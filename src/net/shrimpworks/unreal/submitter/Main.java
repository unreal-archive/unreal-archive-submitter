package net.shrimpworks.unreal.submitter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jgit.api.errors.GitAPIException;

public class Main {

	public static void main(String[] args) throws IOException, GitAPIException {
		final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

		final ContentRepository contentRepo = new ContentRepository(
				System.getenv().getOrDefault("GH_REPO", "https://github.com/unreal-archive/unreal-archive-data.git"),
				System.getenv().getOrDefault("GH_USERNAME", ""),
				System.getenv().getOrDefault("GH_PASSWORD", ""),
				System.getenv().getOrDefault("GH_EMAIL", ""),
				scheduler
		);

		final SubmissionProcessor subProcessor = new SubmissionProcessor(contentRepo, 5, scheduler);

		new WebApp(InetSocketAddress.createUnresolved(
				System.getenv().getOrDefault("BIND_HOST", "localhost"),
				Integer.parseInt(System.getenv().getOrDefault("BIND_PORT", "8081"))
		), subProcessor, System.getenv().getOrDefault("ALLOWED_ORIGIN", "*"));
	}
}
