package org.unrealarchive.submitter;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.unrealarchive.common.CLI;
import org.unrealarchive.common.Platform;
import org.unrealarchive.common.Util;
import org.unrealarchive.content.ContentCollection;
import org.unrealarchive.content.RepositoryManager;
import org.unrealarchive.indexing.CollectionsManager;
import org.unrealarchive.storage.DataStore;
import org.unrealarchive.submitter.submit.CollectionSubmissions;

public class CollectionRepository implements Closeable {

	private static final String SUBMISSION_URL = "https://unrealarchive.org/submit";

	private static final Logger logger = LoggerFactory.getLogger(CollectionRepository.class);

	private static final String[] EMPTY_STRING_ARRAY = {};

	private static final Pattern DATA_URI_PATTERN = Pattern.compile("data:image/(?<type>[a-zA-Z]+);base64,(?<data>.+)");

	private final GitManager gitManager;
	private final RepositoryManager repositoryManager;
	private final CollectionsManager collectionsManager;

	public CollectionRepository(GitManager gitManager) {
		this.gitManager = gitManager;
		CLI cli = new CLI(EMPTY_STRING_ARRAY, Map.of("content-path", gitManager.gitRepo().getRepository().getWorkTree().getAbsolutePath()),
						  Set.of());
		this.repositoryManager = new RepositoryManager(cli);
		this.collectionsManager = new CollectionsManager(repositoryManager, repositoryManager.collections(),
														 store(DataStore.StoreContent.CONTENT));
	}

	@Override
	public void close() throws IOException {
		// nothing to close specifically here, gitManager is closed by ContentRepository or Main
	}

	private DataStore store(DataStore.StoreContent contentType) {
		String stringType = System.getenv().getOrDefault("STORE_" + contentType.name().toUpperCase(),
														 System.getenv().getOrDefault("STORE", "NOP"));
		DataStore.StoreType storeType = DataStore.StoreType.valueOf(stringType.toUpperCase());
		return storeType.newStore(contentType, new CLI(EMPTY_STRING_ARRAY, Map.of(), Set.of()));
	}

	public void lock() {
		gitManager.lock();
	}

	public void unlock() {
		gitManager.unlock();
	}

	public void submit(CollectionSubmissions.Job job) throws GitAPIException, IOException {
		final String branchName = String.format("collection_%s_%s", Util.slug(job.submission.title()), job.id);

		try {
			// check out a new branch
			job.log(CollectionSubmissions.JobState.SUBMITTING, String.format("Checkout content data branch %s", branchName));
			gitManager.checkout(branchName, true);

			// 1. Map DTO to Entity
			ContentCollection collection = new ContentCollection();
			collection.title = job.submission.title();
			collection.description = job.submission.description();
			collection.author = job.submission.author();
			collection.links = job.submission.links();
			collection.createdDate = LocalDate.parse(job.submission.createdDate());
			collection.items = job.submission.items().stream()
											 .map(i -> {
												 ContentCollection.CollectionItem item = new ContentCollection.CollectionItem();
												 item.id = i.reference();
												 item.title = i.title();
												 return item;
											 })
											 .collect(Collectors.toList());

			processImage(job, collection);

			// 2. Initial checkin
			job.log(CollectionSubmissions.JobState.CHECKING_IN, "Initial collection checkin");
			collectionsManager.checkin(collection);
			job.log(CollectionSubmissions.JobState.CHECKED_IN, "Collection checked in");

			// 3. Create archive
			job.log(CollectionSubmissions.JobState.ARCHIVING, "Creating collection archive");
			collectionsManager.createArchive(collection, Platform.ANY);
			job.log(CollectionSubmissions.JobState.ARCHIVED, "Collection archive created");

			// 4. Sync
			job.log(CollectionSubmissions.JobState.SYNCING, "Syncing collection archive");
			collectionsManager.sync(collection);
			job.log(CollectionSubmissions.JobState.SYNCED, "Collection archive synced");

			// 5. Git add, commit, push
			job.log(CollectionSubmissions.JobState.SUBMITTING, "Submitting changes and opening pull request");
			gitManager.addAndPush(job.id, job::log, "collections", String.format("Add collection %s", collection.title));

			// 6. PR
			createPullRequest(job, branchName, collection);

			job.log(CollectionSubmissions.JobState.COMPLETED, "Submission completed");
		} catch (Exception e) {
			job.log(CollectionSubmissions.JobState.SUBMIT_FAILED, String.format("Submission failed: %s", e.getMessage()), e);
			logger.error("Collection submission failed", e);
		} finally {
			// go back to master branch
			gitManager.checkout(GitManager.GIT_DEFAULT_BRANCH, false);
		}
	}

	private void createPullRequest(CollectionSubmissions.Job job, String branchName, ContentCollection collection) throws IOException {
		long start = job.log.getFirst().time;

		String body = String.format("Add collection: %n - %s by %s%n%n---%nJob log:%n```%n%s%n```%n%n---%nSubmission log: %s/#%s",
									collection.title,
									collection.author,
									job.log().stream()
									   .map(l -> String.format("[%s %.2fs] %s", l.type.toString().charAt(0), (l.time - start) / 1000f,
															   l.message))
									   .collect(Collectors.joining("\n")),
									SUBMISSION_URL, job.id
		);

		gitManager.createPullRequest(job.id, job::log, branchName, String.format("Add collection %s", collection.title), body);
	}

	private void processImage(CollectionSubmissions.Job job, ContentCollection collection) throws IOException {
		String image = job.submission.image();
		if (image == null || image.isBlank() || !image.startsWith("data:image/")) return;

		Matcher matcher = DATA_URI_PATTERN.matcher(image);
		if (matcher.find()) {
			String type = matcher.group("type");
			String data = matcher.group("data");

			String extension;
			switch (type.toLowerCase()) {
				case "jpeg" -> extension = "jpg";
				case "png" -> extension = "png";
				case "webp" -> extension = "webp";
				case "gif" -> extension = "gif";
				default -> {
					logger.warn("Unsupported image type: {}", type);
					return;
				}
			}

			byte[] bytes = Base64.getDecoder().decode(data.trim());
			Path tempFile = Files.createTempFile("ua-image", "." + extension);
			try {
				Files.write(tempFile, bytes);
				collectionsManager.putFile(collection, tempFile);
				collection.titleImage = tempFile.getFileName().toString();
			} finally {
				Files.deleteIfExists(tempFile);
			}
		}
	}

}
