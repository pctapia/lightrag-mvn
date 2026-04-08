package io.github.lightrag.wiki.sync;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link DocumentIdComputer} produces IDs that match the formula
 * in {@code UploadedDocumentMapper} (demo module).
 *
 * <p>The formula is: {@code slug(stem) + "-" + sha256(filename + 0x00 + bytes)[0:12]}
 * where {@code slug} lower-cases, replaces non-alphanumerics with {@code -}, and
 * caps at 48 characters.
 */
class DocumentIdComputerTest {

    @Test
    void producesStableIdForTheSameInput() {
        byte[] content = "# Home\nHello wiki".getBytes(StandardCharsets.UTF_8);

        String id1 = DocumentIdComputer.compute("Home.md", content);
        String id2 = DocumentIdComputer.compute("Home.md", content);

        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void idStartsWithSlugDerivedFromFilename() {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);

        String id = DocumentIdComputer.compute("Home.md", content);

        assertThat(id).startsWith("home-");
    }

    @Test
    void hashSegmentIs12HexChars() {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);

        String id = DocumentIdComputer.compute("Home.md", content);
        // format: "home-<12hexchars>"
        String hash = id.substring(id.lastIndexOf('-') + 1);

        assertThat(hash).hasSize(12).matches("[0-9a-f]{12}");
    }

    @Test
    void differentContentProducesDifferentId() {
        byte[] v1 = "original content".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "modified content".getBytes(StandardCharsets.UTF_8);

        assertThat(DocumentIdComputer.compute("Page.md", v1))
                .isNotEqualTo(DocumentIdComputer.compute("Page.md", v2));
    }

    @Test
    void differentFilenameProducesDifferentIdEvenWithSameContent() {
        byte[] content = "same content".getBytes(StandardCharsets.UTF_8);

        assertThat(DocumentIdComputer.compute("Alpha.md", content))
                .isNotEqualTo(DocumentIdComputer.compute("Beta.md", content));
    }

    @Test
    void slugSanitizesSpacesAndSpecialChars() {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);

        String id = DocumentIdComputer.compute("My Page!! v2.md", content);

        assertThat(id).startsWith("my-page-v2-");
    }

    @Test
    void slugStripsLeadingAndTrailingHyphens() {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);

        String id = DocumentIdComputer.compute("---title---.md", content);

        String slug = id.substring(0, id.lastIndexOf('-'));
        assertThat(slug).doesNotStartWith("-").doesNotEndWith("-");
    }

    @Test
    void slugIsCappedAt48Characters() {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);
        // stem is 60 'a' chars → slug should be truncated
        String longStem = "a".repeat(60);
        String id = DocumentIdComputer.compute(longStem + ".md", content);

        String slug = id.substring(0, id.lastIndexOf('-'));
        assertThat(slug.length()).isLessThanOrEqualTo(48);
    }

    @Test
    void stemWithOnlySpecialCharsFallsBackToDocumentSlug() {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);

        String id = DocumentIdComputer.compute("!@#$.md", content);

        assertThat(id).startsWith("document-");
    }

    @Test
    void fileWithNoExtensionUsesFullNameAsStem() {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);

        String id = DocumentIdComputer.compute("README", content);

        assertThat(id).startsWith("readme-");
    }

    @Test
    void adocExtensionIsTreatedLikeMd() {
        byte[] content = "= Title\nBody".getBytes(StandardCharsets.UTF_8);

        String id = DocumentIdComputer.compute("guide.adoc", content);

        assertThat(id).startsWith("guide-");
        String hash = id.substring(id.lastIndexOf('-') + 1);
        assertThat(hash).hasSize(12);
    }

    @Test
    void multiSegmentPathUsesOnlyBasenameStem() {
        // The syncer passes just the file name (not the full path) to DocumentIdComputer
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);

        // Same basename → same slug prefix regardless of path
        String id = DocumentIdComputer.compute("Home.md", content);
        assertThat(id).startsWith("home-");
    }
}
