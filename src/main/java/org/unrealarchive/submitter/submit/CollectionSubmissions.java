package org.unrealarchive.submitter.submit;

import java.beans.ConstructorProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class CollectionSubmissions {

	public enum JobState {
		CREATED,
		CHECKING_IN,
		CHECKED_IN,
		CHECKIN_FAILED,
		ARCHIVING,
		ARCHIVED,
		ARCHIVE_FAILED,
		SYNCING,
		SYNCED,
		SYNC_FAILED,
		SUBMITTING,
		SUBMITTED,
		SUBMIT_FAILED,
		COMPLETED;

		private static final Set<JobState> DONE_STATES = Set.of(
			CHECKIN_FAILED, ARCHIVE_FAILED, SYNC_FAILED, SUBMIT_FAILED, COMPLETED
		);

		public boolean done() {
			return DONE_STATES.contains(this);
		}
	}

	public static class Job {

		public final String id;
		public final CollectionSubmission submission;
		public final List<Submissions.LogEntry> log;
		public volatile JobState state;
		public boolean done;

		public final transient BlockingQueue<Submissions.LogEntry> logEvents;

		public Job(CollectionSubmission submission) {
			this(UUID.randomUUID().toString(), submission, JobState.CREATED, new ArrayList<>());
		}

		@ConstructorProperties({ "id", "submission", "state", "log" })
		public Job(String id, CollectionSubmission submission, JobState state, List<Submissions.LogEntry> log) {
			this.id = id;
			this.submission = submission;
			this.state = state;
			this.log = log;
			this.done = false;
			this.logEvents = new ArrayBlockingQueue<>(20);
		}

		public void log(String message) {
			log(state, new Submissions.LogEntry(message, Submissions.LogType.INFO));
		}

		public void log(String message, Submissions.LogType type) {
			log(state, new Submissions.LogEntry(message, type));
		}

		public void log(String message, Throwable error) {
			log(state, new Submissions.LogEntry(message, error));
		}

		public void log(JobState state, String message) {
			log(state, new Submissions.LogEntry(message, Submissions.LogType.INFO));
		}

		public void log(JobState state, String message, Submissions.LogType type) {
			log(state, new Submissions.LogEntry(message, type));
		}

		public void log(JobState state, String message, Throwable error) {
			log(state, new Submissions.LogEntry(message, error));
		}

		public void log(JobState state, Submissions.LogEntry logEntry) {
			this.state = state;
			this.log.add(logEntry);
			this.logEvents.offer(logEntry);
		}

		@JsonIgnore
		public Collection<Submissions.LogEntry> log() {
			return Collections.unmodifiableCollection(log);
		}

		public List<Submissions.LogEntry> pollLog(Duration timeout) throws InterruptedException {
			final Submissions.LogEntry head = logEvents.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
			final List<Submissions.LogEntry> polledLogs = new ArrayList<>();
			if (head != null) polledLogs.add(head);
			logEvents.drainTo(polledLogs);

			if (!done && state.done()) done = true;

			return polledLogs;
		}
	}
}
