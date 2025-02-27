package org.unrealarchive.submitter;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

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
	private static final String CLAMSCAN = "clamscan";

	private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(300);

	private final String clamCommand;

	public ClamScan(String clamCommand) {
		this.clamCommand = clamCommand;
	}

	public ClamScan() {
		this(CLAMSCAN);
	}

	public ClamResult scan(Submissions.Job job, Path... paths) {
		job.log(Submissions.JobState.VIRUS_SCANNING, "Scanning for malware");
		try {
			String[] clamCommand = clamCommand(paths);
			logger.info("Invoking clam scan with command {}", String.join(" ", clamCommand));
			Process process = new ProcessBuilder()
				.command(clamCommand)
				.inheritIO()
				.start();
			boolean b = process.waitFor(SCAN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			if (!b) {
				process.destroyForcibly().waitFor(SCAN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			}

			ClamResult clamResult = ClamResult.values()[process.exitValue()];
			switch (clamResult) {
				case OK:
					job.log(Submissions.JobState.VIRUS_FREE, "No malware found");
					break;
				case VIRUS:
					job.log(Submissions.JobState.VIRUS_FOUND, "Malware found!!", new RuntimeException("Found some malware"));
					break;
				case FAILED:
				case ERROR:
				default:
					job.log(Submissions.JobState.VIRUS_ERROR, "Malware scan failed.", new RuntimeException("Malware scan failure"));
					break;
			}

			return clamResult;
		} catch (Exception e) {
			logger.error("Malware scan failure", e);
			job.log(Submissions.JobState.VIRUS_ERROR, "Malware scan error.", new RuntimeException("Malware scan error"));
			return ClamResult.ERROR;
		}
	}

	protected String[] clamCommand(Path[] paths) {
		String[] cmd = new String[paths.length + 3];
		cmd[0] = clamCommand;
		cmd[1] = CLAMSCAN_OPTIONS;
		for (int i = 0; i < paths.length; i++) {
			cmd[i + 2] = paths[0].toAbsolutePath().toString();
		}
		return cmd;
	}
}
