package io.github.lightrag.wiki.scheduler;

import io.github.lightrag.wiki.config.WikiSyncProperties;
import io.github.lightrag.wiki.sync.WikiSyncer;
import io.github.lightrag.wiki.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives wiki synchronization on a cron schedule and optionally on startup.
 *
 * <p>The cron expression is read from {@code wiki.sync.schedule} (default: daily at 02:00).
 * Set {@code wiki.sync.enabled=false} to disable all scheduling without removing the bean.
 */
@Component
public class WikiSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(WikiSyncScheduler.class);

    private final WikiSyncer syncer;
    private final WikiSyncProperties properties;

    public WikiSyncScheduler(WikiSyncer syncer, WikiSyncProperties properties) {
        this.syncer = syncer;
        this.properties = properties;
    }

    /**
     * Runs immediately after the application context is fully started when
     * {@code wiki.sync.sync-on-startup=true}. Useful for initial seeding.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (properties.isSyncOnStartup()) {
            log.info("wiki.sync.sync-on-startup=true — triggering initial sync");
            runSync();
        }
    }

    /**
     * Scheduled sync job. The cron expression is resolved at runtime from
     * {@code wiki.sync.schedule}; the Spring EL expression below reads that
     * property so changes to application.yml take effect without recompiling.
     */
    @Scheduled(cron = "${wiki.sync.schedule:0 0 2 * * ?}")
    public void scheduledSync() {
        if (!properties.isEnabled()) {
            log.debug("Scheduled sync skipped (wiki.sync.enabled=false)");
            return;
        }
        log.info("Starting scheduled wiki sync…");
        runSync();
    }

    /**
     * Runs one sync cycle, logs the summary, and returns the result.
     * Called by both the scheduler and the manual-trigger REST endpoint.
     */
    public SyncResult runSync() {
        SyncResult result = syncer.sync();
        if (result.isSuccess()) {
            log.info(
                    "Wiki sync complete — uploaded={}, deleted={}, failed={}, duration={}ms, toCommit={}",
                    result.filesUploaded(),
                    result.filesDeleted(),
                    result.filesFailed(),
                    result.duration().toMillis(),
                    abbrev(result.toCommit()));
        } else {
            log.warn(
                    "Wiki sync finished with errors — uploaded={}, failed={}, duration={}ms, errors={}",
                    result.filesUploaded(),
                    result.filesFailed(),
                    result.duration().toMillis(),
                    result.errors());
        }
        return result;
    }

    private static String abbrev(String sha) {
        return sha == null ? "null" : sha.substring(0, Math.min(7, sha.length()));
    }
}
