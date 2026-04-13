package io.github.lightrag.wiki.client;

import io.github.lightrag.wiki.config.WikiSyncProperties;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Thin HTTP client for the LightRAG demo application's document upload API.
 *
 * <p>Only the upload endpoint is used here. LightRAG's demo app does not currently
 * expose a document-delete endpoint, so deleted wiki pages are only logged.
 */
@Component
public class LightRagApiClient {

    private static final Logger log = LoggerFactory.getLogger(LightRagApiClient.class);

    private static final MediaType MEDIA_MARKDOWN   = MediaType.parse("text/markdown; charset=utf-8");
    private static final MediaType MEDIA_ASCIIDOC   = MediaType.parse("text/asciidoc; charset=utf-8");
    private static final MediaType MEDIA_PLAIN_TEXT = MediaType.parse("text/plain; charset=utf-8");

    private final WikiSyncProperties properties;
    private final OkHttpClient http;

    public LightRagApiClient(WikiSyncProperties properties) {
        this.properties = properties;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        log.info("LightRagApiClient initialized: apiUrl={}, workspaceId={}",
                properties.getLightragApiUrl(), properties.getWorkspaceId());
    }

    /**
     * Uploads a single wiki file to LightRAG via {@code POST /documents/upload}.
     *
     * @param fileName file name including extension (used as the document name)
     * @param content  raw bytes of the file
     * @return raw JSON response body from LightRAG (contains job ID and document IDs)
     * @throws IOException if the HTTP call fails or the server returns a non-2xx status
     */
    public String uploadFile(String fileName, byte[] content, String gitFilePath) throws IOException {
        MediaType mediaType = resolveMediaType(fileName);

        RequestBody fileBody = RequestBody.create(content, mediaType);
        var multipartBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("files", fileName, fileBody);
        if (gitFilePath != null && !gitFilePath.isBlank()) {
            multipartBuilder.addFormDataPart("file_path", gitFilePath);
        }
        RequestBody multipart = multipartBuilder.build();

        Request request = new Request.Builder()
                .url(properties.getLightragApiUrl() + "/documents/upload")
                .addHeader("X-Workspace-Id", properties.getWorkspaceId())
                .post(multipart)
                .build();

        try (Response response = http.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException(
                        "Upload failed — HTTP " + response.code() + " for [" + fileName + "]: " + responseBody);
            }
            log.debug("Uploaded {} to LightRAG (HTTP {})", fileName, response.code());
            return responseBody;
        }
    }

    /**
     * Deletes a document from LightRAG via {@code DELETE /documents/{documentId}}.
     *
     * <p>The call is idempotent: a 404 response is treated as success, since the
     * document is already absent from LightRAG.
     *
     * @param docId the LightRAG document ID to delete
     * @throws IOException if the HTTP call fails or the server returns an unexpected non-2xx status
     */
    public void deleteDocument(String docId) throws IOException {
        Request request = new Request.Builder()
                .url(properties.getLightragApiUrl() + "/documents/" + docId)
                .addHeader("X-Workspace-Id", properties.getWorkspaceId())
                .delete()
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 404) {
                log.debug("Document {} not found in LightRAG (already deleted)", docId);
                return;
            }
            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                throw new IOException(
                        "Delete failed — HTTP " + response.code() + " for docId [" + docId + "]: " + responseBody);
            }
            log.debug("Deleted document: {}", docId);
        }
    }

    /**
     * Resolves the appropriate media type based on the file extension.
     */
    private MediaType resolveMediaType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md")) {
            return MEDIA_MARKDOWN;
        }
        if (lower.endsWith(".adoc") || lower.endsWith(".asciidoc")) {
            return MEDIA_ASCIIDOC;
        }
        return MEDIA_PLAIN_TEXT;
    }
}
