package net.shrimpworks.unreal.archive.submitter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClient;
import org.eclipse.jgit.api.errors.GitAPIException;

public class Main {

	public static void main(String[] args) throws IOException, GitAPIException {
		final StatsDClient statsD = new NonBlockingStatsDClient("unreal-archive.submitter",
																System.getenv().getOrDefault("STATS_HOST", ""),
																Integer.parseInt(System.getenv().getOrDefault("STATS_PORT", "8125")));

		final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

		final RuntimeStats runtimeStats = new RuntimeStats(statsD, scheduler);

		final ContentRepository contentRepo = new ContentRepository(
				System.getenv().getOrDefault("GIT_REPO", "https://github.com/unreal-archive/unreal-archive-data.git"),
				System.getenv().getOrDefault("GIT_USERNAME", ""),
				System.getenv().getOrDefault("GIT_PASSWORD", ""),
				System.getenv().getOrDefault("GIT_EMAIL", ""),
				System.getenv().getOrDefault("GH_TOKEN", ""),
				scheduler,
				statsD
		);

		final ClamScan.ClamConfig clamConfig = new ClamScan.ClamConfig(
				Paths.get(System.getenv().getOrDefault("CLAM_SOCKET", Files.createTempDirectory("clamd").resolve("clamd.ctl").toString()))
		);

		// only spawn a clamd instance if we haven't been provided with a socket
		final ClamScan.ClamD[] clamd = new ClamScan.ClamD[] { null };
		if (!System.getenv().containsKey("CLAM_SOCKET")) {
			clamd[0] = new ClamScan.ClamD(clamConfig);
		}

		final ClamScan clamScan = new ClamScan(clamConfig, statsD);

		final Path jobsPath = Files.createDirectories(Paths.get(
				System.getenv().getOrDefault("JOBS_PATH", "/tmp")
		));
		final Path uploadPath = Files.createDirectories(Paths.get(
				System.getenv().getOrDefault("UPLOAD_PATH", "/tmp/ua-submit-files")
		));

		final SubmissionProcessor subProcessor = new SubmissionProcessor(contentRepo, clamScan, 5, scheduler, jobsPath, statsD);

		final WebApp webApp = new WebApp(InetSocketAddress.createUnresolved(
				System.getenv().getOrDefault("BIND_HOST", "localhost"),
				Integer.parseInt(System.getenv().getOrDefault("BIND_PORT", "8081"))
		), subProcessor, uploadPath, System.getenv().getOrDefault("ALLOWED_ORIGIN", "*"), statsD);

		// shutdown hook to cleanup repo
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			clamConfig.close();
			if (clamd[0] != null) clamd[0].close();
			webApp.close();
			contentRepo.close();
			runtimeStats.close();
			scheduler.shutdownNow();
		}));

	}
}
