package org.unrealarchive.submitter;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

public class GitManager implements Closeable {

	private static final Logger logger = LoggerFactory.getLogger(GitManager.class);

	public static final String GIT_DEFAULT_BRANCH = "master";

	private final String repoUrl;
	private final Git gitRepo;
	private final CredentialsProvider gitCredentials;
	private final PersonIdent gitAuthor;
	private final GHRepository repository;
	private final ReentrantLock lock = new ReentrantLock();
	private final Path cloneDir;

	public GitManager(
		String gitRepoUrl, String gitAuthUsername, String gitAuthPassword, String gitUserEmail, String githubToken, Path cloneDir)
		throws IOException, GitAPIException {

		this.cloneDir = cloneDir;

		logger.info("Cloning git repository {} into {}", gitRepoUrl, this.cloneDir);

		this.repoUrl = gitRepoUrl;
		this.gitCredentials = new UsernamePasswordCredentialsProvider(gitAuthUsername, gitAuthPassword);
		this.gitAuthor = new PersonIdent(gitAuthUsername, gitUserEmail);
		this.gitRepo = Git.cloneRepository()
						  .setCredentialsProvider(gitCredentials)
						  .setURI(gitRepoUrl)
						  .setBranch(GIT_DEFAULT_BRANCH)
						  .setDirectory(this.cloneDir.toFile())
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
	}

	@Override
	public void close() {
		try {
			logger.info("Cleaning data path {}", cloneDir);
			ArchiveUtil.cleanPath(cloneDir);
		} catch (IOException e) {
			logger.error("Cleanup failed", e);
		}
	}

	public void lock() {
		lock.lock();
	}

	public void unlock() {
		lock.unlock();
	}

	public Git gitRepo() {
		return gitRepo;
	}

	public void checkout(String branchName, boolean createBranch) throws GitAPIException {
		gitRepo.checkout()
			   .setName(branchName)
			   .setCreateBranch(createBranch)
			   .call();
	}

	public boolean update() throws IOException, GitAPIException {
		// remember current ref
		final ObjectId old = gitRepo.getRepository().findRef(GIT_DEFAULT_BRANCH).getObjectId();

		// pull latest
		gitRepo.pull().call();

		// return true if it changed
		return !old.equals(gitRepo.getRepository().findRef(GIT_DEFAULT_BRANCH).getObjectId());
	}

	public void addAndPush(String jobId, Consumer<String> log, String filePattern, String commitMessage) throws GitAPIException {
		final Status untrackedStatus = gitRepo.status().call();
		if (!untrackedStatus.getUntracked().isEmpty() || !untrackedStatus.getModified().isEmpty()) {
			logger.info("[{}] Adding files: {}", jobId, String.join(", ", untrackedStatus.getUntracked()));
			gitRepo.add().addFilepattern(filePattern).call();
		} else {
			throw new IllegalStateException("There are no new files to add");
		}

		log.accept("Commit changes to content data");

		gitRepo.commit()
			   .setCommitter(gitAuthor)
			   .setAuthor(gitAuthor)
			   .setMessage(commitMessage)
			   .call();

		log.accept("Push content data changes ...");

		gitRepo.push()
			   .setRemote(repoUrl)
			   .setCredentialsProvider(gitCredentials)
			   .call();

		log.accept("Content data changes pushed");
	}

	public void createPullRequest(Consumer<String> log, String branchName, String title, String body, String... labels) throws IOException {
		log.accept("Creating Pull Request for content data change");

		GHPullRequest pullRequest = repository.createPullRequest(
			title, branchName, GIT_DEFAULT_BRANCH, body
		);

		pullRequest.setLabels(labels);

		log.accept(String.format("Created Pull Request at %s", pullRequest.getHtmlUrl()));
	}
}
