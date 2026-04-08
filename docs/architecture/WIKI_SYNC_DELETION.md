# Wiki Sync — Document Deletion Design

## Problem statement

When a wiki page is deleted (or renamed/modified), the sync module detects
it via `git diff`. The question is: **what document ID should be passed to
`LightRag.deleteByDocumentId()`?**

The answer requires understanding how LightRAG assigns IDs, where they are stored,
and what state needs to be maintained across sync runs.

---

## Document ID formula

`UploadedDocumentMapper.buildDocumentId` (demo module) produces a deterministic ID
from the file name and its raw bytes:

```
stem    = filename without extension          e.g. "Home"
slug    = stem.toLowerCase()
          .replaceAll("[^a-z0-9]+", "-")
          .replaceAll("^-+|-+$", "")          e.g. "home"
          (capped at 48 chars)

hash    = SHA-256(filename_bytes + 0x00 + content_bytes)
          → first 12 hex characters           e.g. "a1b2c3d4e5f6"

docId   = slug + "-" + hash                   e.g. "home-a1b2c3d4e5f6"
```

Because the hash covers **both** the filename and the content, the same filename
with different content produces a different ID. This has an important implication
for modified pages (see below).

---

## The hidden MODIFY problem

With the initial sync implementation, a MODIFY diff entry causes:

1. New version uploaded → new content → **new docId**
2. Old version's docId **remains in the index** as a ghost document

This means every edit to a wiki page accumulates stale copies in LightRAG. The
deletion mechanism described here fixes both MODIFY and DELETE.

---

## Architecture: three layers

```
┌────────────────────────────────────────────────────────────┐
│  lightrag-wiki-sync                                         │
│                                                             │
│  WikiSyncer                                                 │
│   • git diff → DiffEntry(DELETE | MODIFY | ADD | RENAME)   │
│   • WikiDocRegistry: filePath → docId  (.wiki-doc-registry) │
│   • replicates buildDocumentId() formula locally            │
│   │                                                         │
│   ├─ ADD    → upload → save registry[newPath] = newDocId   │
│   ├─ MODIFY → delete(registry[path])                        │
│   │           → upload → update registry[path] = newDocId  │
│   ├─ DELETE → delete(registry[oldPath])                     │
│   │           → remove registry[oldPath]                   │
│   └─ RENAME → delete(registry[oldPath])                     │
│               → upload newPath → registry[newPath] = id    │
│               → remove registry[oldPath]                   │
└────────────────┬───────────────────────────────────────────┘
                 │  DELETE /documents/{docId}
                 │  X-Workspace-Id: <workspaceId>
                 ▼
┌────────────────────────────────────────────────────────────┐
│  lightrag-spring-boot-demo                                  │
│                                                             │
│  DELETE /documents/{documentId}   ← NEW endpoint           │
│   └─ lightRag.deleteByDocumentId(workspaceId, documentId)  │
└────────────────┬───────────────────────────────────────────┘
                 │
                 ▼
┌────────────────────────────────────────────────────────────┐
│  lightrag-core   (no changes needed)                        │
│                                                             │
│  LightRag.deleteByDocumentId(workspaceId, documentId)       │
│   └─ DeletionPipeline                                       │
│       • remove document chunks from vector store            │
│       • remove entities / relations from graph store        │
│         (only those not referenced by other documents)      │
│       • re-index surviving documents                        │
└────────────────────────────────────────────────────────────┘
```

---

## WikiDocRegistry

A JSON file persisted inside the local wiki clone directory as
`.wiki-doc-registry.json`. It maps every known git file path to the LightRAG
document ID that was used when that file was last uploaded.

```json
{
  "Home.md":              "home-a1b2c3d4e5f6",
  "docs/Architecture.md": "architecture-bb90c1d2e3f4",
  "Runbook.md":           "runbook-1122334455aa"
}
```

The file is updated atomically (write to a temp file, then rename) after every
successful upload or deletion so a crash mid-sync cannot corrupt it.

The registry file starts with a `.` so it is excluded from the full-sync file
walk (same convention as `.wiki-sync-state`).

---

## End-to-end DELETE flow

```
git diff  →  DiffEntry(DELETE, oldPath="Runbook.md")
                │
                ├─ registry.get("Runbook.md")  →  "runbook-1122334455aa"
                │
                └─ DELETE /documents/runbook-1122334455aa
                         (X-Workspace-Id: corporate-wiki)
                               │
                               ▼
                     LightRag.deleteByDocumentId(…)
                               │
                               ▼
                     DeletionPipeline:
                       • purge chunks + embeddings
                       • remove orphaned graph nodes
                       • re-index surviving documents
                               │
                               ▼
                     registry.remove("Runbook.md")
                     advance .wiki-sync-state → new HEAD
```

---

## End-to-end MODIFY flow

```
git diff  →  DiffEntry(MODIFY, path="Home.md")
                │
                ├─ oldDocId = registry.get("Home.md")  →  "home-a1b2c3d4e5f6"
                ├─ DELETE /documents/home-a1b2c3d4e5f6
                │
                ├─ read new content from working tree
                ├─ compute newDocId = buildDocumentId("Home.md", newBytes)
                ├─ POST /documents/upload  (multipart, Home.md)
                │
                └─ registry.put("Home.md", newDocId)
```

---

## Failure handling

If the **delete** call succeeds but the subsequent **upload** fails:

- The page is gone from LightRAG and not in the registry — data loss for that page.
- On the next sync run, the git diff will still show MODIFY/DELETE for the same commit
  range (because `.wiki-sync-state` was not advanced on failure), so the operation
  is retried automatically.

If the **upload** succeeds but the **registry save** fails:

- The new document is in LightRAG but the registry still holds the old docId.
- Next run will attempt to delete the already-deleted old docId (harmless 404) and
  re-upload, leaving a duplicate. This is recoverable by a full re-sync
  (`wiki.sync.sync-on-startup=true` after clearing the registry file).

---

## Changes required per module

| Module | Change |
|---|---|
| `lightrag-core` | None — `deleteByDocumentId` already exists at `LightRag.java:169` |
| `lightrag-spring-boot-demo` | Add `DELETE /documents/{documentId}` endpoint (~15 lines) |
| `lightrag-wiki-sync` | Add `WikiDocRegistry`, replicate ID formula, update syncer for MODIFY + DELETE (~150 lines) |
