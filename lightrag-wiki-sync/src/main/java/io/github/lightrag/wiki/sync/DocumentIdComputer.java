package io.github.lightrag.wiki.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Replicates the document ID formula from {@code UploadedDocumentMapper} in the demo module.
 *
 * <p>The formula must stay in sync with the demo's implementation. It is:
 * <pre>
 *   stem     = filename without extension
 *   slug     = stem.toLowerCase().replaceAll("[^a-z0-9]+", "-").trim("-")  (max 48 chars)
 *   hash     = SHA-256(filename_bytes + 0x00 + content_bytes), first 12 hex chars
 *   docId    = slug + "-" + hash
 * </pre>
 *
 * <p>Because the hash covers both the filename <em>and</em> the content, the same
 * file name uploaded with different content produces a different ID. This is why
 * the sync module must delete the old document ID and upload under the new one
 * whenever a wiki page is modified.
 */
public final class DocumentIdComputer {

    private static final int ID_PREFIX_MAX_LENGTH = 48;

    private DocumentIdComputer() {
    }

    /**
     * Computes the LightRAG document ID for a file.
     *
     * @param filename the plain file name including extension (no path separators),
     *                 e.g. {@code "Home.md"}
     * @param bytes    raw file content
     * @return the document ID that LightRAG will assign when this file is uploaded
     */
    public static String compute(String filename, byte[] bytes) {
        String stem = filename;
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0) {
            stem = filename.substring(0, dotIndex);
        }

        String slug = stem.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "document";
        }
        if (slug.length() > ID_PREFIX_MAX_LENGTH) {
            slug = slug.substring(0, ID_PREFIX_MAX_LENGTH).replaceAll("-+$", "");
        }

        return slug + "-" + shortHash(filename, bytes);
    }

    private static String shortHash(String filename, byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(filename.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(bytes);
            return toHex(digest.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
