package org.unrealarchive.submitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.mockito.ArgumentCaptor;

import org.unrealarchive.content.ContentCollection;
import org.unrealarchive.indexing.CollectionsManager;
import org.unrealarchive.submitter.submit.CollectionSubmission;
import org.unrealarchive.submitter.submit.CollectionSubmissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class CollectionRepositoryTest {

	@TempDir
	Path tempDir;

	@Test
	public void testSubmitWithImage() throws Exception {
		Path gitDir = tempDir.resolve("git");
		Files.createDirectories(gitDir);
		Files.createDirectories(gitDir.resolve("collections"));

		GitManager gitManager = mock(GitManager.class);
		Git git = mock(Git.class);
		Repository repo = mock(Repository.class);
		when(gitManager.gitRepo()).thenReturn(git);
		when(git.getRepository()).thenReturn(repo);
		when(repo.getWorkTree()).thenReturn(gitDir.toFile());

		CollectionRepository collectionRepository = new CollectionRepository(gitManager);

		// Mock CollectionsManager because it's hard to setup real one with all dependencies
		CollectionsManager mockCollectionsManager = mock(CollectionsManager.class);
		java.lang.reflect.Field field = CollectionRepository.class.getDeclaredField("collectionsManager");
		field.setAccessible(true);
		field.set(collectionRepository, mockCollectionsManager);

		// Base64 for a 1x1 transparent PNG
		String base64Image = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==";

		CollectionSubmission submission = new CollectionSubmission(
			"Test Collection", "Description", "Author", Map.of(),
			base64Image,
			"2026-01-03", List.of()
		);
		CollectionSubmissions.Job job = new CollectionSubmissions.Job(submission);

		collectionRepository.submit(job);

		// Verify putFile was called
		verify(mockCollectionsManager).putFile(any(ContentCollection.class), any(Path.class));

		// Verify titleImage was updated
		ArgumentCaptor<ContentCollection> collectionCaptor = ArgumentCaptor.forClass(ContentCollection.class);
		verify(mockCollectionsManager, atLeastOnce()).checkin(collectionCaptor.capture());
		ContentCollection captured = collectionCaptor.getValue();
		assertNotNull(captured.titleImage);
		assertTrue(captured.titleImage.endsWith(".png"), "Extension should be .png but was " + captured.titleImage);
		assertFalse(captured.titleImage.startsWith("data:image/"));
	}

	@Test
	public void testSubmitWithJpegImage() throws Exception {
		Path gitDir = tempDir.resolve("git");
		Files.createDirectories(gitDir);
		Files.createDirectories(gitDir.resolve("collections"));

		GitManager gitManager = mock(GitManager.class);
		Git git = mock(Git.class);
		Repository repo = mock(Repository.class);
		when(gitManager.gitRepo()).thenReturn(git);
		when(git.getRepository()).thenReturn(repo);
		when(repo.getWorkTree()).thenReturn(gitDir.toFile());

		CollectionRepository collectionRepository = new CollectionRepository(gitManager);

		CollectionsManager mockCollectionsManager = mock(CollectionsManager.class);
		java.lang.reflect.Field field = CollectionRepository.class.getDeclaredField("collectionsManager");
		field.setAccessible(true);
		field.set(collectionRepository, mockCollectionsManager);

		// Base64 for a tiny JPEG
		String base64Image = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=";

		CollectionSubmission submission = new CollectionSubmission(
			"Test Jpeg", "Description", "Author", Map.of(),
			base64Image,
			"2026-01-03", List.of()
		);
		CollectionSubmissions.Job job = new CollectionSubmissions.Job(submission);

		collectionRepository.submit(job);

		ArgumentCaptor<ContentCollection> collectionCaptor = ArgumentCaptor.forClass(ContentCollection.class);
		verify(mockCollectionsManager, atLeastOnce()).checkin(collectionCaptor.capture());
		ContentCollection captured = collectionCaptor.getValue();
		assertNotNull(captured.titleImage);
		assertTrue(captured.titleImage.endsWith(".jpg"), "Extension should be .jpg but was " + captured.titleImage);
	}

	@Test
	public void testSubmitWithWebpImage() throws Exception {
		Path gitDir = tempDir.resolve("git");
		Files.createDirectories(gitDir);
		Files.createDirectories(gitDir.resolve("collections"));

		GitManager gitManager = mock(GitManager.class);
		Git git = mock(Git.class);
		Repository repo = mock(Repository.class);
		when(gitManager.gitRepo()).thenReturn(git);
		when(git.getRepository()).thenReturn(repo);
		when(repo.getWorkTree()).thenReturn(gitDir.toFile());

		CollectionRepository collectionRepository = new CollectionRepository(gitManager);

		CollectionsManager mockCollectionsManager = mock(CollectionsManager.class);
		java.lang.reflect.Field field = CollectionRepository.class.getDeclaredField("collectionsManager");
		field.setAccessible(true);
		field.set(collectionRepository, mockCollectionsManager);

		// Base64 for a tiny WEBP
		String base64Image = "data:image/webp;base64,UklGRhoAAABXRUJQVlA4TAYAAAAvAAAAAAfQ//73vQ== ";

		CollectionSubmission submission = new CollectionSubmission(
			"Test Webp", "Description", "Author", Map.of(),
			base64Image,
			"2026-01-03", List.of()
		);
		CollectionSubmissions.Job job = new CollectionSubmissions.Job(submission);

		collectionRepository.submit(job);

		ArgumentCaptor<ContentCollection> collectionCaptor = ArgumentCaptor.forClass(ContentCollection.class);
		verify(mockCollectionsManager, atLeastOnce()).checkin(collectionCaptor.capture());
		ContentCollection captured = collectionCaptor.getValue();
		assertNotNull(captured.titleImage);
		assertTrue(captured.titleImage.endsWith(".webp"), "Extension should be .webp but was " + captured.titleImage);
	}

	@Test
	public void testSubmitWithGifImage() throws Exception {
		Path gitDir = tempDir.resolve("git");
		Files.createDirectories(gitDir);
		Files.createDirectories(gitDir.resolve("collections"));

		GitManager gitManager = mock(GitManager.class);
		Git git = mock(Git.class);
		Repository repo = mock(Repository.class);
		when(gitManager.gitRepo()).thenReturn(git);
		when(git.getRepository()).thenReturn(repo);
		when(repo.getWorkTree()).thenReturn(gitDir.toFile());

		CollectionRepository collectionRepository = new CollectionRepository(gitManager);

		CollectionsManager mockCollectionsManager = mock(CollectionsManager.class);
		java.lang.reflect.Field field = CollectionRepository.class.getDeclaredField("collectionsManager");
		field.setAccessible(true);
		field.set(collectionRepository, mockCollectionsManager);

		// Base64 for a 1x1 GIF
		String base64Image = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";

		CollectionSubmission submission = new CollectionSubmission(
			"Test Gif", "Description", "Author", Map.of(),
			base64Image,
			"2026-01-03", List.of()
		);
		CollectionSubmissions.Job job = new CollectionSubmissions.Job(submission);

		collectionRepository.submit(job);

		ArgumentCaptor<ContentCollection> collectionCaptor = ArgumentCaptor.forClass(ContentCollection.class);
		verify(mockCollectionsManager, atLeastOnce()).checkin(collectionCaptor.capture());
		ContentCollection captured = collectionCaptor.getValue();
		assertNotNull(captured.titleImage);
		assertTrue(captured.titleImage.endsWith(".gif"), "Extension should be .gif but was " + captured.titleImage);
	}
}
