package io.github.lightrag.wiki.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.wiki.client.LightRagApiClient;
import io.github.lightrag.wiki.config.WikiSyncProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Integration-style unit tests for {@link WikiSyncer}.
 *
 * <p>A real local git repository (bare + working copy) is used so that git
 * operations (clone, pull, diff) are exercised end-to-end. Only
 * {@link LightRagApiClient} is mocked to avoid real HTTP calls.
 * {@link WikiDocRegistry} is real and backed by the clone temp directory.
 *
 * <h2>Remote layout</h2>
 * <pre>
 *   testRoot/
 *     test-wiki.wiki.git/   ← bare "remote" (JGit's fake wiki remote)
 *     work/                 ← working copy used by tests to author commits
 *     clone/                ← created by WikiSyncer on first sync
 * </pre>
 *
 * <p>The syncer is configured with:
 * <pre>
 *   wiki.sync.remote-url   = file:///&lt;testRoot&gt;
 *   wiki.sync.project-path = test-wiki
 *   → wikiGitUrl()         = file:///&lt;testRoot&gt;/test-wiki.wiki.git
 * </pre>
 */
class WikiSyncerTest {

    @TempDir
    Path testRoot;

    private Path workPath;   // working copy for authoring commits
    private Path clonePath;  // where the syncer clones to

    private LightRagApiClient mockApiClient;
    private WikiDocRegistry registry;
    private WikiSyncer syncer;

    @BeforeEach
    void setUp() throws Exception {
        Path remotePath = testRoot.resolve("test-wiki.wiki.git");
        workPath        = testRoot.resolve("work");
        clonePath       = testRoot.resolve("clone");

        // Create a bare repo as the fake wiki remote
        Git.init().setDirectory(remotePath.toFile()).setBare(true).call().close();

        // Clone it into a working copy so tests can author and push commits
        try (Git workGit = Git.cloneRepository()
                .setURI(remotePath.toAbsolutePath().toUri().toString())
                .setDirectory(workPath.toFile())
                .call()) {
            configureGitUser(workGit);
            // Seed an initial commit so the repo has a HEAD
            writeAndPush(workGit, "Home.md", "# Home\nInitial content");
        }

        // Configure the syncer to clone from the local bare repo
        WikiSyncProperties props = new WikiSyncProperties();
        props.setRemoteUrl("file://" + testRoot.toAbsolutePath());
        props.setProjectPath("test-wiki");
        props.setLocalClonePath(clonePath.toString());
        props.setAccessToken("irrelevant-for-local-file-repos");

        mockApiClient = mock(LightRagApiClient.class);
        registry = new WikiDocRegistry(props, new ObjectMapper());
        syncer = new WikiSyncer(props, mockApiClient, registry);
    }

    // -------------------------------------------------------------------------
    // Full sync (fresh clone — no .wiki-sync-state)
    // -------------------------------------------------------------------------

    @Test
    void fullSyncClonesRepoAndUploadsAllWikiFiles() throws Exception {
        SyncResult result = syncer.sync();

        assertThat(result.filesUploaded()).isEqualTo(1);
        assertThat(result.filesFailed()).isEqualTo(0);
        assertThat(result.toCommit()).isNotNull();
        assertThat(clonePath.resolve(".git")).exists();

        verify(mockApiClient).uploadFile(eq("Home.md"), any());
    }

    @Test
    void fullSyncRegistersDocIdForEachUploadedFile() throws Exception {
        syncer.sync();

        assertThat(registry.get("Home.md")).isNotNull();
    }

    @Test
    void fullSyncDocIdMatchesDocumentIdComputerFormula() throws Exception {
        byte[] content = Files.readAllBytes(workPath.resolve("Home.md"));
        String expectedDocId = DocumentIdComputer.compute("Home.md", content);

        syncer.sync();

        assertThat(registry.get("Home.md")).isEqualTo(expectedDocId);
    }

    @Test
    void fullSyncSavesStateToDiskSoSubsequentRunIsIncremental() throws Exception {
        syncer.sync();

        Path stateFile = clonePath.resolve(".wiki-sync-state");
        assertThat(stateFile).exists();
        assertThat(Files.readString(stateFile).strip()).isNotBlank();
    }

    @Test
    void fullSyncIgnoresNonWikiFiles() throws Exception {
        try (Git workGit = Git.open(workPath.toFile())) {
            writeAndPush(workGit, "image.png", "fake-png-bytes");
            writeAndPush(workGit, "data.json", "{\"key\":\"value\"}");
        }

        syncer.sync();

        // Only Home.md is a wiki file
        verify(mockApiClient, times(1)).uploadFile(any(), any());
        verify(mockApiClient).uploadFile(eq("Home.md"), any());
    }

    @Test
    void fullSyncIgnoresHiddenFiles() throws Exception {
        // .wiki-sync-state and .wiki-doc-registry.json live in the clone dir,
        // but any hidden file committed to the wiki should also be skipped
        // (e.g. .gitignore inside the wiki repo itself)
        try (Git workGit = Git.open(workPath.toFile())) {
            writeAndPush(workGit, ".gitignore", "*.tmp");
        }

        syncer.sync();

        // .gitignore must not be uploaded
        verify(mockApiClient, never()).uploadFile(eq(".gitignore"), any());
    }

    // -------------------------------------------------------------------------
    // No-changes (HEAD has not advanced since last sync)
    // -------------------------------------------------------------------------

    @Test
    void secondSyncWithNoNewCommitsReturnsNoChanges() throws Exception {
        syncer.sync(); // first (full) sync
        clearInvocations(mockApiClient);

        SyncResult result = syncer.sync(); // second sync — nothing changed

        assertThat(result.filesUploaded()).isEqualTo(0);
        assertThat(result.filesDeleted()).isEqualTo(0);
        assertThat(result.filesFailed()).isEqualTo(0);
        verifyNoInteractions(mockApiClient);
    }

    // -------------------------------------------------------------------------
    // Incremental sync — ADD
    // -------------------------------------------------------------------------

    @Test
    void incrementalSyncUploadsOnlyNewFile() throws Exception {
        syncer.sync(); // seed the registry
        clearInvocations(mockApiClient);

        try (Git workGit = Git.open(workPath.toFile())) {
            writeAndPush(workGit, "About.md", "# About\nAbout page");
        }

        SyncResult result = syncer.sync();

        assertThat(result.filesUploaded()).isEqualTo(1);
        assertThat(result.filesFailed()).isEqualTo(0);

        verify(mockApiClient).uploadFile(eq("About.md"), any());
        verify(mockApiClient, never()).uploadFile(eq("Home.md"), any());
    }

    @Test
    void incrementalSyncRegistersDocIdForAddedFile() throws Exception {
        syncer.sync();

        try (Git workGit = Git.open(workPath.toFile())) {
            writeAndPush(workGit, "About.md", "# About");
        }

        syncer.sync();

        assertThat(registry.get("About.md")).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Incremental sync — MODIFY (delete old ghost + upload new version)
    // -------------------------------------------------------------------------

    @Test
    void modifiedFileDeletesOldDocIdAndUploadsNewVersion() throws Exception {
        syncer.sync();
        String oldDocId = registry.get("Home.md");
        clearInvocations(mockApiClient);

        try (Git workGit = Git.open(workPath.toFile())) {
            writeAndPush(workGit, "Home.md", "# Home\nModified content");
        }

        SyncResult result = syncer.sync();

        assertThat(result.filesUploaded()).isEqualTo(1);
        assertThat(result.filesFailed()).isEqualTo(0);

        // Old ghost document must be removed from LightRAG
        verify(mockApiClient).deleteDocument(oldDocId);
        // New version must be uploaded
        verify(mockApiClient).uploadFile(eq("Home.md"), any());
    }

    @Test
    void modifiedFileProducesNewDocIdInRegistry() throws Exception {
        syncer.sync();
        String oldDocId = registry.get("Home.md");

        try (Git workGit = Git.open(workPath.toFile())) {
            writeAndPush(workGit, "Home.md", "# Home\nModified content");
        }

        syncer.sync();

        String newDocId = registry.get("Home.md");
        assertThat(newDocId).isNotNull().isNotEqualTo(oldDocId);
    }

    // -------------------------------------------------------------------------
    // Incremental sync — DELETE
    // -------------------------------------------------------------------------

    @Test
    void deletedFileCallsLightRagDeleteAndRemovesRegistryEntry() throws Exception {
        // Set up: sync both Home.md and About.md
        try (Git workGit = Git.open(workPath.toFile())) {
            writeAndPush(workGit, "About.md", "# About");
        }
        syncer.sync();
        String aboutDocId = registry.get("About.md");
        assertThat(aboutDocId).isNotNull();
        clearInvocations(mockApiClient);

        // Delete About.md from the wiki
        try (Git workGit = Git.open(workPath.toFile())) {
            deleteAndPush(workGit, "About.md");
        }

        SyncResult result = syncer.sync();

        assertThat(result.filesDeleted()).isEqualTo(1);
        assertThat(result.filesFailed()).isEqualTo(0);

        verify(mockApiClient).deleteDocument(aboutDocId);
        assertThat(registry.get("About.md")).isNull();
    }

    @Test
    void deletedFileWithNoRegistryEntryIsCountedButDoesNotCallDeleteApi() throws Exception {
        // Sync without registering About.md (simulate a stale registry)
        try (Git workGit = Git.open(workPath.toFile())) {
            writeAndPush(workGit, "About.md", "# About");
        }
        syncer.sync();
        // Manually remove from registry to simulate a gap
        registry.remove("About.md");
        clearInvocations(mockApiClient);

        // Now delete About.md
        try (Git workGit = Git.open(workPath.toFile())) {
            deleteAndPush(workGit, "About.md");
        }

        SyncResult result = syncer.sync();

        assertThat(result.filesDeleted()).isEqualTo(1);
        // No delete API call since docId is not in registry
        verify(mockApiClient, never()).deleteDocument(any());
    }

    @Test
    void deletingNonWikiFileDoesNotCountAsDeleted() throws Exception {
        try (Git workGit = Git.open(workPath.toFile())) {
            writeAndPush(workGit, "script.sh", "#!/bin/sh");
        }
        syncer.sync();
        clearInvocations(mockApiClient);

        try (Git workGit = Git.open(workPath.toFile())) {
            deleteAndPush(workGit, "script.sh");
        }

        SyncResult result = syncer.sync();

        assertThat(result.filesDeleted()).isEqualTo(0);
        verifyNoInteractions(mockApiClient);
    }

    // -------------------------------------------------------------------------
    // State file management
    // -------------------------------------------------------------------------

    @Test
    void stateFileNotAdvancedWhenUploadFails() throws Exception {
        // First sync succeeds and writes a state file
        syncer.sync();
        String firstState = Files.readString(clonePath.resolve(".wiki-sync-state")).strip();

        // Simulate an upload failure on the next commit
        try (Git workGit = Git.open(workPath.toFile())) {
            writeAndPush(workGit, "New.md", "New page");
        }
        org.mockito.Mockito.doThrow(new IOException("simulated upload failure"))
                .when(mockApiClient).uploadFile(eq("New.md"), any());

        SyncResult result = syncer.sync();

        assertThat(result.filesFailed()).isEqualTo(1);
        // State must stay at the commit before the failed one
        String stateAfter = Files.readString(clonePath.resolve(".wiki-sync-state")).strip();
        assertThat(stateAfter).isEqualTo(firstState);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Writes (or overwrites) a file in the working copy, stages it, commits, and pushes.
     */
    private RevCommit writeAndPush(Git git, String filename, String content) throws Exception {
        Path file = git.getRepository().getWorkTree().toPath().resolve(filename);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(filename).call();
        RevCommit commit = git.commit()
                .setMessage("Test: write " + filename)
                .setAuthor("Test User", "test@example.com")
                .setCommitter("Test User", "test@example.com")
                .call();
        git.push().call();
        return commit;
    }

    /**
     * Stages a file deletion, commits, and pushes.
     */
    private void deleteAndPush(Git git, String filename) throws Exception {
        git.rm().addFilepattern(filename).call();
        git.commit()
                .setMessage("Test: delete " + filename)
                .setAuthor("Test User", "test@example.com")
                .setCommitter("Test User", "test@example.com")
                .call();
        git.push().call();
    }

    /** Configures git user identity on the given repository. */
    private void configureGitUser(Git git) throws Exception {
        StoredConfig config = git.getRepository().getConfig();
        config.setString("user", null, "name", "Test User");
        config.setString("user", null, "email", "test@example.com");
        config.save();
    }
}
