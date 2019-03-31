package net.shrimpworks.unreal.submitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import net.shrimpworks.unreal.archive.ArchiveUtil;
import net.shrimpworks.unreal.archive.content.Content;
import net.shrimpworks.unreal.archive.content.ContentManager;
import net.shrimpworks.unreal.archive.content.IndexResult;
import net.shrimpworks.unreal.archive.content.Scanner;
import net.shrimpworks.unreal.archive.storage.DataStore;

public class ContentRepository {

	private static final Duration GIT_POLL_TIME = Duration.ofMinutes(30);

	static final String GH_USERNAME = System.getenv().getOrDefault("GH_USERNAME", "anonymous");
	static final String GH_PASSWORD = System.getenv().getOrDefault("GH_PASSWORD", "");
	static final String GH_EMAIL = System.getenv().getOrDefault("GH_EMAIL", "anon@localhost");

	static final String GIT_ORG = "unreal-archive";
	static final String GIT_REPO = "unreal-archive-data";

	static final String GIT_REPO_URL = String.format("https://github.com/%s/%s.git", GIT_ORG, GIT_REPO);
	static final String GIT_CLONE_URL = System.getenv().getOrDefault("GIT_CLONE_URL", GIT_REPO_URL);

	private final Git gitRepo;
	private final PersonIdent gitAuthor;

	private ContentManager content;

	public ContentRepository(String repoUrl, String authUsername, String authPassword, String email, ScheduledExecutorService executor)
			throws IOException, GitAPIException {
		final Path tmpDir = Files.createTempDirectory("ua-submit-");

		// TODO shutdown hook to cleanup repo
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				System.out.printf("Cleaning working path %s", tmpDir);
				ArchiveUtil.cleanPath(tmpDir);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}));

		// TODO on startup, clone git repo
		final CredentialsProvider credentials = new UsernamePasswordCredentialsProvider(authUsername, authPassword);
		this.gitAuthor = new PersonIdent(authUsername, email);
		this.gitRepo = Git.cloneRepository()
						  .setCredentialsProvider(credentials)
						  .setURI(GIT_CLONE_URL)
						  .setBranch("master")
						  .setDirectory(tmpDir.toFile())
						  .call();

		// TODO create a ContentManager
		this.content = initContentManager(tmpDir);

		// TODO on a schedule, pull repo remote
		executor.scheduleWithFixedDelay(() -> {
			try {
				// remember current ref
				final ObjectId old = gitRepo.getRepository().findRef("master").getObjectId();

				// pull latest
				gitRepo.pull().call();

				// TODO if it changed, re-create ContentManager
				if (!old.equals(gitRepo.getRepository().findRef("master").getObjectId())) {
					content = initContentManager(tmpDir);
				}
			} catch (IOException | GitAPIException e) {
				e.printStackTrace();
			}
		}, GIT_POLL_TIME.toMillis(), GIT_POLL_TIME.toMillis(), TimeUnit.MILLISECONDS);
	}

	private ContentManager initContentManager(Path path) throws IOException {
		// FIXME provide datastore
		return new ContentManager(path.resolve("content"),
								  new DataStore.NopStore(),
								  new DataStore.NopStore(),
								  new DataStore.NopStore());
	}

	public Set<Scanner.ScanResult> scan(Path[] paths) {
		final ContentManager cm = this.content;
		// TODO scan path with content manager
		return null;
	}

	public Set<IndexResult<? extends Content>> index(Path[] paths) {
		final ContentManager cm = this.content;
		// TODO index path with content manager
		return null;
	}

	// TODO scan and index paths, push changes to remote, create pull request
}
