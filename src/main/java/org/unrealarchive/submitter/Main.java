package org.unrealarchive.submitter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jgit.api.errors.GitAPIException;

import org.unrealarchive.submitter.clam.ClamDScan;
import org.unrealarchive.submitter.clam.ClamScan;
import org.unrealarchive.submitter.submit.CollectionProcessor;
import org.unrealarchive.submitter.submit.SubmissionProcessor;

public class Main {

	public static void main(String[] args) throws IOException, GitAPIException {
		final Path contentDir = Files.createTempDirectory("ua-submit-data-");

		final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

		final GitManager gitManager = new GitManager(
			System.getenv().getOrDefault("GIT_REPO", "https://github.com/unreal-archive/unreal-archive-data.git"),
			System.getenv().getOrDefault("GIT_USERNAME", ""),
			System.getenv().getOrDefault("GIT_PASSWORD", ""),
			System.getenv().getOrDefault("GIT_EMAIL", ""),
			System.getenv().getOrDefault("GH_TOKEN", ""),
			contentDir
		);

		ClamScan clamScan;

		if (!System.getenv().getOrDefault("CLAM_SOCKET", "").isEmpty()) {

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

		final ContentRepository contentRepo = new ContentRepository(
			gitManager,
			scheduler,
			contentDir
		);

		final SubmissionProcessor subProcessor = new SubmissionProcessor(contentRepo, clamScan, 5, scheduler, jobsPath);

		final CollectionRepository collectionRepo = new CollectionRepository(contentRepo.gitManager());
		final CollectionProcessor collectionProcessor = new CollectionProcessor(collectionRepo, 5, scheduler);

		final WebApp webApp = new WebApp(InetSocketAddress.createUnresolved(
			System.getenv().getOrDefault("BIND_HOST", "localhost"),
			Integer.parseInt(System.getenv().getOrDefault("BIND_PORT", "8081"))
		), subProcessor, collectionProcessor, uploadPath, System.getenv().getOrDefault("ALLOWED_ORIGIN", "*"));

		// shutdown hook to cleanup repo
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			webApp.close();
			subProcessor.close();
			collectionProcessor.close();
			try {
				collectionRepo.close();
			} catch (IOException e) {
				// ignore
			}
			contentRepo.close();
			gitManager.close();
			scheduler.shutdownNow();
		}));

	}
}
