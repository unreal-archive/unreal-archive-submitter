package net.shrimpworks.unreal.submitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.shrimpworks.unreal.archive.ArchiveUtil;
import net.shrimpworks.unreal.archive.CLI;
import net.shrimpworks.unreal.archive.content.Content;
import net.shrimpworks.unreal.archive.content.ContentManager;
import net.shrimpworks.unreal.archive.content.IndexLog;
import net.shrimpworks.unreal.archive.content.IndexResult;
import net.shrimpworks.unreal.archive.content.Indexer;
import net.shrimpworks.unreal.archive.content.Scanner;
import net.shrimpworks.unreal.archive.content.Submission;
import net.shrimpworks.unreal.archive.storage.DataStore;

public class ContentRepository {

	private static final Logger logger = LoggerFactory.getLogger(ContentRepository.class);

	private static final Duration GIT_POLL_TIME = Duration.ofMinutes(30);

	private static final String[] EMPTY_STRING_ARRAY = new String[0];

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

	private volatile boolean contentLock = false;

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
			// skip updating the repo if something is busy with it
			if (contentLock) return;

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

	public void lock() {
		if (contentLock) throw new IllegalStateException("Already locked");
		contentLock = true;
	}

	public void unlock() {
		contentLock = false;
	}

	public Set<Scanner.ScanResult> scan(String jobId, Path[] paths) throws IOException {
		final ContentManager cm = this.content; // remember current content, in case it swaps out mid-process
		final Scanner sc = new Scanner(cm, new CLI(EMPTY_STRING_ARRAY, Collections.emptyMap()));
		final Set<Scanner.ScanResult> scanResults = new HashSet<>();
		// TODO scan path with content manager
		sc.scan(new Scanner.ScannerEvents() {
			@Override
			public void starting(int foundFiles, Pattern included, Pattern excluded) {
				logger.info("[{}] Start scanning paths {}", jobId, Arrays.toString(paths));
			}

			@Override
			public void progress(int scanned, int total, Path currentFile) {
				logger.info("[{}] Scanned {} of {}", jobId, scanned, total);
			}

			@Override
			public void scanned(Scanner.ScanResult scanned) {
				scanResults.add(scanned);
			}

			@Override
			public void completed(int scannedFiles) {
				logger.info("[{}] Completed scanning {}", jobId, scannedFiles);
			}
		}, paths);
		return scanResults;
	}

	public Set<IndexResult<? extends Content>> submit(String jobId, Path[] paths) throws IOException {
		// TODO create a branch, push to remote, create PR, re-checkout master

		final ContentManager cm = this.content; // remember current content, in case it swaps out mid-process
		final Indexer idx = new Indexer(cm);
		final Set<IndexResult<? extends Content>> indexResults = new HashSet<>();
		// TODO index path with content manager
		idx.index(false, null, new Indexer.IndexerEvents() {
			@Override
			public void starting(int foundFiles) {
				logger.info("[{}] Start indexing paths {}", jobId, Arrays.toString(paths));
			}

			@Override
			public void progress(int indexed, int total, Path currentFile) {
				logger.info("[{}] Indexed {} of {}", jobId, indexed, total);
			}

			@Override
			public void indexed(Submission submission, Optional<IndexResult<? extends Content>> indexed, IndexLog log) {
				indexed.ifPresent(indexResults::add);
			}

			@Override
			public void completed(int indexedFiles, int errorCount) {
				logger.info("[{}] Completed indexing {} files with {} errors", jobId, indexedFiles, errorCount);
			}
		}, paths);
		return indexResults;
	}
}
