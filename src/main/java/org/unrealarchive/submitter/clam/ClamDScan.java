package org.unrealarchive.submitter.clam;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClamDScan extends ClamScan {

	private static final Logger logger = LoggerFactory.getLogger(ClamDScan.class);

	private static final String CLAMSCAN_OPTIONS = "-avr";
	private static final String CLAMSCAN = "clamdscan";
	private static final String CLAMD = "/usr/sbin/clamd";

	private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(300);

	private final String clamCommand;
	private final ClamDConfig clamConfig;

	public ClamDScan(String clamCommand, ClamDConfig clamConfig) {
		this.clamCommand = clamCommand;
		this.clamConfig = clamConfig;
	}

	public ClamDScan(ClamDConfig clamConfig) {
		this(CLAMSCAN, clamConfig);
	}

	@Override
	protected String[] clamCommand(Path[] paths) {
		String[] cmd = new String[paths.length + 3];
		cmd[0] = clamCommand;
		cmd[1] = CLAMSCAN_OPTIONS;
		cmd[2] = String.format("--config-file=%s", clamConfig.clamdConf.toAbsolutePath());
		for (int i = 0; i < paths.length; i++) {
			cmd[i + 3] = paths[0].toAbsolutePath().toString();
		}
		return cmd;
	}

	public static class ClamDConfig implements Closeable {

		public final Path socketPath;
		public final Path clamdConf;

		public ClamDConfig(Path socketPath) throws IOException {
			this.socketPath = socketPath;
			this.clamdConf = Files.createTempFile("clamd", ".conf");
			Files.writeString(this.clamdConf, String.format("LocalSocket %s", socketPath.toAbsolutePath()));
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
		private final ClamDConfig clamConfig;
		private final Process clamd;

		public ClamD(String clamdCommand, ClamDConfig clamConfig) {
			this.clamdCommand = clamdCommand;
			this.clamConfig = clamConfig;

			try {
				this.clamd = startClamd();
			} catch (Exception e) {
				throw new IllegalStateException(String.format("Failed to start clamd with command %s", clamdCommand), e);
			}
		}

		public ClamD(ClamDConfig clamConfig) {
			this(CLAMD, clamConfig);
		}

		private Process startClamd() throws IOException {
			Process clamd = new ProcessBuilder()
				.command(
					this.clamdCommand,
					"-F",
					String.format("--config-file=%s", this.clamConfig.clamdConf.toAbsolutePath())
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
