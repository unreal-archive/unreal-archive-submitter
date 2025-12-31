package org.unrealarchive.submitter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import org.unrealarchive.content.addons.SimpleAddonType;
import org.unrealarchive.submitter.submit.SubmissionProcessor;
import org.unrealarchive.submitter.submit.Submissions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.wildfly.common.Assert.assertFalse;

public class WebAppTest {

	private static final int APP_PORT = 58974 + (int)(Math.random() * 1000);

	private final SubmissionProcessor mockProcessor;

	public WebAppTest() {
		this.mockProcessor = Mockito.mock(SubmissionProcessor.class);
	}

	@Test
	void testUploadWithForcedType() throws IOException, InterruptedException {
		Path uploadPath = Files.createTempDirectory("ua-test-upload");

		try (WebApp ignored = new WebApp(InetSocketAddress.createUnresolved("127.0.0.1", APP_PORT),
										 mockProcessor, uploadPath, "*")) {

			MultiPartBodyPublisher bp = new MultiPartBodyPublisher();
			bp.addPart("files", () -> getClass().getResourceAsStream("test.txt"), "test.txt", "text/plain")
			  .addPart("forceType", "map");

			HttpRequest req = HttpRequest.newBuilder()
										 .uri(URI.create("http://127.0.0.1:" + APP_PORT + "/upload"))
										 .header("Content-Type", "multipart/form-data; boundary=" + bp.getBoundary())
										 .POST(bp.build())
										 .build();
			HttpClient c = HttpClient.newHttpClient();
			String result = c.send(req, HttpResponse.BodyHandlers.ofString()).body();
			assertFalse(result.isBlank());

			ArgumentCaptor<Submissions.Job> jobCapture = ArgumentCaptor.forClass(Submissions.Job.class);
			Mockito.verify(mockProcessor).trackJob(jobCapture.capture());

			assertEquals(SimpleAddonType.MAP, jobCapture.getValue().forcedType);
		} finally {
			Files.deleteIfExists(uploadPath);
		}
	}
}
