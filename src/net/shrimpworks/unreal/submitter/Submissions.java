package net.shrimpworks.unreal.submitter;

import java.beans.ConstructorProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class Submissions {

	public enum LogType {
		INFO,
		WARN,
		ERROR
	}

	public enum JobState {
		CREATED,
		VIRUS_SCANNING,
		VIRUS_FREE,
		VIRUS_FOUND,
		VIRUS_ERROR,
		SCANNING,
		UNKNOWN_CONTENT,
		KNOWN_CONTENT,
		SCAN_FAILED,
		SCANNED,
		INDEXING,
		INDEX_FAILED,
		SUBMITTING,
		SUBMITTED,
		SUBMIT_FAILED,
		FAILED,
		COMPLETED;

		private static final Set<JobState> DONE_STATES = Set.of(
				VIRUS_FOUND, VIRUS_ERROR, UNKNOWN_CONTENT, SCAN_FAILED, INDEX_FAILED, FAILED, COMPLETED
		);

		public boolean done() {
			return DONE_STATES.contains(this);
		}
	}

	public static class Job {

		public final String id;
		public final List<LogEntry> log;
		public JobState state;
		public boolean done;

		public final transient BlockingQueue<LogEntry> logEvents;

		@ConstructorProperties({ "id", "log", "state" })
		public Job(String id, List<LogEntry> log, JobState state) {
			this.id = id;
			this.log = log;
			this.state = state;
			this.done = false;

			this.logEvents = new ArrayBlockingQueue<>(20);
		}

		public Job() {
			this(Long.toHexString(Double.doubleToLongBits(Math.random())).substring(8), new ArrayList<>(), JobState.CREATED);
		}

		public Job log(JobState state, LogEntry log) {
			this.log.add(log);
			this.state = state;
			this.logEvents.offer(log);
			return this;
		}

		public Job log(JobState state, String message) {
			return log(state, new LogEntry(message, LogType.INFO));
		}

		public Job log(JobState state, String message, LogType type) {
			return log(state, new LogEntry(message, type));
		}

		public Job log(JobState state, String message, Throwable error) {
			return log(state, new LogEntry(message, error));
		}

		public Job log(String message) {
			return log(state, new LogEntry(message));
		}

		public Job log(String message, LogType type) {
			return log(state, new LogEntry(message, type));
		}

		public Job log(String message, Throwable error) {
			return log(state, new LogEntry(message, error));
		}

		public List<LogEntry> log() {
			return Collections.unmodifiableList(log);
		}

		public List<LogEntry> pollLog(Duration timeout) throws InterruptedException {
			final LogEntry head = logEvents.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
			final List<LogEntry> polledLogs = new ArrayList<>();
			if (head != null) polledLogs.add(head);
			logEvents.drainTo(polledLogs);

			if (!done && state.done()) done = true;

			return polledLogs;
		}
	}

	public static class LogEntry {

		public final long time;
		public final String message;
		public final Throwable error;
		public final LogType type;

		public LogEntry(String message) {
			this(System.currentTimeMillis(), message, null, LogType.INFO);
		}

		public LogEntry(String message, LogType type) {
			this(System.currentTimeMillis(), message, null, type);
		}

		public LogEntry(String message, Throwable error) {
			this(System.currentTimeMillis(), message, error, LogType.ERROR);
		}

		@ConstructorProperties({ "time", "message", "error", "type" })
		public LogEntry(long time, String message, Throwable error, LogType type) {
			this.time = time;
			this.message = message;
			this.error = error;
			this.type = type;
		}

		@Override
		public String toString() {
			return String.format("[%s] %s %s", time, type, message);
		}
	}
}
