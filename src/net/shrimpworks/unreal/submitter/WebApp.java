package net.shrimpworks.unreal.submitter;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timgroup.statsd.StatsDClient;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.form.EagerFormParsingHandler;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.server.handlers.form.MultiPartParserDefinition;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.Options;

import net.shrimpworks.unreal.archive.ArchiveUtil;
import net.shrimpworks.unreal.archive.Util;

public class WebApp implements Closeable {

	private static final Logger logger = LoggerFactory.getLogger(WebApp.class);

	private static final int WORKER_IO_THREADS = 2;
	private static final int WORKER_TASK_CORE_THREADS = 10;

	private static final String HTTP_UPLOAD = "/upload";
	private static final String HTTP_JOB = "/job/{jobId}";
	private static final Path[] PATH_ARRAY = {};

	private final ObjectMapper MAPPER = new ObjectMapper();

	private final Path tmpDir;

	private final Undertow server;
	private final String allowOrigins;
	private final StatsDClient statsD;

	public WebApp(InetSocketAddress bindAddress, SubmissionProcessor submissionProcessor, String allowOrigins, StatsDClient statsD)
			throws IOException {
		this.statsD = statsD;
		this.tmpDir = Files.createTempDirectory("ua-submit-files-");

		this.allowOrigins = allowOrigins;
		RoutingHandler handler = Handlers.routing()
										 .add("OPTIONS", HTTP_UPLOAD, corsOptionsHandler("POST, OPTIONS"))
										 .add("POST", HTTP_UPLOAD, uploadHandler(submissionProcessor, tmpDir))
										 .add("OPTIONS", HTTP_JOB, corsOptionsHandler("GET, OPTIONS"))
										 .add("GET", HTTP_JOB, jobHandler(submissionProcessor));

		this.server = Undertow.builder()
							  .setWorkerOption(Options.WORKER_IO_THREADS, WORKER_IO_THREADS)
							  .setWorkerOption(Options.WORKER_TASK_CORE_THREADS, WORKER_TASK_CORE_THREADS)
							  .setWorkerOption(Options.WORKER_TASK_MAX_THREADS, WORKER_TASK_CORE_THREADS)
							  .setWorkerOption(Options.TCP_NODELAY, true)
							  .setSocketOption(Options.WORKER_IO_THREADS, WORKER_IO_THREADS)
							  .setSocketOption(Options.TCP_NODELAY, true)
							  .setSocketOption(Options.REUSE_ADDRESSES, true)
							  .addHttpListener(bindAddress.getPort(), bindAddress.getHostString())
							  .setHandler(handler)
							  .build();
		this.server.start();

		logger.info("Server started on host {}", bindAddress);
	}

	@Override
	public void close() {
		this.server.stop();
		try {
			logger.info("Cleaning upload path {}", tmpDir);
			ArchiveUtil.cleanPath(tmpDir);
		} catch (IOException e) {
			logger.error("Cleanup failed", e);
		}
	}

	private HttpHandler corsOptionsHandler(String methods) {
		return (exchange) -> {
			exchange.getResponseHeaders()
					.put(new HttpString("Access-Control-Allow-Origin"), allowOrigins)
					.put(new HttpString("Access-Control-Allow-Methods"), methods);
			exchange.getResponseSender().close();
		};
	}

	private HttpHandler uploadHandler(SubmissionProcessor subProcessor, Path tmpDir) {
		HttpHandler multipartProcessorHandler = (exchange) -> {
			statsD.count("www.upload", 1);
			final long start = System.currentTimeMillis();
			exchange.dispatch(() -> {
				try {
					Submissions.Job job = new Submissions.Job();
					subProcessor.trackJob(job);

					FormData attachment = exchange.getAttachment(FormDataParser.FORM_DATA);
					final List<Path> files = attachment.get("files").stream().map(v -> {
						try {
							Path file = v.getFileItem().getFile();
							String newName = String.format("%s_%s.%s",
														   Util.plainName(v.getFileName()),
														   Util.hash(file).substring(0, 8),
														   Util.extension(v.getFileName()));

							return Files.move(file, tmpDir.resolve(newName), StandardCopyOption.REPLACE_EXISTING);
						} catch (IOException e) {
							job.log(Submissions.JobState.FAILED, String.format("Failed moving file %s", v.getFileName()), e);
							statsD.count("www.upload.fileFail", 1);
							logger.error("File move failed", e);
							return null;
						}
					}).filter(Objects::nonNull).collect(Collectors.toList());

					if (!files.isEmpty()) {
						job.log(String.format("Received file(s): %s, queue for processing",
											  files.stream().map(Util::fileName).collect(Collectors.joining(", "))));

						subProcessor.add(new SubmissionProcessor.PendingSubmission(
								job, System.currentTimeMillis(), Util.fileName(files.get(0)), files.toArray(PATH_ARRAY)
						));

						statsD.count("www.upload.fileAdd", files.size());
					}

					exchange.getResponseHeaders()
							.put(Headers.CONTENT_TYPE, "application/json")
							.put(new HttpString("Access-Control-Allow-Origin"), allowOrigins)
							.put(new HttpString("Access-Control-Allow-Methods"), "POST");
					exchange.getResponseSender().send(MAPPER.writeValueAsString(job.id));

					statsD.count("www.upload.ok", 1);
				} catch (IOException e) {
					throw new RuntimeException(e);
				} finally {
					statsD.time("www.upload", System.currentTimeMillis() - start);
					exchange.endExchange();
				}
			});
		};

		return new EagerFormParsingHandler(
				FormParserFactory.builder()
								 .addParsers(new MultiPartParserDefinition())
								 .build()
		).setNext(multipartProcessorHandler);
	}

	private HttpHandler jobHandler(SubmissionProcessor submissionProcessor) {
		final Deque<String> emptyDeque = new ArrayDeque<>();

		return (exchange) -> {
			statsD.count("www.job", 1);
			final String jobId = exchange.getQueryParameters().getOrDefault("jobId", emptyDeque).getFirst();
			final Submissions.Job job = submissionProcessor.job(jobId);

			exchange.getResponseHeaders()
					.put(Headers.CONTENT_TYPE, "application/json")
					.put(new HttpString("Access-Control-Allow-Origin"), allowOrigins)
					.put(new HttpString("Access-Control-Allow-Methods"), "POST, GET");

			exchange.dispatch(() -> {
				try {
					if (job == null) {
						exchange.setStatusCode(404);
						exchange.getResponseSender().send("[]");
						return;
					}

					final Deque<String> catchup = exchange.getQueryParameters().getOrDefault("catchup", emptyDeque);
					if (!catchup.isEmpty() && catchup.getFirst().equals("1")) {
						exchange.getResponseSender().send(MAPPER.writeValueAsString(job.log));
					} else {
//						if (!job.state.done()) {
							exchange.getResponseSender().send(MAPPER.writeValueAsString(job.pollLog(Duration.ofSeconds(15))));
//						} else {
//							exchange.setStatusCode(410);
//							exchange.getResponseSender().send("[]");
//						}
					}
				} catch (JsonProcessingException | InterruptedException e) {
					throw new RuntimeException(e);
				} finally {
					exchange.endExchange();
				}
			});
		};
	}

}
