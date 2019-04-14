package net.shrimpworks.unreal.submitter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClient;
import org.eclipse.jgit.api.errors.GitAPIException;

public class Main {

	public static void main(String[] args) throws IOException, GitAPIException {
		final StatsDClient statsD = new NonBlockingStatsDClient("archive.submitter",
																System.getenv().getOrDefault("STATS_HOST", ""),
																Integer.parseInt(System.getenv().getOrDefault("STATS_PORT", "8125")));

		final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

		final RuntimeStats runtimeStats = new RuntimeStats(statsD, scheduler);

		final ContentRepository contentRepo = new ContentRepository(
				System.getenv().getOrDefault("GH_REPO", "https://github.com/unreal-archive/unreal-archive-data.git"),
				System.getenv().getOrDefault("GH_USERNAME", ""),
				System.getenv().getOrDefault("GH_PASSWORD", ""),
				System.getenv().getOrDefault("GH_EMAIL", ""),
				scheduler,
				statsD);

		final ClamScan clamScan = new ClamScan(statsD);

		final SubmissionProcessor subProcessor = new SubmissionProcessor(contentRepo, clamScan, 5, scheduler, statsD);

		final WebApp webApp = new WebApp(InetSocketAddress.createUnresolved(
				System.getenv().getOrDefault("BIND_HOST", "localhost"),
				Integer.parseInt(System.getenv().getOrDefault("BIND_PORT", "8081"))
		), subProcessor, System.getenv().getOrDefault("ALLOWED_ORIGIN", "*"), statsD);

		// shutdown hook to cleanup repo
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			webApp.close();
			contentRepo.close();
			runtimeStats.close();
			scheduler.shutdownNow();
		}));

	}
}
