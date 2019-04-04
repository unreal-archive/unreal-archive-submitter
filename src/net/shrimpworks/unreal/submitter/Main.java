package net.shrimpworks.unreal.submitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.form.EagerFormParsingHandler;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.server.handlers.form.MultiPartParserDefinition;
import io.undertow.util.Headers;
import org.eclipse.jgit.api.errors.GitAPIException;

import net.shrimpworks.unreal.archive.ArchiveUtil;
import net.shrimpworks.unreal.archive.Util;
import net.shrimpworks.unreal.archive.content.Scanner;

public class Main {

	private static final String HTTP_ROOT = "/upload";

	public static void main(String[] args) throws IOException, GitAPIException {
		final Path tmpDir = Files.createTempDirectory("ua-submit-files-");
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				System.out.printf("Cleaning upload path %s", tmpDir);
				ArchiveUtil.cleanPath(tmpDir);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}));

		final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

		final ContentRepository contentRepo = new ContentRepository(
				"https://github.com/unreal-archive/unreal-archive-data.git",
				System.getenv().getOrDefault("GH_USERNAME", ""),
				System.getenv().getOrDefault("GH_PASSWORD", ""),
				System.getenv().getOrDefault("GH_EMAIL", ""),
				scheduler
		);

		final SubmissionProcessor subProcessor = new SubmissionProcessor(contentRepo, 5, scheduler);

		HttpHandler multipartProcessorHandler = (exchange) -> {
			FormData attachment = exchange.getAttachment(FormDataParser.FORM_DATA);
			FormData.FormValue fileValue = attachment.get("file").getFirst();
			Path file = fileValue.getFileItem().getFile();

			Path movedFile = Files.move(file, tmpDir.resolve(file.getFileName().toString() + "." + Util.extension(fileValue.getFileName())));

			Submissions.Job job = new Submissions.Job();
			job.log(String.format("received file %s", fileValue.getFileName()));

			Set<Scanner.ScanResult> scanResults = contentRepo.scan(job, movedFile);

			exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
			exchange.getResponseSender().send(job.log().toString());
		};

		EagerFormParsingHandler formHandler = new EagerFormParsingHandler(
				FormParserFactory.builder()
								 .addParsers(new MultiPartParserDefinition())
								 .build()
		).setNext(multipartProcessorHandler);

		Undertow server = Undertow.builder()
								  .addHttpListener(8081, "localhost")
								  .setHandler(
										  Handlers.path().addPrefixPath(HTTP_ROOT, formHandler)
								  )
								  .build();
		server.start();
	}
}
