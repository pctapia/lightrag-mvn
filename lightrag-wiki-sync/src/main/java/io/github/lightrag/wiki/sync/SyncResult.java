package io.github.lightrag.wiki.sync;

import java.time.Duration;
import java.util.List;

/**
 * Outcome of a single wiki synchronization run.
 *
 * @param filesUploaded  number of new or modified wiki pages successfully uploaded to LightRAG
 * @param filesDeleted   number of wiki pages removed from the git repo during this sync window
 *                       (these are logged but cannot be removed from LightRAG automatically
 *                       because the demo app has no document-delete endpoint)
 * @param filesFailed    number of files that failed to upload (see {@code errors} for details)
 * @param filesSkipped   number of files in a full-sync that were already up to date
 *                       (currently unused; kept for future hash-based deduplication)
 * @param fromCommit     the git SHA that was the starting point of the diff, or {@code null}
 *                       for a full sync triggered by a fresh clone
 * @param toCommit       the git SHA of HEAD after the sync, or {@code null} when the sync
 *                       itself failed before reaching git operations
 * @param duration       wall-clock time the sync took
 * @param errors         human-readable error messages for each failed file
 */
public record SyncResult(
        int filesUploaded,
        int filesDeleted,
        int filesFailed,
        int filesSkipped,
        String fromCommit,
        String toCommit,
        Duration duration,
        List<String> errors
) {

    /** Creates a result that represents no changes detected since the last sync. */
    public static SyncResult noChanges(String headCommit, Duration duration) {
        return new SyncResult(0, 0, 0, 0, headCommit, headCommit, duration, List.of());
    }

    /** Creates a result that represents a top-level failure (e.g. git pull exception). */
    public static SyncResult failed(String errorMessage, Duration duration) {
        return new SyncResult(0, 0, 1, 0, null, null, duration, List.of(errorMessage));
    }

    /** Returns {@code true} when no files failed to upload. */
    public boolean isSuccess() {
        return filesFailed == 0;
    }
}
