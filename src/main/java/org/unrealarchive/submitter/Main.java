package org.unrealarchive.submitter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jgit.api.errors.GitAPIException;

public class Main {

	public static void main(String[] args) throws IOException, GitAPIException {
		final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

		final ContentRepository contentRepo = new ContentRepository(
			System.getenv().getOrDefault("GIT_REPO", "https://github.com/unreal-archive/unreal-archive-data.git"),
			System.getenv().getOrDefault("GIT_USERNAME", ""),
			System.getenv().getOrDefault("GIT_PASSWORD", ""),
			System.getenv().getOrDefault("GIT_EMAIL", ""),
			System.getenv().getOrDefault("GH_TOKEN", ""),
			scheduler
		);

		ClamScan clamScan;

		if (System.getenv().getOrDefault("CLAM_SOCKET", "").isEmpty()) {

			final ClamDScan.ClamDConfig clamConfig = new ClamDScan.ClamDConfig(
				Paths.get(System.getenv().getOrDefault("CLAM_SOCKET", Files.createTempDirectory("clamd").resolve("clamd.ctl").toString()))
			);

			// only spawn a clamd instance if we haven't been provided with a socket
			final ClamDScan.ClamD[] clamd = new ClamDScan.ClamD[] { null };
			if (!System.getenv().containsKey("CLAM_SOCKET")) {
				clamd[0] = new ClamDScan.ClamD(clamConfig);
			}

			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				clamConfig.close();
				if (clamd[0] != null) clamd[0].close();
			}));

			clamScan = new ClamDScan(clamConfig);
		} else {
			clamScan = new ClamScan();
		}

		final Path jobsPath = Files.createDirectories(Paths.get(
			System.getenv().getOrDefault("JOBS_PATH", "/tmp")
		));
		final Path uploadPath = Files.createDirectories(Paths.get(
			System.getenv().getOrDefault("UPLOAD_PATH", "/tmp/ua-submit-files")
		));

		final SubmissionProcessor subProcessor = new SubmissionProcessor(contentRepo, clamScan, 5, scheduler, jobsPath);

		final WebApp webApp = new WebApp(InetSocketAddress.createUnresolved(
			System.getenv().getOrDefault("BIND_HOST", "localhost"),
			Integer.parseInt(System.getenv().getOrDefault("BIND_PORT", "8081"))
		), subProcessor, uploadPath, System.getenv().getOrDefault("ALLOWED_ORIGIN", "*"));

		// shutdown hook to cleanup repo
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			webApp.close();
			contentRepo.close();
			scheduler.shutdownNow();
		}));

	}
}
