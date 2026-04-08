# LightRAG Java — API Reference

Two services, two ports:

| Service | Default port | Purpose |
|---|---|---|
| `lightrag-spring-boot-demo` | **8080** | RAG core — ingest, query, graph management |
| `lightrag-wiki-sync` | **8090** | Wiki sync sidecar — clones a git wiki and keeps LightRAG up to date |

---

## Common header

Every request to the demo service should include a workspace header to isolate data between teams or projects. When omitted the default workspace is used.

```
X-Workspace-Id: <your-workspace-id>
```

---

## lightrag-spring-boot-demo (port 8080)

### Document ingestion

#### `POST /documents/upload`

Upload one or more files for ingestion. Accepts Markdown, AsciiDoc, plain text, and other text formats.

**Content-Type:** `multipart/form-data`

| Part / param | Type | Required | Description |
|---|---|---|---|
| `files` | file parts | yes | One or more files |
| `async` | query param | no | Override the async-ingest flag for this request |
| `preset` | query param | no | Named ingest preset (configured in application.yml) |

**Response — 202 Accepted**
```json
{
  "jobId": "job-abc123",
  "documentIds": ["home-34a1b2c3d4e5", "about-6f7a8b9c0d1e"]
}
```

```bash
curl -X POST http://localhost:8080/documents/upload \
  -H "X-Workspace-Id: my-wiki" \
  -F "files=@Home.md;type=text/markdown" \
  -F "files=@About.md;type=text/markdown"
```

---

#### `POST /documents/ingest`

Ingest documents from a JSON payload instead of file upload. Useful for programmatic ingestion where content is already in memory.

**Content-Type:** `application/json`

```json
{
  "documents": [
    {
      "id": "page-001",
      "title": "Getting Started",
      "content": "# Getting Started\nInstall the tool with...",
      "metadata": {
        "source": "github-wiki",
        "author": "alice"
      }
    }
  ]
}
```

| Field | Required | Description |
|---|---|---|
| `id` | yes | Stable identifier for this document |
| `title` | yes | Document title |
| `content` | yes | Full text content |
| `metadata` | no | Arbitrary key-value pairs |

**Response — 202 Accepted**
```json
{ "jobId": "job-xyz789" }
```

---

### Job management

Ingest operations run asynchronously. Use these endpoints to track progress.

#### `GET /documents/jobs`

List all ingest jobs for the workspace, newest first.

| Query param | Default | Description |
|---|---|---|
| `page` | 0 | Zero-based page index |
| `size` | 20 | Page size |

**Response — 200 OK**
```json
{
  "items": [
    {
      "jobId": "job-abc123",
      "status": "COMPLETED",
      "documentCount": 5,
      "createdAt": "2026-04-06T08:00:00Z",
      "startedAt": "2026-04-06T08:00:01Z",
      "finishedAt": "2026-04-06T08:00:45Z",
      "errorMessage": null,
      "cancellable": false,
      "retriable": false,
      "retriedFromJobId": null,
      "attempt": 1
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1
}
```

Job status values: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`

---

#### `GET /documents/jobs/{jobId}`

Get a single job by ID. Returns 404 if not found.

```bash
curl http://localhost:8080/documents/jobs/job-abc123 \
  -H "X-Workspace-Id: my-wiki"
```

---

#### `POST /documents/jobs/{jobId}/cancel`

Cancel a running or pending job. Returns 202 with the updated job record.

```bash
curl -X POST http://localhost:8080/documents/jobs/job-abc123/cancel \
  -H "X-Workspace-Id: my-wiki"
```

---

#### `POST /documents/jobs/{jobId}/retry`

Retry a failed job. Creates a new job linked to the original via `retriedFromJobId`. Returns 202.

```bash
curl -X POST http://localhost:8080/documents/jobs/job-abc123/retry \
  -H "X-Workspace-Id: my-wiki"
```

---

### Document status

#### `GET /documents/status`

List processing status for all documents in the workspace.

**Response — 200 OK**
```json
[
  { "documentId": "home-34a1b2c3d4e5", "status": "PROCESSED" },
  { "documentId": "about-6f7a8b9c0d1e", "status": "PROCESSING" }
]
```

---

#### `GET /documents/status/{documentId}`

Status for a single document. Returns 404 if the document is not found.

```bash
curl http://localhost:8080/documents/status/home-34a1b2c3d4e5 \
  -H "X-Workspace-Id: my-wiki"
```

---

#### `DELETE /documents/{documentId}`

Delete a document and remove it from the index. Idempotent — returns 204 even if the document does not exist.

```bash
curl -X DELETE http://localhost:8080/documents/home-34a1b2c3d4e5 \
  -H "X-Workspace-Id: my-wiki"
```

---

### Query

#### `POST /query`

Query the RAG system. Returns the full response after generation completes (buffered).

**Content-Type:** `application/json`

| Field | Type | Required | Description |
|---|---|---|---|
| `query` | string | yes | The question or search string |
| `mode` | enum | no | Retrieval mode: `hybrid`, `local`, `global`, `naive` |
| `topK` | int | no | Max number of chunks retrieved |
| `chunkTopK` | int | no | Chunk-level top-K |
| `maxEntityTokens` | int | no | Token budget for entity context |
| `maxRelationTokens` | int | no | Token budget for relation context |
| `maxTotalTokens` | int | no | Overall token budget |
| `responseType` | string | no | Instruct the LLM on response format (e.g. `"markdown"`) |
| `enableRerank` | boolean | no | Enable re-ranking (default: true) |
| `onlyNeedContext` | boolean | no | Return retrieved context only, skip generation |
| `onlyNeedPrompt` | boolean | no | Return the assembled prompt only, skip generation |
| `includeReferences` | boolean | no | Include source entity references in response |
| `hlKeywords` | string[] | no | High-level keyword hints for retrieval |
| `llKeywords` | string[] | no | Low-level keyword hints for retrieval |
| `conversationHistory` | array | no | Prior turns for multi-turn conversations |
| `userPrompt` | string | no | Additional user instruction injected into the prompt |

**Response — 200 OK**
```json
{
  "answer": "The project uses PostgreSQL for vector storage and Neo4j for graph storage.",
  "contexts": [
    { "chunkId": "chunk-001", "content": "Storage architecture...", "score": 0.92 }
  ],
  "references": [
    { "entityName": "PostgreSQL", "entityType": "TECHNOLOGY", "score": 0.88 }
  ]
}
```

```bash
curl -X POST http://localhost:8080/query \
  -H "Content-Type: application/json" \
  -H "X-Workspace-Id: my-wiki" \
  -d '{"query": "How does authentication work?", "mode": "hybrid"}'
```

---

#### `POST /query/stream`

Same as `/query` but streams the response as Server-Sent Events. Use this for interactive UIs to show answers as they are generated.

**Produces:** `text/event-stream`

```bash
curl -X POST http://localhost:8080/query/stream \
  -H "Content-Type: application/json" \
  -H "X-Workspace-Id: my-wiki" \
  -H "Accept: text/event-stream" \
  -d '{"query": "How does authentication work?", "mode": "hybrid"}' \
  --no-buffer
```

The server sends a series of `data:` events followed by a terminal `event: done` event:
```
data: {"answer":"The ","contexts":[],"references":[]}
data: {"answer":"project ","contexts":[],"references":[]}
...
event: done
data: {}
```

---

### Graph management

The knowledge graph extracted during ingestion can be inspected and edited directly.

#### `POST /graph/entities`

Create an entity.

```json
{
  "name": "PostgreSQL",
  "type": "TECHNOLOGY",
  "description": "Open-source relational database used for vector storage",
  "aliases": ["Postgres", "PG"]
}
```

**Response — 200 OK** — returns the created entity.

---

#### `PUT /graph/entities`

Update an existing entity.

```json
{
  "entityName": "PostgreSQL",
  "newName": "PostgreSQL 16",
  "type": "TECHNOLOGY",
  "description": "Updated description",
  "aliases": ["Postgres"]
}
```

---

#### `POST /graph/entities/merge`

Merge multiple entities into one. Useful for deduplication (e.g. `"Postgres"` and `"PostgreSQL"` both pointing to the same node).

```json
{
  "sourceEntityNames": ["Postgres", "PG"],
  "targetEntityName": "PostgreSQL",
  "targetType": "TECHNOLOGY",
  "targetDescription": "Open-source relational database",
  "targetAliases": ["Postgres", "PG"]
}
```

---

#### `DELETE /graph/entities/{entityName}`

Delete an entity and all its relations. Returns 204.

```bash
curl -X DELETE "http://localhost:8080/graph/entities/PostgreSQL" \
  -H "X-Workspace-Id: my-wiki"
```

---

#### `POST /graph/relations`

Create a relation between two entities.

```json
{
  "sourceEntityName": "lightrag-wiki-sync",
  "targetEntityName": "PostgreSQL",
  "relationType": "STORES_DATA_IN",
  "description": "Wiki sync state is persisted in PostgreSQL",
  "weight": 1.0
}
```

---

#### `PUT /graph/relations`

Update an existing relation.

```json
{
  "sourceEntityName": "lightrag-wiki-sync",
  "targetEntityName": "PostgreSQL",
  "currentRelationType": "STORES_DATA_IN",
  "newRelationType": "USES",
  "description": "Updated description",
  "weight": 0.8
}
```

---

#### `DELETE /graph/relations`

Delete a relation between two entities. Returns 204.

```bash
curl -X DELETE "http://localhost:8080/graph/relations?sourceEntityName=lightrag-wiki-sync&targetEntityName=PostgreSQL" \
  -H "X-Workspace-Id: my-wiki"
```

---

### Health & monitoring

Spring Boot Actuator endpoints are enabled at `/actuator/*`.

```bash
# Health
curl http://localhost:8080/actuator/health

# Info
curl http://localhost:8080/actuator/info
```

---

## lightrag-wiki-sync (port 8090)

This service runs alongside the demo, clones a git-hosted wiki repository, and keeps LightRAG's index in sync automatically.

### `POST /sync/trigger`

Trigger an immediate sync without waiting for the next scheduled run. The call is **synchronous** — the response is only returned after the sync finishes.

**Response — 200 OK** (always 200; inspect the body to check for failures)

```json
{
  "filesUploaded": 12,
  "filesDeleted": 1,
  "filesFailed": 0,
  "filesSkipped": 0,
  "fromCommit": "abc1234",
  "toCommit": "def5678",
  "duration": "PT4.321S",
  "errors": []
}
```

| Field | Description |
|---|---|
| `filesUploaded` | Pages successfully uploaded to LightRAG |
| `filesDeleted` | Pages deleted from LightRAG (git DELETE) |
| `filesFailed` | Pages that could not be processed |
| `filesSkipped` | Pages skipped (no changes) |
| `fromCommit` | SHA of the last successfully synced commit (`null` on first sync) |
| `toCommit` | SHA of HEAD after this sync (`null` if there were failures) |
| `duration` | ISO-8601 duration of the sync run |
| `errors` | Per-file error messages when `filesFailed > 0` |

```bash
curl -X POST http://localhost:8090/sync/trigger
```

### Actuator

```bash
curl http://localhost:8090/actuator/health
```
