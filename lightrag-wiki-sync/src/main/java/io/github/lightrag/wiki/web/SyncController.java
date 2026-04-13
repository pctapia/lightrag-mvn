package io.github.lightrag.wiki.web;

import io.github.lightrag.wiki.scheduler.WikiSyncScheduler;
import io.github.lightrag.wiki.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes a single endpoint to trigger a wiki sync on demand, without waiting
 * for the next scheduled run.
 *
 * <p>Useful during initial setup or after an emergency wiki update.
 *
 * <pre>
 *   POST http://localhost:8090/sync/trigger
 * </pre>
 *
 * The call is synchronous — the response is returned only after the sync
 * finishes. For large wikis the first full sync can take several minutes.
 */
@RestController
@RequestMapping("/sync")
public class SyncController {

    private static final Logger log = LoggerFactory.getLogger(SyncController.class);

    private final WikiSyncScheduler scheduler;

    public SyncController(WikiSyncScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Triggers an immediate sync and returns the {@link SyncResult}.
     *
     * <p>Returns HTTP 200 even when some files failed, so callers can inspect
     * the result body. Returns HTTP 500 only for unexpected server errors.
     */
    @PostMapping("/trigger")
    public ResponseEntity<SyncResult> trigger() {
        log.info("Manual sync triggered via POST /sync/trigger");
        SyncResult result = scheduler.runSync();
        log.info("Manual sync finished — uploaded={}, deleted={}, failed={}, skipped={}, duration={}ms",
                result.filesUploaded(), result.filesDeleted(), result.filesFailed(),
                result.filesSkipped(), result.duration().toMillis());
        return ResponseEntity.ok(result);
    }
}
