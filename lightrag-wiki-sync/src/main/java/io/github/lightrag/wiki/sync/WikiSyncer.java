package io.github.lightrag.wiki.sync;

import io.github.lightrag.wiki.client.LightRagApiClient;
import io.github.lightrag.wiki.config.WikiSyncProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Core sync engine: clones the wiki repository on first run, then pulls
 * and uploads only changed pages on every subsequent run.
 *
 * <h2>Sync lifecycle</h2>
 * <ol>
 *   <li>If the local clone does not exist, {@code git clone} the wiki repo and
 *       perform a <em>full sync</em> — upload every wiki file found.</li>
 *   <li>On subsequent calls, {@code git pull} and compare the new HEAD to the
 *       last-synced commit SHA stored in {@code .wiki-sync-state}.</li>
 *   <li>For each changed file:
 *     <ul>
 *       <li><b>ADD / COPY</b>: upload and register the new document ID.</li>
 *       <li><b>MODIFY</b>: delete the old document (old ID from registry), then
 *           upload and register the new ID. Without this step the old version
 *           would remain as a ghost in LightRAG because the new content hash
 *           produces a different document ID.</li>
 *       <li><b>RENAME</b>: delete under the old path, upload under the new path.</li>
 *       <li><b>DELETE</b>: look up the old document ID in the registry, call the
 *           LightRAG delete endpoint, remove the entry from the registry.</li>
 *     </ul>
 *   </li>
 *   <li>Persist the registry and advance {@code .wiki-sync-state} to the new
 *       HEAD only when the sync finished without failures.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * This bean is a singleton. {@link #sync()} is {@code synchronized} to prevent
 * concurrent git operations on the same local clone.
 */
@Service
public class WikiSyncer {

    private static final Logger log = LoggerFactory.getLogger(WikiSyncer.class);

    /**
     * Hidden file inside the local clone directory that records the last commit
     * SHA successfully synced. Hidden by convention (leading dot); never staged.
     */
    private static final String STATE_FILE = ".wiki-sync-state";

    private final WikiSyncProperties properties;
    private final LightRagApiClient apiClient;
    private final WikiDocRegistry registry;

    public WikiSyncer(
            WikiSyncProperties properties,
            LightRagApiClient apiClient,
            WikiDocRegistry registry) {
        this.properties = properties;
        this.apiClient = apiClient;
        this.registry = registry;
    }

    /**
     * Runs one synchronization cycle. Thread-safe (synchronized).
     *
     * @return a {@link SyncResult} describing what happened
     */
    public synchronized SyncResult sync() {
        Instant start = Instant.now();
        Path clonePath = Path.of(properties.getLocalClonePath());

        try {
            ensureCloneExists(clonePath);
        } catch (GitAPIException | IOException e) {
            log.error("Failed to clone wiki repository", e);
            return SyncResult.failed("Clone failed: " + e.getMessage(), elapsed(start));
        }

        try (Git git = Git.open(clonePath.toFile())) {
            String stateCommit = readStateCommit(clonePath);
            String headBefore   = headSha(git);

            pullLatest(git);

            String headAfter = headSha(git);
            log.info("HEAD before pull: {}, after pull: {}", abbrev(headBefore), abbrev(headAfter));

            // Nothing changed since last sync
            if (headAfter.equals(stateCommit)) {
                log.info("No changes since last sync (HEAD: {})", abbrev(headAfter));
                return SyncResult.noChanges(headAfter, elapsed(start));
            }

            SyncResult result;

            if (stateCommit == null) {
                // No record of a previous sync — upload every wiki file
                log.info("No sync state found; performing full sync…");
                result = fullSync(clonePath, headAfter, start);
            } else {
                // Process only files changed between the last synced commit and HEAD
                log.info("Incremental sync {} → {}…", abbrev(stateCommit), abbrev(headAfter));
                result = incrementalSync(git, clonePath, stateCommit, headAfter, start);
            }

            // Only advance state when no files failed (avoid silent data gaps)
            if (result.isSuccess()) {
                registry.save();
                saveStateCommit(clonePath, headAfter);
            } else {
                log.warn(
                        "Sync finished with {} failure(s); state not advanced to allow retry on next run",
                        result.filesFailed());
            }

            return result;

        } catch (Exception e) {
            log.error("Wiki sync failed", e);
            return SyncResult.failed(e.getMessage(), elapsed(start));
        }
    }

    // -------------------------------------------------------------------------
    // Clone / pull
    // -------------------------------------------------------------------------

    private void ensureCloneExists(Path clonePath) throws GitAPIException, IOException {
        if (Files.exists(clonePath.resolve(".git"))) {
            return;
        }
        log.info("Cloning wiki repo {} → {}", properties.resolveGitUrl(), clonePath);
        Files.createDirectories(clonePath);
        var clone = Git.cloneRepository()
                .setURI(properties.resolveGitUrl())
                .setDirectory(clonePath.toFile());
        var creds = credentials();
        if (creds != null) {
            clone.setCredentialsProvider(creds);
        }
        clone.call().close();
        log.info("Clone complete");
    }

    private void pullLatest(Git git) throws GitAPIException {
        var pull = git.pull();
        var creds = credentials();
        if (creds != null) {
            pull.setCredentialsProvider(creds);
        }
        pull.call();
    }

    // -------------------------------------------------------------------------
    // Full sync (fresh clone or first run without a state file)
    // -------------------------------------------------------------------------

    private SyncResult fullSync(Path clonePath, String headSha, Instant start) {
        int uploaded = 0, failed = 0;
        List<String> errors = new ArrayList<>();

        try (Stream<Path> files = Files.walk(clonePath)) {
            List<Path> wikiFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> !isHiddenPath(clonePath, p))
                    .filter(p -> isWikiFile(p.getFileName().toString()))
                    .toList();

            log.info("Full sync: {} wiki files found", wikiFiles.size());

            for (Path file : wikiFiles) {
                String relativePath = clonePath.relativize(file).toString();
                try {
                    uploadAndRegister(file, relativePath);
                    uploaded++;
                } catch (IOException e) {
                    failed++;
                    errors.add(relativePath + ": " + e.getMessage());
                    log.error("Failed to upload {}: {}", relativePath, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to walk clone directory", e);
            errors.add("Directory walk failed: " + e.getMessage());
            failed++;
        }

        return new SyncResult(uploaded, 0, failed, 0, null, headSha, elapsed(start), errors);
    }

    // -------------------------------------------------------------------------
    // Incremental sync (git diff between last-synced commit and HEAD)
    // -------------------------------------------------------------------------

    private SyncResult incrementalSync(
            Git git, Path clonePath, String fromSha, String toSha, Instant start)
            throws Exception {

        Repository repo = git.getRepository();
        AbstractTreeIterator oldTree = treeParserFor(repo, ObjectId.fromString(fromSha));
        AbstractTreeIterator newTree = treeParserFor(repo, ObjectId.fromString(toSha));

        List<DiffEntry> diffs = git.diff()
                .setOldTree(oldTree)
                .setNewTree(newTree)
                .call();

        int uploaded = 0, deleted = 0, failed = 0;
        List<String> errors = new ArrayList<>();

        for (DiffEntry diff : diffs) {
            String filePath = effectivePath(diff);

            if (!isWikiFile(filePath)) {
                continue;
            }

            switch (diff.getChangeType()) {
                case ADD, COPY -> {
                    Path file = clonePath.resolve(filePath);
                    try {
                        uploadAndRegister(file, filePath);
                        uploaded++;
                    } catch (IOException e) {
                        failed++;
                        errors.add(filePath + ": " + e.getMessage());
                        log.error("Failed to upload {}: {}", filePath, e.getMessage());
                    }
                }

                case MODIFY -> {
                    // Delete the old document first to prevent a ghost copy remaining
                    // in LightRAG. The new content hash produces a different docId, so
                    // the upload alone would not overwrite the old entry.
                    tryDeleteFromRegistry(filePath, "modified");

                    Path file = clonePath.resolve(filePath);
                    try {
                        uploadAndRegister(file, filePath);
                        uploaded++;
                    } catch (IOException e) {
                        failed++;
                        errors.add(filePath + ": " + e.getMessage());
                        log.error("Failed to upload modified {}: {}", filePath, e.getMessage());
                    }
                }

                case RENAME -> {
                    // Remove the old path from LightRAG and upload under the new one.
                    // The old path entry is removed from the registry regardless of whether
                    // the delete API call succeeds, because the file no longer exists at
                    // that path in the wiki.
                    tryDeleteFromRegistry(diff.getOldPath(), "renamed");

                    Path file = clonePath.resolve(diff.getNewPath());
                    try {
                        uploadAndRegister(file, diff.getNewPath());
                        uploaded++;
                        log.info("Renamed {} → {}", diff.getOldPath(), diff.getNewPath());
                    } catch (IOException e) {
                        failed++;
                        errors.add(diff.getNewPath() + " (rename): " + e.getMessage());
                        log.error("Failed to upload renamed {}: {}", diff.getNewPath(), e.getMessage());
                    }
                }

                case DELETE -> {
                    String oldDocId = registry.get(filePath);
                    if (oldDocId == null) {
                        log.warn("Deleted wiki page {} has no tracked docId; skipping LightRAG delete",
                                filePath);
                        deleted++;
                        break;
                    }
                    try {
                        apiClient.deleteDocument(oldDocId);
                        registry.remove(filePath);
                        deleted++;
                        log.info("Deleted: {} (docId={})", filePath, abbrev(oldDocId));
                    } catch (IOException e) {
                        failed++;
                        errors.add(filePath + " (delete): " + e.getMessage());
                        log.error("Failed to delete {} (docId={}): {}",
                                filePath, abbrev(oldDocId), e.getMessage());
                    }
                }
            }
        }

        return new SyncResult(uploaded, deleted, failed, 0, fromSha, toSha, elapsed(start), errors);
    }

    // -------------------------------------------------------------------------
    // Upload + register
    // -------------------------------------------------------------------------

    /**
     * Reads the file, computes its LightRAG document ID, uploads it, and records
     * the path → ID mapping in the registry.
     *
     * @return the computed document ID
     */
    private String uploadAndRegister(Path file, String gitFilePath) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("File not found on disk: " + file);
        }
        byte[] content = Files.readAllBytes(file);
        String fileName = file.getFileName().toString();
        String docId = DocumentIdComputer.compute(fileName, content);

        log.debug("Uploading {} → docId={}", gitFilePath, docId);
        apiClient.uploadFile(fileName, content, gitFilePath);
        registry.put(gitFilePath, docId);
        log.info("Uploaded: {} (docId={})", gitFilePath, abbrev(docId));
        return docId;
    }

    /**
     * Looks up the document ID in the registry and calls the LightRAG delete
     * endpoint. Logs a warning but does not throw if the ID is unknown or the
     * call fails, so a failing delete never aborts the broader sync run.
     */
    private void tryDeleteFromRegistry(String filePath, String reason) {
        String oldDocId = registry.get(filePath);
        if (oldDocId == null) {
            log.debug("No registered docId for {} ({}); nothing to delete", filePath, reason);
            return;
        }
        try {
            apiClient.deleteDocument(oldDocId);
            registry.remove(filePath);
            log.info("Deleted old document: {} docId={} ({})", filePath, abbrev(oldDocId), reason);
        } catch (IOException e) {
            log.warn("Could not delete old document for {} (docId={}, reason={}): {}",
                    filePath, abbrev(oldDocId), reason, e.getMessage());
            // Remove from registry anyway — the file no longer exists at this path
            // in the wiki, so retaining the stale entry would cause repeated failed
            // deletes on every subsequent sync.
            registry.remove(filePath);
        }
    }

    // -------------------------------------------------------------------------
    // Sync state (last-synced commit SHA)
    // -------------------------------------------------------------------------

    private String readStateCommit(Path clonePath) {
        Path stateFile = clonePath.resolve(STATE_FILE);
        if (!Files.exists(stateFile)) {
            return null;
        }
        try {
            String sha = Files.readString(stateFile).strip();
            return sha.isEmpty() ? null : sha;
        } catch (IOException e) {
            log.warn("Could not read sync state file; will perform full sync", e);
            return null;
        }
    }

    private void saveStateCommit(Path clonePath, String commitSha) throws IOException {
        Files.writeString(clonePath.resolve(STATE_FILE), commitSha);
        log.debug("Sync state saved: {}", abbrev(commitSha));
    }

    // -------------------------------------------------------------------------
    // JGit utilities
    // -------------------------------------------------------------------------

    private String headSha(Git git) throws IOException {
        ObjectId head = git.getRepository().resolve("HEAD");
        if (head == null) {
            throw new IOException("Repository has no HEAD (empty repo?)");
        }
        return head.getName();
    }

    private AbstractTreeIterator treeParserFor(Repository repo, ObjectId objectId) throws Exception {
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(objectId);
            RevTree tree = walk.parseTree(commit.getTree().getId());
            CanonicalTreeParser parser = new CanonicalTreeParser();
            try (ObjectReader reader = repo.newObjectReader()) {
                parser.reset(reader, tree.getId());
            }
            return parser;
        }
    }

    /**
     * Returns a {@link UsernamePasswordCredentialsProvider} for the configured access token,
     * or {@code null} when no token is set (public repositories do not require authentication).
     *
     * <p>The username {@code "x-access-token"} is accepted by both GitHub and GitLab when
     * a personal access token is supplied as the password.
     */
    private UsernamePasswordCredentialsProvider credentials() {
        String token = properties.getAccessToken();
        if (token == null || token.isBlank()) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider("x-access-token", token);
    }

    // -------------------------------------------------------------------------
    // Path / file type helpers
    // -------------------------------------------------------------------------

    private boolean isWikiFile(String fileName) {
        String lower = fileName.toLowerCase();
        return properties.getFileExtensions().stream().anyMatch(lower::endsWith);
    }

    /**
     * Returns {@code true} if any segment of {@code file}'s path relative to
     * {@code root} starts with a dot. This excludes {@code .git/}, the
     * {@code .wiki-sync-state} file, {@code .wiki-doc-registry.json}, etc.
     */
    private boolean isHiddenPath(Path root, Path file) {
        Path relative = root.relativize(file);
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /** Returns the path most relevant for this diff entry. */
    private String effectivePath(DiffEntry diff) {
        return diff.getChangeType() == DiffEntry.ChangeType.DELETE
                ? diff.getOldPath()
                : diff.getNewPath();
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }

    private static String abbrev(String sha) {
        return sha == null ? "null" : sha.substring(0, Math.min(7, sha.length()));
    }
}
