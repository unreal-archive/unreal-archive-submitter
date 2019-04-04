package net.shrimpworks.unreal.submitter;

import java.beans.ConstructorProperties;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Submissions {

	public static class Job {

		public final String id;
		public final List<LogEntry> log;

		@ConstructorProperties({ "id", "log" })
		public Job(String id, List<LogEntry> log) {
			this.id = id;
			this.log = log;
		}

		public Job() {
			this(Long.toHexString(Double.doubleToLongBits(Math.random())).substring(8), new ArrayList<>());
		}

		public Job log(LogEntry log) {
			this.log.add(log);
			return this;
		}

		public Job log(String message) {
			return log(new LogEntry(message));
		}

		public Job log(String message, Throwable error) {
			return log(new LogEntry(message, error));
		}

		public List<LogEntry> log() {
			return Collections.unmodifiableList(log);
		}
	}

	public static class LogEntry {

		public final LocalDateTime time;
		public final String message;
		public final Throwable error;

		public LogEntry(String message) {
			this(LocalDateTime.now(), message, null);
		}

		public LogEntry(String message, Throwable error) {
			this(LocalDateTime.now(), message, error);
		}

		@ConstructorProperties({ "time", "message", "error" })
		public LogEntry(LocalDateTime time, String message, Throwable error) {
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
