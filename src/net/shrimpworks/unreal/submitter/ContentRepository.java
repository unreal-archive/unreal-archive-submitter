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
import java.util.stream.Collectors;

import org.eclipse.egit.github.core.PullRequest;
import org.eclipse.egit.github.core.PullRequestMarker;
import org.eclipse.egit.github.core.Repository;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.service.PullRequestService;
import org.eclipse.egit.github.core.service.RepositoryService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
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
	private static final String GIT_DEFUALT_BRANCH = "master";

	static final String GIT_ORG = "unreal-archive";
	static final String GIT_REPO = "unreal-archive-data";

	static final String GIT_REPO_URL = String.format("https://github.com/%s/%s.git", GIT_ORG, GIT_REPO);
	static final String GIT_CLONE_URL = System.getenv().getOrDefault("GIT_CLONE_URL", GIT_REPO_URL);

	private final Git gitRepo;
	private final CredentialsProvider gitCredentials;
	private final PersonIdent gitAuthor;

	private final GitHubClient gitHubClient;
	private final Repository gitHubRepo;

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
		this.gitCredentials = new UsernamePasswordCredentialsProvider(authUsername, authPassword);
		this.gitAuthor = new PersonIdent(authUsername, email);
		this.gitRepo = Git.cloneRepository()
						  .setCredentialsProvider(gitCredentials)
						  .setURI(GIT_CLONE_URL)
						  .setBranch(GIT_DEFUALT_BRANCH)
						  .setDirectory(tmpDir.toFile())
						  .call();

		// create github client for pull requests
		this.gitHubClient = new GitHubClient().setCredentials(authUsername, authPassword);
		final RepositoryService gitHubRepoService = new RepositoryService(gitHubClient);
		this.gitHubRepo = gitHubRepoService.getRepository(GIT_ORG, GIT_REPO);

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
		if (paths == null || paths.length == 0) throw new IllegalArgumentException("No paths to index");

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

	public Set<IndexResult<? extends Content>> submit(String jobId, Path[] paths) throws IOException, GitAPIException {
		// TODO create a branch, push to remote, create PR, re-checkout master

		if (paths == null || paths.length == 0) throw new IllegalArgumentException("No paths to index");

		final String branchName = paths[0].getFileName().toString();

		try {
			// check out a new branch
			checkout(branchName, true);

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

			addAndPush(jobId, indexResults);
			createPullRquest(jobId, branchName, indexResults);

			return indexResults;
		} finally {
			// go back to master branch
			checkout(GIT_DEFUALT_BRANCH, false);
		}
	}

	private void checkout(String branchName, boolean createBranch) throws GitAPIException {
		gitRepo.checkout()
			   .setName(branchName)
			   .setCreateBranch(createBranch)
			   .call();
	}

	private void addAndPush(final String jobId, final Set<IndexResult<? extends Content>> indexResults) throws GitAPIException {
		final Status untrackedStatus = gitRepo.status().call();
		if (!untrackedStatus.getUntracked().isEmpty()) {
			logger.info("[{}] Adding untracked files: {}", jobId, String.join(", ", untrackedStatus.getUntracked()));
			gitRepo.add().addFilepattern("content").call();
		} else {
			throw new IllegalStateException("There are no new files to add");
		}

		gitRepo.commit()
			   .setCommitter(gitAuthor)
			   .setAuthor(gitAuthor)
			   .setMessage(String.format("Add content %s",
										 indexResults.stream()
													 .map(i -> String.format("[%s] %s", i.content.contentType, i.content.name))
													 .collect(Collectors.joining(", "))
						   )
			   )
			   .call();

		gitRepo.push()
			   .setRemote(GIT_REPO_URL)
			   .setCredentialsProvider(gitCredentials)
			   .call();
	}

	private void createPullRquest(final String jobId, final String branchName, final Set<IndexResult<? extends Content>> indexResults)
			throws IOException {
		PullRequestService prService = new PullRequestService(gitHubClient);
		PullRequest pr = new PullRequest();
		pr.setBase(new PullRequestMarker().setRepo(gitHubRepo).setLabel(GIT_DEFUALT_BRANCH));
		pr.setHead(new PullRequestMarker().setRepo(gitHubRepo).setLabel(branchName));
		pr.setTitle(branchName);
		pr.setBody(String.format("Add content: %n - %s",
								 indexResults.stream()
											 .map(i -> String.format("[%s] %s", i.content.contentType, i.content.name))
											 .collect(Collectors.joining("%n - "))
		));
		prService.createPullRequest(gitHubRepo, pr);
	}

}
