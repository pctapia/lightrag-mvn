package io.github.lightrag.wiki.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.wiki.config.WikiSyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Persistent mapping from git file paths to LightRAG document IDs.
 *
 * <p>Stored as {@code .wiki-doc-registry.json} inside the local clone directory.
 * The leading dot keeps it out of the full-sync file walk (same convention as
 * {@code .wiki-sync-state}).
 *
 * <p>This registry is the key enabler for document deletion and modification:
 * because LightRAG document IDs are derived from both the file name and its
 * content, the sync module cannot re-derive an old ID after the file has changed
 * or been deleted. The registry retains the last known ID for each path.
 *
 * <h2>Thread safety</h2>
 * All public methods are {@code synchronized}. The registry is loaded lazily on
 * first access and must only be used after the clone directory exists.
 */
@Component
public class WikiDocRegistry {

    private static final Logger log = LoggerFactory.getLogger(WikiDocRegistry.class);
    private static final String REGISTRY_FILENAME = ".wiki-doc-registry.json";

    private final Path registryFile;
    private final ObjectMapper objectMapper;

    /** In-memory state; {@code null} until first access (lazy load). */
    private Map<String, String> data;

    public WikiDocRegistry(WikiSyncProperties properties, ObjectMapper objectMapper) {
        this.registryFile = Path.of(properties.getLocalClonePath()).resolve(REGISTRY_FILENAME);
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the LightRAG document ID last recorded for {@code filePath},
     * or {@code null} if the path has not been synced before.
     */
    public synchronized String get(String filePath) {
        load();
        return data.get(filePath);
    }

    /**
     * Records or updates the document ID for a file path.
     * Changes are not persisted until {@link #save()} is called.
     */
    public synchronized void put(String filePath, String docId) {
        load();
        data.put(filePath, docId);
    }

    /**
     * Removes a file path from the registry.
     * Changes are not persisted until {@link #save()} is called.
     */
    public synchronized void remove(String filePath) {
        load();
        data.remove(filePath);
    }

    /**
     * Atomically persists the current in-memory state to disk.
     * Writes to a sibling {@code .tmp} file first, then renames to avoid
     * leaving a partially-written registry file on crash.
     *
     * @throws IOException if the write or rename fails
     */
    public synchronized void save() throws IOException {
        load();
        Files.createDirectories(registryFile.getParent());
        Path tempFile = registryFile.resolveSibling(REGISTRY_FILENAME + ".tmp");
        objectMapper.writeValue(tempFile.toFile(), data);
        Files.move(tempFile, registryFile, StandardCopyOption.REPLACE_EXISTING);
        log.debug("Registry saved ({} entries)", data.size());
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void load() {
        if (data != null) {
            return;
        }
        data = new HashMap<>();
        if (!Files.exists(registryFile)) {
            log.debug("No registry file found at {}; starting empty", registryFile);
            return;
        }
        try {
            data = objectMapper.readValue(registryFile.toFile(),
                    new TypeReference<HashMap<String, String>>() {});
            log.debug("Registry loaded: {} entries from {}", data.size(), registryFile);
        } catch (IOException e) {
            log.warn("Failed to read registry file {}; starting empty. Cause: {}",
                    registryFile, e.getMessage());
            data = new HashMap<>();
        }
    }
}
