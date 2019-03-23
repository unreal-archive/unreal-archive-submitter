package net.shrimpworks.unreal.submitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import net.shrimpworks.unreal.archive.ArchiveUtil;
import net.shrimpworks.unreal.archive.CLI;
import net.shrimpworks.unreal.archive.Util;
import net.shrimpworks.unreal.archive.content.Content;
import net.shrimpworks.unreal.archive.content.ContentManager;
import net.shrimpworks.unreal.archive.content.IndexLog;
import net.shrimpworks.unreal.archive.content.IndexResult;
import net.shrimpworks.unreal.archive.content.Indexer;
import net.shrimpworks.unreal.archive.content.Scanner;
import net.shrimpworks.unreal.archive.content.Submission;
import net.shrimpworks.unreal.archive.storage.DataStore;

import static net.shrimpworks.unreal.archive.content.Scanner.ScanResult;
import static net.shrimpworks.unreal.archive.content.Scanner.ScannerEvents;

public class Main {

	private static final String GH_USERNAME = System.getenv().getOrDefault("GH_USERNAME", "anonymous");
	private static final String GH_PASSWORD = System.getenv().getOrDefault("GH_PASSWORD", "");
	private static final String GH_EMAIL = System.getenv().getOrDefault("GH_EMAIL", "anon@localhost");

	public static void main(String[] args) throws IOException, GitAPIException {
		final Path tmpDir = Files.createTempDirectory("ua-submit-");

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				System.out.printf("Cleaning working path %s", tmpDir);
				ArchiveUtil.cleanPath(tmpDir);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}));

		final Path[] input = new Path[] { Paths.get("/home/shrimp/tmp/MutSelfDamage.rar") };

		final String branchName = Util.fileName(input[0]);

		final PersonIdent author = new PersonIdent(GH_USERNAME, GH_EMAIL);
		final CredentialsProvider credentials = new UsernamePasswordCredentialsProvider(GH_USERNAME, GH_PASSWORD);
		final Git repo = Git.cloneRepository()
							.setCredentialsProvider(credentials)
							.setURI("https://github.com/unreal-archive/unreal-archive-data.git")
							.setDirectory(tmpDir.toFile())
							.call();

		System.out.println("Cloned to " + tmpDir);

		final ContentManager content = new ContentManager(tmpDir.resolve("content"),
														  new DataStore.NopStore(),
														  new DataStore.NopStore(),
														  new DataStore.NopStore());
		final CLI cli = new CLI(new String[] {}, Collections.emptyMap());

		final Scanner scanner = new Scanner(content, cli);

		final Set<ScanResult> scanned = new HashSet<>();

		scanner.scan(new ScannerEvents() {
			@Override
			public void starting(int fileCount, Pattern match, Pattern exclude) {
				System.out.println("Scanning " + fileCount);
			}

			@Override
			public void progress(int i, int i1, Path path) {
				//
			}

			@Override
			public void scanned(ScanResult scanResult) {
				System.out.println(scanResult);
				scanned.add(scanResult);
			}

			@Override
			public void completed(int i) {
				System.out.println("done!");
			}
		}, input);

		final Set<ScanResult> errors = scanned.stream().filter(r -> r.failed != null).collect(Collectors.toSet());
		final Set<ScanResult> known = scanned.stream().filter(r -> r.known).collect(Collectors.toSet());
		final Set<ScanResult> added = scanned.stream().filter(r -> !r.known).collect(Collectors.toSet());

		System.out.printf("Scanned things: %n==== Failed: %n %s %n==== Known: %n %s %n==== New: %n %s %n", errors, known, added);

		final Indexer indexer = new Indexer(content);
		indexer.index(false, null, new Indexer.IndexerEvents() {
			@Override
			public void starting(int foundFiles) {
				System.out.printf("Indexing %d%n", foundFiles);
			}

			@Override
			public void progress(int indexed, int total, Path currentFile) {

			}

			@Override
			public void indexed(Submission submission, Optional<IndexResult<? extends Content>> indexed, IndexLog log) {
				if (indexed.isPresent()) {
					IndexResult<? extends Content> result = indexed.get();
					System.out.printf("Indexed %s: %s%n", result.content.contentType, result.content.name);
				} else {
					System.out.printf("Nothing indexed in file %s%n", submission.filePath);
				}
			}

			@Override
			public void completed(int indexedFiles, int errorCount) {
				System.out.println("done!");
			}
		}, input);

		final Status untrackedStatus = repo.status().call();
		System.out.printf("New untracked files: %n %s%n", String.join(" \n", untrackedStatus.getUntracked()));
		if (!untrackedStatus.getUntracked().isEmpty()) {
			repo.add().addFilepattern("content").call();
		}

		final Status addedStatus = repo.status().call();
		System.out.printf("New staged files: %n %s%n", String.join(" \n", addedStatus.getAdded()));

		if (!addedStatus.getAdded().isEmpty()) {
			repo.checkout()
				.setName(branchName)
				.setCreateBranch(true)
				.call();

			repo.commit()
				.setCommitter(author)
				.setAuthor(author)
				.setMessage(String.format("Add content %s", Util.fileName(input[0])))
				.call();

			repo.push()
				.setCredentialsProvider(credentials)
				.call();
		}
	}
}
