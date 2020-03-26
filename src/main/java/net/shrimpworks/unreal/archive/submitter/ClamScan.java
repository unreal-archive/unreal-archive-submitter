package net.shrimpworks.unreal.archive.submitter;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import com.timgroup.statsd.StatsDClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClamScan {

	private static final Logger logger = LoggerFactory.getLogger(ClamScan.class);

	enum ClamResult {
		OK,
		VIRUS,
		FAILED,
		ERROR
	}

	private static final String CLAMSCAN_OPTIONS = "-avr";
	private static final String CLAMSCAN = "clamdscan";
	private static final String CLAMD = "/usr/sbin/clamd";

	private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(300);

	private final String clamCommand;
	private final ClamConfig clamConfig;
	private final StatsDClient statsD;

	public ClamScan(String clamCommand, ClamConfig clamConfig, StatsDClient statsD) {
		this.clamCommand = clamCommand;
		this.clamConfig = clamConfig;
		this.statsD = statsD;
	}

	public ClamScan(ClamConfig clamConfig, StatsDClient statsD) {
		this(CLAMSCAN, clamConfig, statsD);
	}

	public ClamResult scan(Submissions.Job job, Path... paths) {
		job.log(Submissions.JobState.VIRUS_SCANNING, "Scanning for viruses");
		try {
			String[] clamCommand = clamCommand(paths);
			logger.info("Invoking clam scan with command {}", String.join(" ", clamCommand));
			Process process = new ProcessBuilder()
					.command(clamCommand)
					.start();
			boolean b = process.waitFor(SCAN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			if (!b) {
				process.destroyForcibly().waitFor(SCAN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			}

			ClamResult clamResult = ClamResult.values()[process.exitValue()];
			switch (clamResult) {
				case OK:
					job.log(Submissions.JobState.VIRUS_FREE, "No viruses found");
					break;
				case VIRUS:
					job.log(Submissions.JobState.VIRUS_FOUND, "Viruses found!!", new RuntimeException("Found a virus"));
					break;
				case FAILED:
				default:
					job.log(Submissions.JobState.VIRUS_ERROR, "Virus scan failed.", new RuntimeException("Virus scan failure"));
					break;
			}

			return clamResult;
		} catch (Exception e) {
			logger.error("Virus scan failure", e);
			job.log(Submissions.JobState.VIRUS_ERROR, "Virus scan error.", new RuntimeException("Virus scan error"));
			return ClamResult.ERROR;
		}
	}

	private String[] clamCommand(Path[] paths) {
		String[] cmd = new String[paths.length + 3];
		cmd[0] = clamCommand;
		cmd[1] = CLAMSCAN_OPTIONS;
		cmd[2] = String.format("--config-file=%s", clamConfig.clamdConf.toAbsolutePath().toString());
		for (int i = 0; i < paths.length; i++) {
			cmd[i + 3] = paths[0].toAbsolutePath().toString();
		}
		return cmd;
	}

	public static class ClamConfig implements Closeable {

		public final Path socketPath;
		public final Path clamdConf;

		public ClamConfig(Path socketPath) throws IOException {
			this.socketPath = socketPath;
			this.clamdConf = Files.createTempFile("clamd", ".conf");
			Files.writeString(this.clamdConf, String.format("LocalSocket %s", socketPath.toAbsolutePath().toString()));
			logger.info("Created config file at {}", clamdConf);
		}

		@Override
		public void close() {
			try {
				Files.deleteIfExists(clamdConf);
			} catch (Exception ex) {
				logger.warn("Failed to remove clam config file {}}", clamdConf, ex);
			}
		}
	}

	public static class ClamD implements Closeable {

		private final String clamdCommand;
		private final ClamConfig clamConfig;
		private final Process clamd;

		public ClamD(String clamdCommand, ClamConfig clamConfig) {
			this.clamdCommand = clamdCommand;
			this.clamConfig = clamConfig;

			try {
				this.clamd = startClamd();
			} catch (Exception e) {
				throw new IllegalStateException(String.format("Failed to start clamd with command %s", clamdCommand), e);
			}
		}

		public ClamD(ClamConfig clamConfig) {
			this(CLAMD, clamConfig);
		}

		private Process startClamd() throws IOException {
			Process clamd = new ProcessBuilder()
					.command(
							this.clamdCommand,
							"-F",
							String.format("--config-file=%s", this.clamConfig.clamdConf.toAbsolutePath().toString())
					)
					.inheritIO()
					.start();
			logger.info("Started clamd {} with config file {}", this.clamdCommand, this.clamConfig.clamdConf);
			return clamd;
		}

		@Override
		public void close() {
			if (this.clamd.isAlive()) {
				logger.info("Stopping ClamD");
				this.clamd.destroyForcibly();
			}
		}
	}
}
