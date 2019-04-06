package net.shrimpworks.unreal.submitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.form.EagerFormParsingHandler;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.server.handlers.form.MultiPartParserDefinition;
import io.undertow.util.Headers;
import org.eclipse.jgit.api.errors.GitAPIException;

import net.shrimpworks.unreal.archive.ArchiveUtil;
import net.shrimpworks.unreal.archive.Util;

import static net.shrimpworks.unreal.submitter.Submissions.Job;

public class Main {

	private static final String HTTP_UPLOAD = "/upload";
	private static final String HTTP_JOB = "/job/{jobId}";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Map<String, Job> jobs = new HashMap<>();

	public static void main(String[] args) throws IOException, GitAPIException {
		final Path tmpDir = Files.createTempDirectory("ua-submit-files-");
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				System.out.printf("Cleaning upload path %s%n", tmpDir);
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

		RoutingHandler routingHandler = new RoutingHandler()
				.post(HTTP_UPLOAD, uploadHandler(subProcessor, tmpDir))
				.get(HTTP_JOB, jobHandler());

		Undertow server = Undertow.builder()
								  .addHttpListener(8081, "localhost")
								  .setHandler(routingHandler)
								  .build();
		server.start();
	}

	private static HttpHandler uploadHandler(SubmissionProcessor subProcessor, Path tmpDir) {
		HttpHandler multipartProcessorHandler = (exchange) -> {
			FormData attachment = exchange.getAttachment(FormDataParser.FORM_DATA);
			FormData.FormValue fileValue = attachment.get("file").getFirst();
			Path file = fileValue.getFileItem().getFile();
			String newName = String.format("%s_%s.%s",
										   Util.plainName(fileValue.getFileName()),
										   Util.hash(file).substring(0, 8),
										   Util.extension(fileValue.getFileName()));

			Path movedFile = Files.move(file, tmpDir.resolve(newName));

			Job job = new Job();
			jobs.put(job.id, job);
			job.log(String.format("Received file %s, queue for processing", newName));
			subProcessor.add(new SubmissionProcessor.PendingSubmission(
					job, LocalDateTime.now(), fileValue.getFileName(), new Path[] { movedFile }
			));

			exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
			exchange.getResponseSender().send(job.id);
		};

		return new EagerFormParsingHandler(
				FormParserFactory.builder()
								 .addParsers(new MultiPartParserDefinition())
								 .build()
		).setNext(multipartProcessorHandler);
	}

	private static HttpHandler jobHandler() {
		final Deque<String> emptyDeque = new ArrayDeque<>();

		return (exchange) -> {
			final String jobId = exchange.getQueryParameters().getOrDefault("jobId", emptyDeque).getFirst();
			final Job job = jobs.get(jobId);

			exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
			exchange.getResponseSender().send(MAPPER.writeValueAsString(
					job == null ? Collections.emptyList() : job.pollLog(Duration.ofSeconds(15))
			));
		};
	}
}
