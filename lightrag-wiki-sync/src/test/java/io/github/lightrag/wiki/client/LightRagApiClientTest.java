package io.github.lightrag.wiki.client;

import io.github.lightrag.wiki.config.WikiSyncProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LightRagApiClient} using an in-process {@link MockWebServer}.
 *
 * <p>Covers: upload success, upload HTTP error, delete success (204),
 * delete idempotent on 404, and delete HTTP error.
 */
class LightRagApiClientTest {

    private MockWebServer server;
    private LightRagApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        WikiSyncProperties props = new WikiSyncProperties();
        // Strip the trailing slash that MockWebServer adds to the base URL
        String baseUrl = server.url("/").toString();
        props.setLightragApiUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        props.setWorkspaceId("test-workspace");

        client = new LightRagApiClient(props);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // -------------------------------------------------------------------------
    // uploadFile
    // -------------------------------------------------------------------------

    @Test
    void uploadFileSucceedsAndReturnsResponseBody() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jobId\":\"job-1\",\"documentIds\":[\"home-abc123def456\"]}"));

        String response = client.uploadFile("Home.md",
                "# Home\nContent".getBytes(StandardCharsets.UTF_8));

        assertThat(response).contains("job-1").contains("home-abc123def456");
    }

    @Test
    void uploadFileSendsPostToCorrectEndpoint() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(202).setBody("{}"));

        client.uploadFile("Home.md", "content".getBytes(StandardCharsets.UTF_8));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/documents/upload");
    }

    @Test
    void uploadFileSendsWorkspaceIdHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(202).setBody("{}"));

        client.uploadFile("Home.md", "content".getBytes(StandardCharsets.UTF_8));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("X-Workspace-Id")).isEqualTo("test-workspace");
    }

    @Test
    void uploadFileSendsFileNameInMultipartBody() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(202).setBody("{}"));

        client.uploadFile("Home.md", "# Home".getBytes(StandardCharsets.UTF_8));

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("Home.md");
    }

    @Test
    void uploadFileSetsMarkdownMediaTypeForMdExtension() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(202).setBody("{}"));

        client.uploadFile("notes.md", "content".getBytes(StandardCharsets.UTF_8));

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("text/markdown");
    }

    @Test
    void uploadFileSetsAsciidocMediaTypeForAdocExtension() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(202).setBody("{}"));

        client.uploadFile("guide.adoc", "= Title".getBytes(StandardCharsets.UTF_8));

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("text/asciidoc");
    }

    @Test
    void uploadFileSetsPlainTextMediaTypeForUnknownExtension() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(202).setBody("{}"));

        client.uploadFile("notes.txt", "content".getBytes(StandardCharsets.UTF_8));

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("text/plain");
    }

    @Test
    void uploadFileThrowsIoExceptionOnServerError() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":\"internal server error\"}"));

        assertThatThrownBy(() ->
                client.uploadFile("Home.md", "content".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("Home.md");
    }

    @Test
    void uploadFileThrowsIoExceptionOn400() {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setBody("{\"message\":\"file too large\"}"));

        assertThatThrownBy(() ->
                client.uploadFile("large.md", "x".repeat(100).getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("400");
    }

    // -------------------------------------------------------------------------
    // deleteDocument
    // -------------------------------------------------------------------------

    @Test
    void deleteDocumentSucceedsOn204() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        assertThatNoException().isThrownBy(() -> client.deleteDocument("home-abc123def456"));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("DELETE");
        assertThat(request.getPath()).isEqualTo("/documents/home-abc123def456");
    }

    @Test
    void deleteDocumentSendsWorkspaceIdHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        client.deleteDocument("home-abc123def456");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("X-Workspace-Id")).isEqualTo("test-workspace");
    }

    @Test
    void deleteDocumentTreats404AsSuccessForIdempotency() {
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThatNoException().isThrownBy(() -> client.deleteDocument("already-gone"));
    }

    @Test
    void deleteDocumentThrowsIoExceptionOn500() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("internal error"));

        assertThatThrownBy(() -> client.deleteDocument("some-doc-id"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("some-doc-id");
    }

    @Test
    void deleteDocumentThrowsIoExceptionOn403() {
        server.enqueue(new MockResponse()
                .setResponseCode(403)
                .setBody("forbidden"));

        assertThatThrownBy(() -> client.deleteDocument("protected-doc"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("403");
    }

    @Test
    void deleteDocumentEncodesDocIdInPath() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        client.deleteDocument("my-doc-aabbccddeeff");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/documents/my-doc-aabbccddeeff");
    }
}
