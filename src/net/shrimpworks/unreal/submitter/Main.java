package net.shrimpworks.unreal.submitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import net.shrimpworks.unreal.archive.CLI;
import net.shrimpworks.unreal.archive.content.ContentManager;
import net.shrimpworks.unreal.archive.content.Scanner;
import net.shrimpworks.unreal.archive.storage.DataStore;

public class Main {

	public static void main(String[] args) throws IOException, GitAPIException {
		Path tmpDir = Files.createTempDirectory("ua-submit-");

		Git repo = Git.cloneRepository()
					  .setURI("https://github.com/unreal-archive/unreal-archive-data.git")
					  .setDirectory(tmpDir.toFile())
					  .call();

		System.out.println("Cloned to " + tmpDir);

		Scanner scanner = new Scanner(new ContentManager(tmpDir.resolve("content"),
														 new DataStore.NopStore(),
														 new DataStore.NopStore(),
														 new DataStore.NopStore()
		), new CLI(new String[]{}, Collections.emptyMap()));

		scanner.scan(new Scanner.ScannerEvents() {
			@Override
			public void starting(int fileCount, Pattern match, Pattern exclude) {
				System.out.println("Scanning " + fileCount);
			}

			@Override
			public void progress(int i, int i1, Path path) {
				//
			}

			@Override
			public void scanned(Scanner.ScanResult scanResult) {
				System.out.println(scanResult);
			}

			@Override
			public void completed(int i) {
				System.out.println("done!");
			}
		}, Paths.get("/home/shrimp/tmp/MutSelfDamage.rar"));
	}
}
