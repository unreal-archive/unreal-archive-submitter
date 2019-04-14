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

import com.timgroup.statsd.StatsDClient;

public class SubmissionProcessor implements Closeable {

	private static final PendingSubmission[] PENDING_ARRAY = {};
	private static final Duration POLL_WAIT = Duration.ofSeconds(5);

	private final BlockingDeque<PendingSubmission> pending;
	private final ContentRepository repo;
	private final ClamScan clamScan;
	private final StatsDClient statsD;

	private volatile boolean stopped;

	public SubmissionProcessor(ContentRepository repo, ClamScan clamScan, int queueSize, ExecutorService executor, StatsDClient statsD) {
		this.repo = repo;
		this.clamScan = clamScan;
		this.pending = new LinkedBlockingDeque<>(queueSize);
		this.statsD = statsD;

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

				statsD.gauge("queue", pending.size());

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
		boolean added = pending.offerLast(submission);
		statsD.gauge("queue", pending.size());
		statsD.count("added", 1);
		return added;
	}

	@Override
	public void close() {
		stopped = true;
	}

	// --- private helpers

	private void process(PendingSubmission submission) {
		statsD.count("process." + submission.job.state.name(), 1);
		switch (submission.job.state) {
			case CREATED:
				if (virusScan(submission)) {
					// no viruses, re-add it to the queue for scanning
					add(submission);
				}
				break;
			case VIRUS_FREE:
				if (scan(submission)) {
					// successful scan, re-add it to the queue for indexing
					add(submission);
				}
				break;
			case SCANNED:
				index(submission);
				break;
			default:
				submission.job.log("Invalid processing state " + submission.job.state);
		}
	}

	private boolean virusScan(PendingSubmission submission) {
		final long start = System.currentTimeMillis();
		try {
			ClamScan.ClamResult clamResult = clamScan.scan(submission.job, submission.files);
			statsD.count("processed.virus." + clamResult.name(), 1);
			return clamResult == ClamScan.ClamResult.OK;
		} finally {
			statsD.time("processed.virus", System.currentTimeMillis() - start);
		}
	}

	private boolean scan(PendingSubmission submission) {
		final long start = System.currentTimeMillis();
		try {
			repo.scan(submission.job, submission.files);
			return (submission.job.state == Submissions.JobState.SCANNED);
		} catch (IOException e) {
			statsD.count("submissions.scan.failed", 1);
			submission.job.log(Submissions.JobState.SCAN_FAILED, "Scanning failed", e);
		} finally {
			statsD.time("processed.scan", System.currentTimeMillis() - start);
		}
		return false;
	}

	private void index(PendingSubmission submission) {
		final long start = System.currentTimeMillis();
		// use the repo to index and submit PR
		repo.lock();
		try {
			if (!repo.submit(submission.job, submission.files).isEmpty()) {
				submission.job.log(Submissions.JobState.COMPLETED, "Complete!");
			}
		} catch (Exception e) {
			statsD.count("submissions.index.failed", 1);
			submission.job.log(
					Submissions.JobState.FAILED, String.format("Failed to index or submit content: %s", e.getMessage()), e
			);
		} finally {
			repo.unlock();
			statsD.time("processed.index", System.currentTimeMillis() - start);
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
