package net.shrimpworks.unreal.submitter;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

public class SubmissionProcessor implements Closeable {

	private static final PendingSubmission[] PENDING_ARRAY = new PendingSubmission[0];
	private static final Duration POLL_WAIT = Duration.ofSeconds(5);

	private final BlockingDeque<PendingSubmission> pending;
	private final ContentRepository repo;

	private volatile boolean stopped;

	public SubmissionProcessor(ContentRepository repo, int queueSize, ExecutorService executor) {
		this.repo = repo;
		this.pending = new LinkedBlockingDeque<>(queueSize);

		this.stopped = false;

		final Runnable runnable = new Runnable() {
			@Override
			public void run() {
				if (stopped) return;

				try {
					PendingSubmission sub = pending.pollFirst(POLL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
					if (sub != null) {
						try {
							sub.job.log("Picked up for processing");
							process(sub);
						} catch (Exception e) {
							sub.job.log(Submissions.JobState.FAILED, String.format("Failed to process submission: %s", e.getMessage()), e);
						}
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				if (!stopped) executor.submit(this);
			}
		};

		executor.submit(runnable);
	}

	// --- public methods

	public PendingSubmission[] pending() {
		return pending.toArray(PENDING_ARRAY);
	}

	public boolean add(PendingSubmission submission) {
		return pending.offerLast(submission);
	}

	@Override
	public void close() {
		stopped = true;
	}

	// --- private helpers

	private void process(PendingSubmission submission) {
		switch (submission.job.state) {
			case CREATED:
				if (scan(submission)) {
					// successful scan, re-add it to the queue for indexing
					add(submission);
				}
				break;
			case SCANNED:
				index(submission);
				break;
			default:
				submission.job.log("Invalid processing state");
		}
	}

	private boolean scan(PendingSubmission submission) {
		try {
			repo.scan(submission.job, submission.files);
			return (submission.job.state == Submissions.JobState.SCANNED);
		} catch (IOException e) {
			submission.job.log(Submissions.JobState.SCAN_FAILED, "Scanning failed", e);
		}
		return false;
	}

	private void index(PendingSubmission submission) {
		// use the repo to index and submit PR
		repo.lock();
		try {
			if (!repo.submit(submission.job, submission.files).isEmpty()) {
				submission.job.log(Submissions.JobState.COMPLETED, "Complete!");
			}
		} catch (Exception e) {
			submission.job.log(
					Submissions.JobState.FAILED, String.format("Failed to index or submit content: %s", e.getMessage()), e
			);
		} finally {
			repo.unlock();
		}
	}

	public static class PendingSubmission {

		public final Submissions.Job job;
		public final LocalDateTime submitted;
		public final String name;
		public final Path[] files;

		public PendingSubmission(Submissions.Job job, LocalDateTime submitted, String name, Path[] files) {
			this.job = job;
			this.submitted = submitted;
			this.name = name;
			this.files = files;
		}
	}
}
