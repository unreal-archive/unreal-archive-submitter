package org.unrealarchive.submitter.submit;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.unrealarchive.submitter.ContentRepository;
import org.unrealarchive.submitter.clam.ClamScan;

public class SubmissionProcessor implements Closeable {

	private static final Logger logger = LoggerFactory.getLogger(SubmissionProcessor.class);

	private static final PendingSubmission[] PENDING_ARRAY = {};
	private static final Duration POLL_WAIT = Duration.ofSeconds(5);
	private static final Duration SWEEP_RATE = Duration.ofSeconds(120);
	private static final Duration SWEEP_AGE = Duration.ofHours(36);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	static {
		MAPPER.configure(SerializationFeature.INDENT_OUTPUT, true);
	}

	private final BlockingDeque<PendingSubmission> pending;
	private final ContentRepository repo;
	private final ClamScan clamScan;
	private final Path jobsPath;
	private final Map<String, Submissions.Job> jobs;

	private volatile boolean stopped;

	public SubmissionProcessor(
		ContentRepository repo, ClamScan clamScan, int queueSize, ScheduledExecutorService executor, Path jobsPath) {
		this.repo = repo;
		this.clamScan = clamScan;
		this.jobs = new HashMap<>();
		this.pending = new LinkedBlockingDeque<>(queueSize);
		this.jobsPath = jobsPath;

		this.stopped = false;

		final Runnable processor = new Runnable() {
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
							logger.warn("Submission processing failure", e);
						} finally {
							writeJob(sub);
						}
					}
				} catch (InterruptedException e) {
					logger.warn("Submission queue processing failure", e);
				}

				if (!stopped) executor.submit(this);
			}
		};

		final Runnable cleaner = () -> {
			if (stopped) return;
			jobs.entrySet().removeIf(e -> {
				Submissions.Job job = e.getValue();
				Submissions.LogEntry last = job.log.getLast();
				return last.time < System.currentTimeMillis() - SWEEP_AGE.toMillis();
			});
		};

		executor.submit(processor);
		executor.scheduleAtFixedRate(cleaner, SWEEP_RATE.toMillis(), SWEEP_RATE.toMillis(), TimeUnit.MILLISECONDS);

		logger.info("Submission processor started");
	}

	// --- public methods

	public PendingSubmission[] pending() {
		return pending.toArray(PENDING_ARRAY);
	}

	public boolean trackJob(Submissions.Job job) {
		return this.jobs.put(job.id, job) == null;
	}

	public Collection<Submissions.Job> jobs() {
		return Collections.unmodifiableCollection(jobs.values());
	}

	public Submissions.Job job(String jobId) {
		return jobs.get(jobId);
	}

	public boolean add(PendingSubmission submission) {
		return pending.offerLast(submission);
	}

	@Override
	public void close() {
		stopped = true;
	}

	// --- private helpers

	private void writeJob(PendingSubmission submission) {
		try {
			final String fName = String.format("%d-%s.json", submission.submitTime, submission.job.id);
			Files.write(jobsPath.resolve(fName), MAPPER.writeValueAsBytes(submission));
		} catch (Exception e) {
			logger.warn("Failed to write job file", e);
		}
	}

	private void process(PendingSubmission submission) {
		switch (submission.job.state) {
			case CREATED -> {
				if (virusScan(submission)) {
					// no viruses, re-add it to the queue for scanning
					add(submission);
				} else {
					// probably a virus, cleanup
					fileCleanup(submission);
				}
			}
			case VIRUS_FREE -> {
				if (scan(submission)) {
					// successful scan, re-add it to the queue for indexing
					add(submission);
				} else {
					// no indexable content, cleanup
					fileCleanup(submission);
				}
			}
			case SCANNED -> {
				index(submission);
				// completed, cleanup
				fileCleanup(submission);
			}
			default -> submission.job.log("Invalid processing state " + submission.job.state, Submissions.LogType.ERROR);
		}
	}

	private boolean virusScan(PendingSubmission submission) {
		return clamScan.scan(submission.job, submission.files) == ClamScan.ClamResult.OK;
	}

	private boolean scan(PendingSubmission submission) {
		if (submission.job.forcedType != null) {
			submission.job.log(Submissions.JobState.SCANNED, "Content scan skipped, forcing type to " + submission.job.forcedType.name());
			return true;
		}

		try {
			repo.scan(submission.job, submission.files);
			return (submission.job.state == Submissions.JobState.SCANNED);
		} catch (IOException e) {
			submission.job.log(Submissions.JobState.SCAN_FAILED, "Scanning failed", e);
			logger.warn("Submission scanning failure", e);
		}
		return false;
	}

	private void index(PendingSubmission submission) {
		// use the repo to index and submit PR
		repo.lock();
		try {
			if (!repo.submit(submission.job, submission.files).isEmpty()) {
				submission.job.log(Submissions.JobState.COMPLETED, "Complete!", Submissions.LogType.GOOD);
			} else {
				submission.job.log(Submissions.JobState.FAILED, "No content was added", Submissions.LogType.ERROR);
				logger.warn("Content index returned an empty result");
			}
		} catch (Exception e) {
			submission.job.log(Submissions.JobState.FAILED, String.format("Failed to index or submit content: %s", e.getMessage()), e);
			logger.warn("Submission indexing failure", e);
		} finally {
			repo.unlock();
		}
	}

	private void fileCleanup(PendingSubmission submission) {
		for (Path file : submission.files) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				logger.warn("Failed to delete file {} for job {}", file, submission.job.id);
			}
		}
	}

	public record PendingSubmission(Submissions.Job job, long submitTime, String name, Path[] files) {
	}
}
