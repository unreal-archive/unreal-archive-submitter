package net.shrimpworks.unreal.submitter;

import java.beans.ConstructorProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class Submissions {

	public enum JobState {
		CREATED,
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
		COMPLETED
	}

	public static class Job {

		public final String id;
		public final List<LogEntry> log;
		public JobState state;

		public final transient BlockingQueue<LogEntry> logEvents;

		@ConstructorProperties({ "id", "log", "state" })
		public Job(String id, List<LogEntry> log, JobState state) {
			this.id = id;
			this.log = log;
			this.state = state;

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
			return log(state, new LogEntry(message));
		}

		public Job log(JobState state, String message, Throwable error) {
			return log(state, new LogEntry(message, error));
		}

		public Job log(String message) {
			return log(state, new LogEntry(message));
		}

		public Job log(String message, Throwable error) {
			return log(state, new LogEntry(message, error));
		}

		public List<LogEntry> log() {
			return Collections.unmodifiableList(log);
		}

		public List<LogEntry> pollLog(Duration timeout) {
			try {
				final LogEntry head = logEvents.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
				final List<LogEntry> polledLogs = new ArrayList<>();
				if (head != null) polledLogs.add(head);
				logEvents.drainTo(polledLogs);
				return polledLogs;
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return Collections.emptyList();
		}
	}

	public static class LogEntry {

		public final long time;
		public final String message;
		public final Throwable error;

		public LogEntry(String message) {
			this(System.currentTimeMillis(), message, null);
		}

		public LogEntry(String message, Throwable error) {
			this(System.currentTimeMillis(), message, error);
		}

		@ConstructorProperties({ "time", "message", "error" })
		public LogEntry(long time, String message, Throwable error) {
			this.time = time;
			this.message = message;
			this.error = error;
		}

		@Override
		public String toString() {
			return String.format("[%s] message=%s", time, message);
		}
	}
}
