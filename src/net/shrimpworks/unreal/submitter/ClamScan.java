package net.shrimpworks.unreal.submitter;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.timgroup.statsd.StatsDClient;

public class ClamScan {

	enum ClamResult {
		OK,
		VIRUS,
		FAILED,
		ERROR
	}

	private static final String DEFAULT_OPTIONS = "-avr";
	private static final String CLAMSCAN = "clamscan";

	private static final Duration TIMEOUT = Duration.ofSeconds(120);

	private final String clamCommand;
	private final StatsDClient statsD;

	public ClamScan(String clamCommand, StatsDClient statsD) {
		this.clamCommand = clamCommand;
		this.statsD = statsD;
	}

	public ClamScan(StatsDClient statsD) {
		this(CLAMSCAN, statsD);
	}

	public ClamResult scan(Submissions.Job job, Path... paths) {
		job.log(Submissions.JobState.VIRUS_SCANNING, "Scanning for viruses");
		try {
			Process process = new ProcessBuilder()
					.command(clamCommand(paths))
					.start();
			boolean b = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			if (!b) {
				process.destroyForcibly().waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
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
			job.log(Submissions.JobState.VIRUS_ERROR, "Virus scan error.", new RuntimeException("Virus scan error"));
			return ClamResult.ERROR;
		}
	}

	private String[] clamCommand(Path[] paths) {
		String[] cmd = new String[paths.length + 2];
		cmd[0] = clamCommand;
		cmd[1] = DEFAULT_OPTIONS;
		for (int i = 0; i < paths.length; i++) {
			cmd[i + 2] = paths[0].toAbsolutePath().toString();
		}
		return cmd;
	}
}
