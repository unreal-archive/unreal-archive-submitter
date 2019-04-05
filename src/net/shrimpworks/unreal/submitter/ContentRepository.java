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
import net.shrimpworks.unreal.archive.Util;
import net.shrimpworks.unreal.archive.content.Content;
import net.shrimpworks.unreal.archive.content.ContentManager;
import net.shrimpworks.unreal.archive.content.ContentType;
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
		final Path tmpDir = Files.createTempDirectory("ua-submit-data-");

		// shutdown hook to cleanup repo
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				System.out.printf("Cleaning data path %s%n", tmpDir);
				ArchiveUtil.cleanPath(tmpDir);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}));

		// on startup, clone git repo
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

		// create a ContentManager
		this.content = initContentManager(tmpDir);

		// on a schedule, pull repo remote
		executor.scheduleWithFixedDelay(() -> {
			// skip updating the repo if something is busy with it
			if (contentLock) return;

			try {
				// remember current ref
				final ObjectId old = gitRepo.getRepository().findRef("master").getObjectId();

				// pull latest
				gitRepo.pull().call();

				// if it changed, re-create ContentManager
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

	public Set<Scanner.ScanResult> scan(Submissions.Job job, Path... paths) throws IOException {
		if (paths == null || paths.length == 0) throw new IllegalArgumentException("No paths to index");

		final ContentManager cm = this.content; // remember current content, in case it swaps out mid-process
		final Scanner sc = new Scanner(cm, new CLI(EMPTY_STRING_ARRAY, Collections.emptyMap()));
		final Set<Scanner.ScanResult> scanResults = new HashSet<>();
		// scan path with content manager
		sc.scan(new Scanner.ScannerEvents() {
			@Override
			public void starting(int foundFiles, Pattern included, Pattern excluded) {
				job.log(Submissions.JobState.SCANNING, "Begin scanning content");
				logger.info("[{}] Start scanning paths {}", job.id, Arrays.toString(paths));
			}

			@Override
			public void progress(int scanned, int total, Path currentFile) {
				logger.info("[{}] Scanned {} of {}", job.id, scanned, total);
			}

			@Override
			public void scanned(Scanner.ScanResult scanned) {
				job.log(String.format("Found %s", scanned.newType), scanned.failed);
				scanResults.add(scanned);
			}

			@Override
			public void completed(int scannedFiles) {
				logger.info("[{}] Completed scanning {}", job.id, scannedFiles);
			}
		}, paths);

		boolean alreadyKnown = scanResults.stream().allMatch(s -> s.known);
		boolean unknown = scanResults.stream().allMatch(s -> s.newType == ContentType.UNKNOWN);
		boolean failed = scanResults.stream().allMatch(s -> s.failed != null);

		if (alreadyKnown) job.log(Submissions.JobState.KNOWN_CONTENT, "Content already known");
		else if (unknown) job.log(Submissions.JobState.UNKNOWN_CONTENT, "No recognisable content type found");
		else if (failed) job.log(Submissions.JobState.SCAN_FAILED, "Scan failed");
		else job.log(Submissions.JobState.SCANNED, "Scan completed");

		return scanResults;
	}

	public Set<IndexResult<? extends Content>> submit(Submissions.Job job, Path... paths) throws GitAPIException {
		if (paths == null || paths.length == 0) throw new IllegalArgumentException("No paths to index");

		final String branchName = paths[0].getFileName().toString();

		try {
			// check out a new branch
			job.log(String.format("Checkout content data branch %s", branchName));
			checkout(branchName, true);

			final ContentManager cm = this.content; // remember current content, in case it swaps out mid-process
			final Indexer idx = new Indexer(cm);
			final Set<IndexResult<? extends Content>> indexResults = new HashSet<>();

			try {
				idx.index(false, null, new Indexer.IndexerEvents() {
					@Override
					public void starting(int foundFiles) {
						job.log(Submissions.JobState.INDEXING, "Begin indexing content");
						logger.info("[{}] Start indexing paths {}", job.id, Arrays.toString(paths));
					}

					@Override
					public void progress(int indexed, int total, Path currentFile) {
						logger.info("[{}] Indexed {} of {}", job.id, indexed, total);
					}

					@Override
					public void indexed(Submission submission, Optional<IndexResult<? extends Content>> indexed, IndexLog log) {
						indexed.ifPresentOrElse(i -> {
													job.log(String.format("Indexed %s: %s by %s", i.content.contentType, i.content.name, i.content.author));
													indexResults.add(i);
												}, () -> job.log(String.format("Failed to index content in file %s: %s",
																			   Util.fileName(submission.filePath),
																			   log.log.stream().map(l -> l.message).collect(Collectors.joining("; "))))
						);
					}

					@Override
					public void completed(int indexedFiles, int errorCount) {
						job.log("Indexing complete");
						logger.info("[{}] Completed indexing {} files with {} errors", job.id, indexedFiles, errorCount);
					}
				}, paths);

				if (!indexResults.isEmpty()) {
					job.log(Submissions.JobState.SUBMITTING, "Submitting content and opening pull request");
					try {
						addAndPush(job, indexResults);
						createPullRequest(job, branchName, indexResults);
						job.log(Submissions.JobState.SUBMITTED, "Submission completed");
					} catch (Exception e) {
						job.log(Submissions.JobState.SUBMIT_FAILED, String.format("Submission failed: %s", e.getMessage()), e);
					}
				}
			} catch (Exception e) {
				job.log(Submissions.JobState.INDEX_FAILED, String.format("Content indexing failed: %s", e.getMessage()), e);
			}

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

	private void addAndPush(Submissions.Job job, final Set<IndexResult<? extends Content>> indexResults) throws GitAPIException {
		final Status untrackedStatus = gitRepo.status().call();
		if (!untrackedStatus.getUntracked().isEmpty()) {
			logger.info("[{}] Adding untracked files: {}", job.id, String.join(", ", untrackedStatus.getUntracked()));
			gitRepo.add().addFilepattern("content").call();
		} else {
			throw new IllegalStateException("There are no new files to add");
		}

		job.log("Commit changes to content data");

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

		job.log("Push content data changes ...");

		gitRepo.push()
			   .setRemote(GIT_REPO_URL)
			   .setCredentialsProvider(gitCredentials)
			   .call();

		job.log("Content data changes pushed");
	}

	private void createPullRequest(Submissions.Job job, final String branchName, final Set<IndexResult<? extends Content>> indexResults)
			throws IOException {

		job.log("Creating Pull Request for content data change");

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
		PullRequest pullRequest = prService.createPullRequest(gitHubRepo, pr);

		job.log(String.format("Created Pull Request at %s", pullRequest.getUrl()));
	}

}
