package org.unrealarchive.submitter.submit;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.unrealarchive.submitter.CollectionRepository;

public class CollectionProcessor implements Closeable {

	private static final Logger logger = LoggerFactory.getLogger(CollectionProcessor.class);

	private static final long POLL_WAIT = 5000;

	private final BlockingDeque<CollectionSubmissions.Job> pending;
	private final Map<String, CollectionSubmissions.Job> jobs;

	private volatile boolean stopped;

	public CollectionProcessor(CollectionRepository repo, int queueSize, ScheduledExecutorService executor) {
		this.jobs = new HashMap<>();
		this.pending = new LinkedBlockingDeque<>(queueSize);

		this.stopped = false;

		final Runnable processor = new Runnable() {
			@Override
			public void run() {
				if (stopped) return;

				try {
					CollectionSubmissions.Job job = pending.pollFirst(POLL_WAIT, TimeUnit.MILLISECONDS);
					if (job != null) {
						try {
							job.log("Picked up for processing");
							repo.lock();
							try {
								repo.submit(job);
							} finally {
								repo.unlock();
							}
						} catch (Exception e) {
							job.log(CollectionSubmissions.JobState.SUBMIT_FAILED, String.format("Failed to process submission: %s", e.getMessage()), e);
							logger.warn("Collection submission processing failure", e);
						}
					}
				} catch (InterruptedException e) {
					logger.warn("Collection submission queue processing failure", e);
				}

				if (!stopped) executor.submit(this);
			}
		};

		executor.submit(processor);

		logger.info("Collection processor started");
	}

	public void trackJob(CollectionSubmissions.Job job) {
		this.jobs.put(job.id, job);
	}

	public Collection<CollectionSubmissions.Job> jobs() {
		return Collections.unmodifiableCollection(jobs.values());
	}

	public CollectionSubmissions.Job job(String jobId) {
		return jobs.get(jobId);
	}

	public boolean add(CollectionSubmissions.Job job) {
		trackJob(job);
		return pending.offerLast(job);
	}

	@Override
	public void close() {
		stopped = true;
	}
}
