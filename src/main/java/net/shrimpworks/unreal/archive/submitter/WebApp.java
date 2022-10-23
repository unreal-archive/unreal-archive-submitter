package net.shrimpworks.unreal.archive.submitter;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
import net.shrimpworks.unreal.archive.content.ContentType;

import static java.nio.file.attribute.PosixFilePermission.*;

public class WebApp implements Closeable {

	private static final Logger logger = LoggerFactory.getLogger(WebApp.class);

	private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

	private static final int WORKER_IO_THREADS = 2;
	private static final int WORKER_TASK_CORE_THREADS = 10;

	private static final String HTTP_UPLOAD = "/upload";
	private static final String HTTP_JOB = "/job/{jobId}";
	private static final String HTTP_STATUS = "/status";
	private static final Path[] PATH_ARRAY = {};

	private final ObjectMapper MAPPER = new ObjectMapper();

	private final Path uploadPath;

	private final Undertow server;
	private final String allowOrigins;
	private final StatsDClient statsD;

	public WebApp(InetSocketAddress bindAddress, SubmissionProcessor submissionProcessor, Path uploadPath, String allowOrigins,
				  StatsDClient statsD) throws IOException {
		this.statsD = statsD;
		this.uploadPath = Files.createDirectories(uploadPath.resolve("incoming"));

		this.allowOrigins = allowOrigins;
		RoutingHandler handler = Handlers.routing()
										 .add("OPTIONS", HTTP_UPLOAD, corsOptionsHandler("POST, OPTIONS"))
										 .add("POST", HTTP_UPLOAD, uploadHandler(submissionProcessor, this.uploadPath))
										 .add("OPTIONS", HTTP_JOB, corsOptionsHandler("GET, OPTIONS"))
										 .add("GET", HTTP_JOB, jobHandler(submissionProcessor))
										 .add("GET", HTTP_STATUS, statusHandler(submissionProcessor));

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
			logger.info("Cleaning upload path {}", uploadPath);
			ArchiveUtil.cleanPath(uploadPath);
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
					FormData attachment = exchange.getAttachment(FormDataParser.FORM_DATA);

					ContentType forceType = null;
					FormData.FormValue maybeForceType = attachment.getFirst("forceType");
					if (maybeForceType != null && maybeForceType.getValue() != null && !maybeForceType.getValue().isBlank()) {
						forceType = ContentType.valueOf(maybeForceType.getValue().toUpperCase());
					}

					Submissions.Job job = new Submissions.Job(forceType);
					subProcessor.trackJob(job);

					final List<Path> files = attachment.get("files").stream().map(v -> {
						try {
							Path file = v.getFileItem().getFile();
							String newName = String.format("%s.%s", Util.plainName(v.getFileName()), Util.extension(v.getFileName()));
							Path savePath = Files.createDirectories(tmpDir.resolve(Util.hash(file).substring(0, 8)));
							// we're also changing the permissions of the file here, so it can be read by the clamav user
							return Files.setPosixFilePermissions(
								Files.move(file, savePath.resolve(newName), StandardCopyOption.REPLACE_EXISTING),
								Set.of(OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_READ)
							);
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
						exchange.getResponseSender().send(MAPPER.writeValueAsString(job.pollLog(Duration.ofSeconds(15))));
					}
				} catch (JsonProcessingException | InterruptedException e) {
					throw new RuntimeException(e);
				} finally {
					exchange.endExchange();
				}
			});
		};
	}

	private HttpHandler statusHandler(SubmissionProcessor submissionProcessor) {

		return (exchange) -> {
			statsD.count("www.status", 1);
			StringBuilder html = new StringBuilder("<html><title>Job History</title><body><pre>");

			html.append(String.format("<b>%-8s %-20s %-20s %-20s %s</b>\n",
									  "Job", "Created", "Updated", "State", "Last Update"));
			html.append("<hr/>");

			submissionProcessor.jobs().stream().sorted(Comparator.comparingLong(j -> j.logHead().time))
							   .forEach(j -> {
								   html.append(String.format("<a href='job/%s?catchup=1'>%s</a>", j.id, j.id));
								   html.append(String.format(" %-20s", LocalDateTime.ofInstant(Instant.ofEpochMilli(j.logHead().time),
																							   ZoneId.systemDefault()).format(DATE_FMT)));
								   html.append(String.format(" %-20s", LocalDateTime.ofInstant(Instant.ofEpochMilli(j.logTail().time),
																							   ZoneId.systemDefault()).format(DATE_FMT)));
								   html.append(String.format(" %-21s", j.state));
								   html.append(j.logTail().message);
								   html.append("\n");
							   });

			html.append("</pre></body></html>");

			exchange.getResponseHeaders()
					.put(Headers.CONTENT_TYPE, "text/html");

			exchange.dispatch(() -> {
				try {
					exchange.getResponseSender().send(html.toString());
				} finally {
					exchange.endExchange();
				}
			});
		};
	}

}
