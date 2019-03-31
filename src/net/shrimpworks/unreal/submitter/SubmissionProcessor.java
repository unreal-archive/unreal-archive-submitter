package net.shrimpworks.unreal.submitter;

import java.io.Closeable;
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
							process(sub);
						} catch (Exception e) {
							e.printStackTrace(); // FIXME
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

	private boolean process(PendingSubmission submission) {
		// TODO use the repo to index and submit PR
		repo.lock();
		try {
			return !repo.submit(submission.jobId, submission.files).isEmpty();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			repo.unlock();
		}
		return false;
	}

	public static class PendingSubmission {

		public final String jobId;
		public final LocalDateTime submitted;
		public final String name;
		public final Path[] files;

		public PendingSubmission(String jobId, LocalDateTime submitted, String name, Path[] files) {
			this.jobId = jobId;
			this.submitted = submitted;
			this.name = name;
			this.files = files;
		}
	}
}
