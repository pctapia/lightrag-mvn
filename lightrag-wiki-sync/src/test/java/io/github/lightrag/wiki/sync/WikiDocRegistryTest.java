package io.github.lightrag.wiki.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.wiki.config.WikiSyncProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WikiDocRegistry}.
 *
 * <p>Each test uses a fresh {@link TempDir}, so there is no shared state
 * between tests and no cleanup is required.
 */
class WikiDocRegistryTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // In-memory operations (before any save)
    // -------------------------------------------------------------------------

    @Test
    void returnsNullForUnknownFilePath() {
        WikiDocRegistry registry = registryFor(tempDir);

        assertThat(registry.get("Home.md")).isNull();
    }

    @Test
    void putsValueAndRetrievesIt() {
        WikiDocRegistry registry = registryFor(tempDir);

        registry.put("Home.md", "home-abc123def456");

        assertThat(registry.get("Home.md")).isEqualTo("home-abc123def456");
    }

    @Test
    void putOverwritesExistingEntry() {
        WikiDocRegistry registry = registryFor(tempDir);
        registry.put("Home.md", "home-old000000000");
        registry.put("Home.md", "home-new111111111");

        assertThat(registry.get("Home.md")).isEqualTo("home-new111111111");
    }

    @Test
    void removeDeletesEntry() {
        WikiDocRegistry registry = registryFor(tempDir);
        registry.put("Home.md", "home-abc123def456");

        registry.remove("Home.md");

        assertThat(registry.get("Home.md")).isNull();
    }

    @Test
    void removingAbsentKeyIsIdempotent() {
        WikiDocRegistry registry = registryFor(tempDir);

        // Should not throw
        registry.remove("nonexistent.md");
        assertThat(registry.get("nonexistent.md")).isNull();
    }

    @Test
    void multipleEntriesCoexistIndependently() {
        WikiDocRegistry registry = registryFor(tempDir);
        registry.put("Home.md", "home-aaa000000001");
        registry.put("About.md", "about-bbb000000002");
        registry.put("docs/Guide.md", "guide-ccc000000003");

        assertThat(registry.get("Home.md")).isEqualTo("home-aaa000000001");
        assertThat(registry.get("About.md")).isEqualTo("about-bbb000000002");
        assertThat(registry.get("docs/Guide.md")).isEqualTo("guide-ccc000000003");
    }

    // -------------------------------------------------------------------------
    // Persistence: save + reload
    // -------------------------------------------------------------------------

    @Test
    void saveAndReloadRoundTripPreservesAllEntries() throws Exception {
        WikiDocRegistry registry1 = registryFor(tempDir);
        registry1.put("Home.md", "home-aaa000000001");
        registry1.put("About.md", "about-bbb000000002");
        registry1.save();

        WikiDocRegistry registry2 = registryFor(tempDir);
        assertThat(registry2.get("Home.md")).isEqualTo("home-aaa000000001");
        assertThat(registry2.get("About.md")).isEqualTo("about-bbb000000002");
    }

    @Test
    void removedEntriesAreExcludedAfterSave() throws Exception {
        WikiDocRegistry registry1 = registryFor(tempDir);
        registry1.put("Home.md", "home-aaa000000001");
        registry1.put("Old.md", "old-bbb000000002");
        registry1.save();

        registry1.remove("Old.md");
        registry1.save();

        WikiDocRegistry registry2 = registryFor(tempDir);
        assertThat(registry2.get("Home.md")).isEqualTo("home-aaa000000001");
        assertThat(registry2.get("Old.md")).isNull();
    }

    @Test
    void overwrittenEntryIsPersistedWithNewValue() throws Exception {
        WikiDocRegistry registry1 = registryFor(tempDir);
        registry1.put("Home.md", "home-old000000000");
        registry1.save();

        registry1.put("Home.md", "home-new111111111");
        registry1.save();

        WikiDocRegistry registry2 = registryFor(tempDir);
        assertThat(registry2.get("Home.md")).isEqualTo("home-new111111111");
    }

    // -------------------------------------------------------------------------
    // Edge cases: missing file, corrupt file, atomic save
    // -------------------------------------------------------------------------

    @Test
    void startsEmptyWhenNoRegistryFileExists() {
        WikiDocRegistry registry = registryFor(tempDir);

        // The file does not exist yet; operations should work without error
        assertThat(registry.get("anything.md")).isNull();
    }

    @Test
    void recoversGracefullyFromCorruptedJsonFile() throws Exception {
        Files.writeString(tempDir.resolve(".wiki-doc-registry.json"), "{ not valid json }");

        WikiDocRegistry registry = registryFor(tempDir);

        // Should not throw; starts empty
        assertThat(registry.get("Home.md")).isNull();

        // Can still accept new entries and save
        registry.put("Home.md", "home-aaa000000001");
        assertThat(registry.get("Home.md")).isEqualTo("home-aaa000000001");
    }

    @Test
    void saveDoesNotLeaveTemporaryFileOnDisk() throws Exception {
        WikiDocRegistry registry = registryFor(tempDir);
        registry.put("Home.md", "home-aaa000000001");

        registry.save();

        Path tempFile = tempDir.resolve(".wiki-doc-registry.json.tmp");
        assertThat(tempFile).doesNotExist();
    }

    @Test
    void registryFileIsCreatedByFirstSave() throws Exception {
        WikiDocRegistry registry = registryFor(tempDir);
        Path registryFile = tempDir.resolve(".wiki-doc-registry.json");
        assertThat(registryFile).doesNotExist();

        registry.put("Home.md", "home-aaa000000001");
        registry.save();

        assertThat(registryFile).exists();
    }

    @Test
    void saveCreatesParentDirectoryIfMissing() throws Exception {
        Path nestedDir = tempDir.resolve("deep/nested/clone");
        WikiSyncProperties props = new WikiSyncProperties();
        props.setLocalClonePath(nestedDir.toString());
        WikiDocRegistry registry = new WikiDocRegistry(props, new ObjectMapper());

        registry.put("Home.md", "home-aaa000000001");
        registry.save(); // parent dir does not exist yet

        assertThat(nestedDir.resolve(".wiki-doc-registry.json")).exists();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private WikiDocRegistry registryFor(Path dir) {
        WikiSyncProperties props = new WikiSyncProperties();
        props.setLocalClonePath(dir.toString());
        return new WikiDocRegistry(props, new ObjectMapper());
    }
}
