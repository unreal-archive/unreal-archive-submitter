package net.shrimpworks.unreal.submitter;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class ClamScan {

	enum ClamResult {
		OK,
		VIRUS,
		FAILED,
		ERROR
	}

	private static final String DEFAULT_OPTIONS = "-avr";
	private static final String CLAMSCAN = "clamscan";

	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	private final String clamCommand;

	public ClamScan(String clamCommand) {
		this.clamCommand = clamCommand;
	}

	public ClamScan() {
		this(CLAMSCAN);
	}

	public ClamResult scan(Path[] paths) {
		try {
			Process process = new ProcessBuilder()
					.command(clamCommand(paths))
					.start();
			boolean b = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			if (!b) {
				process.destroyForcibly().waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			}

			return ClamResult.values()[process.exitValue()];
		} catch (Exception e) {
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
