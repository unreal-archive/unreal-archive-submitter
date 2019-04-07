package net.shrimpworks.unreal.submitter;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.form.EagerFormParsingHandler;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.server.handlers.form.MultiPartParserDefinition;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.util.Headers;

import net.shrimpworks.unreal.archive.ArchiveUtil;
import net.shrimpworks.unreal.archive.Util;

public class WebApp implements Closeable {

	private static final String HTTP_ROOT = "/";
	private static final String HTTP_UPLOAD = "/upload";
	private static final String HTTP_JOB = "/job/{jobId}";

	private static final List<String> ALLOWED_STATIC_TYPES = Arrays.asList("html", "js", "css", "png");

	private static final Path[] PATH_ARRAY = new Path[] {};

	private final ObjectMapper MAPPER = new ObjectMapper();

	private final Map<String, Submissions.Job> jobs = new HashMap<>();

	private final Undertow server;

	public WebApp(InetSocketAddress bindAddress, SubmissionProcessor submissionProcessor) throws IOException {
		final Path tmpDir = Files.createTempDirectory("ua-submit-files-");
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				System.out.printf("Cleaning upload path %s%n", tmpDir);
				ArchiveUtil.cleanPath(tmpDir);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}));

		RoutingHandler handler = new RoutingHandler()
				.setFallbackHandler(staticHandler())
				.post(HTTP_UPLOAD, uploadHandler(submissionProcessor, tmpDir))
				.get(HTTP_JOB, jobHandler());

		this.server = Undertow.builder()
							  .addHttpListener(bindAddress.getPort(), bindAddress.getHostString())
							  .setHandler(handler)
							  .build();
		this.server.start();
	}

	@Override
	public void close() {
		this.server.stop();
	}

	private HttpHandler staticHandler() {
		return Handlers.resource(new ClassPathResourceManager(Main.class.getClassLoader(), Main.class.getPackage()))
					   .addWelcomeFiles("index.html")
					   .setAllowed(x -> x.getRequestPath().equals(HTTP_ROOT)
										|| ALLOWED_STATIC_TYPES.contains(Util.extension(x.getRequestPath())));
	}

	private HttpHandler uploadHandler(SubmissionProcessor subProcessor, Path tmpDir) {
		HttpHandler multipartProcessorHandler = (exchange) -> {
			Submissions.Job job = new Submissions.Job();
			jobs.put(job.id, job);

			FormData attachment = exchange.getAttachment(FormDataParser.FORM_DATA);
			final List<Path> files = attachment.get("files").stream().map(v -> {
				try {
					Path file = v.getFileItem().getFile();
					String newName = String.format("%s_%s.%s",
												   Util.plainName(v.getFileName()),
												   Util.hash(file).substring(0, 8),
												   Util.extension(v.getFileName()));

					return Files.move(file, tmpDir.resolve(newName));
				} catch (IOException e) {
					job.log(Submissions.JobState.FAILED, String.format("Failed moving file %s", v.getFileName()), e);
					return null;
				}
			}).filter(Objects::nonNull).collect(Collectors.toList());

			if (!files.isEmpty()) {
				job.log(String.format("Received file(s): %s, queue for processing",
									  files.stream().map(Util::fileName).collect(Collectors.joining(", "))));

				subProcessor.add(new SubmissionProcessor.PendingSubmission(
						job, LocalDateTime.now(), Util.fileName(files.get(0)), files.toArray(PATH_ARRAY)
				));
			}

			exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
			exchange.getResponseSender().send(MAPPER.writeValueAsString(job.id));
		};

		return new EagerFormParsingHandler(
				FormParserFactory.builder()
								 .addParsers(new MultiPartParserDefinition())
								 .build()
		).setNext(multipartProcessorHandler);
	}

	private HttpHandler jobHandler() {
		final Deque<String> emptyDeque = new ArrayDeque<>();

		return (exchange) -> {
			final String jobId = exchange.getQueryParameters().getOrDefault("jobId", emptyDeque).getFirst();
			final Submissions.Job job = jobs.get(jobId);

			exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
			exchange.getResponseSender().send(MAPPER.writeValueAsString(
					job == null ? Collections.emptyList() : job.pollLog(Duration.ofSeconds(15))
			));
		};
	}

}
