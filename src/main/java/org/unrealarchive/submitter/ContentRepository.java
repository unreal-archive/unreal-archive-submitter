package org.unrealarchive.submitter;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.unrealarchive.common.ArchiveUtil;
import org.unrealarchive.common.CLI;
import org.unrealarchive.common.Util;
import org.unrealarchive.content.Games;
import org.unrealarchive.content.addons.Addon;
import org.unrealarchive.content.addons.SimpleAddonRepository;
import org.unrealarchive.content.addons.SimpleAddonType;
import org.unrealarchive.indexing.ContentManager;
import org.unrealarchive.indexing.IndexLog;
import org.unrealarchive.indexing.IndexResult;
import org.unrealarchive.indexing.Indexer;
import org.unrealarchive.indexing.Scanner;
import org.unrealarchive.indexing.Submission;
import org.unrealarchive.storage.DataStore;

import static org.unrealarchive.submitter.Submissions.LogType.ERROR;
import static org.unrealarchive.submitter.Submissions.LogType.WARN;

public class ContentRepository implements Closeable {

	private static final String SUBMISSION_URL = "https://unrealarchive.org/submit";

	private static final Logger logger = LoggerFactory.getLogger(ContentRepository.class);

	private static final Duration GIT_POLL_TIME = Duration.ofMinutes(30);
	private static final String[] EMPTY_STRING_ARRAY = {};
	private static final String GIT_DEFUALT_BRANCH = "master";

	private final Path tmpDir;
	private final ScheduledFuture<?> schedule;

	private final String repoUrl;
	private final Git gitRepo;
	private final CredentialsProvider gitCredentials;
	private final PersonIdent gitAuthor;

	private final GHRepository repository;

	private SimpleAddonRepository contentRepo;
	private ContentManager content;

	private volatile boolean contentLock = false;

	public ContentRepository(
		String gitRepoUrl, String gitAuthUsername, String gitAuthPassword, String gitUserEmail, String githubToken,
		ScheduledExecutorService executor)
		throws IOException, GitAPIException {
		this.tmpDir = Files.createTempDirectory("ua-submit-data-");

		logger.info("Cloning git repository {} into {}", gitRepoUrl, tmpDir);

		// on startup, clone git repo
		this.repoUrl = gitRepoUrl;
		this.gitCredentials = new UsernamePasswordCredentialsProvider(gitAuthUsername, gitAuthPassword);
		this.gitAuthor = new PersonIdent(gitAuthUsername, gitUserEmail);
		this.gitRepo = Git.cloneRepository()
						  .setCredentialsProvider(gitCredentials)
						  .setURI(gitRepoUrl)
						  .setBranch(GIT_DEFUALT_BRANCH)
						  .setDirectory(tmpDir.toFile())
						  .setDepth(1)
						  .setCloneAllBranches(false)
						  .setProgressMonitor(new TextProgressMonitor())
						  .call();

		final Pattern repoPattern = Pattern.compile(".*/(.*)/(.*)\\.git");
		Matcher repoNameMatch = repoPattern.matcher(gitRepoUrl);
		if (!repoNameMatch.find()) {
			throw new IllegalArgumentException(String.format("Could not find repo organisation and name in input %s", gitRepoUrl));
		}

		// create github client for pull requests
		GitHub gitHub = new GitHubBuilder().withOAuthToken(githubToken).build();
		this.repository = gitHub.getRepository(String.format("%s/%s", repoNameMatch.group(1), repoNameMatch.group(2)));

		// create a ContentManager
		this.contentRepo = initContentRepo(tmpDir);
		this.content = initContentManager(this.contentRepo);

		// on a schedule, pull repo remote
		this.schedule = executor.scheduleWithFixedDelay(() -> {
			// skip updating the repo if something is busy with it
			if (contentLock) return;

			try {
				// remember current ref
				final ObjectId old = gitRepo.getRepository().findRef("master").getObjectId();

				// pull latest
				gitRepo.pull().call();

				// if it changed, re-create ContentManager
				if (!old.equals(gitRepo.getRepository().findRef("master").getObjectId())) {
					this.contentRepo = initContentRepo(tmpDir);
					this.content = initContentManager(this.contentRepo);
				}
			} catch (IOException | GitAPIException e) {
				e.printStackTrace();
			}
		}, GIT_POLL_TIME.toMillis(), GIT_POLL_TIME.toMillis(), TimeUnit.MILLISECONDS);

		logger.info("Content repo started");
	}

	@Override
	public void close() {
		schedule.cancel(false);
		try {
			logger.info("Cleaning data path {}", tmpDir);
			ArchiveUtil.cleanPath(tmpDir);
		} catch (IOException e) {
			logger.error("Cleanup failed", e);
		}
	}

	private SimpleAddonRepository initContentRepo(Path path) throws IOException {
		return new SimpleAddonRepository.FileRepository(path.resolve("content"));
	}

	private ContentManager initContentManager(SimpleAddonRepository repo) throws IOException {
		return new ContentManager(repo, store(DataStore.StoreContent.CONTENT), store(DataStore.StoreContent.IMAGES));
	}

	private DataStore store(DataStore.StoreContent contentType) {
		String stringType = System.getenv().getOrDefault("STORE_" + contentType.name().toUpperCase(),
														 System.getenv().getOrDefault("STORE", "NOP"));
		DataStore.StoreType storeType = DataStore.StoreType.valueOf(stringType.toUpperCase());
		return storeType.newStore(contentType, new CLI(EMPTY_STRING_ARRAY, Map.of(), Set.of()));
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

		final Scanner sc = new Scanner(this.contentRepo, new CLI(EMPTY_STRING_ARRAY, Map.of(), Set.of()));
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
				String fName = Util.fileName(scanned.filePath());
				if (scanned.failed() != null) {
					job.log(String.format("Error scanning file %s", fName), scanned.failed());
				} else if (scanned.known()) {
					job.log(String.format("No new content found in file %s", fName), WARN);
				} else if (scanned.newType() == SimpleAddonType.UNKNOWN) {
					job.log(String.format("No recognisable content found in file %s", fName), ERROR);
				} else {
					job.log(String.format("Found a %s in file %s", scanned.newType(), fName));
					scanResults.add(scanned);
				}
			}

			@Override
			public void completed(int scannedFiles) {
				logger.info("[{}] Completed scanning {}", job.id, scannedFiles);
			}
		}, paths);

		if (scanResults.isEmpty()) {
			job.log(Submissions.JobState.SCAN_FAILED, "No new content found", ERROR);
		} else {
			job.log(Submissions.JobState.SCANNED, "Scan completed");
		}

		return scanResults;
	}

	public Set<IndexResult<? extends Addon>> submit(Submissions.Job job, Path... paths) throws GitAPIException {
		if (paths == null || paths.length == 0) throw new IllegalArgumentException("No paths to index");

		final String branchName = String.format("%s_%s", Util.slug(paths[0].getFileName().toString()), job.id);

		try {
			// check out a new branch
			job.log(String.format("Checkout content data branch %s", branchName));
			checkout(branchName, true);

			final Set<IndexResult<? extends Addon>> indexResults = new HashSet<>();

			final Indexer idx = new Indexer(this.contentRepo, this.content, new IndexedCollector(job, paths, indexResults));

			try {
				idx.index(false, true, 1, job.forcedType, null, paths);

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
				logger.warn("Content index failed", e);
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

	private void addAndPush(Submissions.Job job, final Set<IndexResult<? extends Addon>> indexResults) throws GitAPIException {
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
													 .map(i -> String.format("[%s %s] %s", Games.byName(i.content.game).shortName,
																			 i.content.contentType, i.content.name)
													 )
													 .collect(Collectors.joining(", "))
						   )
			   )
			   .call();

		job.log("Push content data changes ...");

		gitRepo.push()
			   .setRemote(repoUrl)
			   .setCredentialsProvider(gitCredentials)
			   .call();

		job.log("Content data changes pushed");
	}

	private void createPullRequest(Submissions.Job job, final String branchName, final Set<IndexResult<? extends Addon>> indexResults)
		throws IOException {

		job.log("Creating Pull Request for content data change");

		GHPullRequest pullRequest = repository.createPullRequest(
			branchName, branchName, GIT_DEFUALT_BRANCH,
			String.format("Add content: %n - %s%n%n---%nSubmission log: %s/#%s",
						  indexResults.stream()
									  .map(i -> String.format("[%s %s] %s", Games.byName(i.content.game).shortName, i.content.contentType,
															  i.content.name))
									  .collect(Collectors.joining(String.format("%n - "))),
						  SUBMISSION_URL, job.id
			)
		);

		job.log(String.format("Created Pull Request at %s", pullRequest.getHtmlUrl()));
	}

	private record IndexedCollector(Submissions.Job job, Path[] paths, Set<IndexResult<? extends Addon>> indexResults)
		implements Indexer.IndexerEvents {

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
		public void indexed(Submission submission, Optional<IndexResult<? extends Addon>> indexed, IndexLog log) {
			indexed.ifPresentOrElse(i -> {
										job.log(String.format("Indexed %s: %s by %s", i.content.contentType, i.content.name, i.content.author));
										indexResults.add(i);
									}, () -> {
										job.log(String.format("Failed to index content in file %s", Util.fileName(submission.filePath)));
										logger.warn(log.log.stream().map(l -> l.message).collect(Collectors.joining("; ")));
									}
			);
		}

		@Override
		public void completed(int indexedFiles, int errorCount) {
			job.log("Indexing complete");
			logger.info("[{}] Completed indexing {} files with {} errors", job.id, indexedFiles, errorCount);
		}
	}

}
