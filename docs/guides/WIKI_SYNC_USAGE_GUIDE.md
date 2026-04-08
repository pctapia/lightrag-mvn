# Wiki Sync — Practical Usage Guide

This guide walks through the full lifecycle: connect a GitHub or GitLab wiki to LightRAG, run the initial sync, and query the indexed content.

---

## How it works

```
GitHub / GitLab wiki
  (git repository)
        │
        │  git clone / pull  (JGit, no git binary required)
        ▼
  local clone directory
        │
        │  diff between last-synced commit and HEAD
        │  upload new/changed pages → POST /documents/upload
        │  delete removed pages    → DELETE /documents/{id}
        ▼
  lightrag-spring-boot-demo  (port 8080)
        │
        │  extracts entities, relations, chunk vectors
        ▼
  PostgreSQL + Neo4j  (or in-memory for dev)
        │
        ▼
  POST /query  →  semantic answers over your wiki
```

The sync service runs on port **8090** and calls the demo service on port **8080**. The two services are independent Spring Boot applications and can be deployed on separate hosts.

---

## Prerequisites

- Java 17+
- Maven 3.9+
- A running instance of `lightrag-spring-boot-demo`
- A GitHub or GitLab personal access token with **read** access to the wiki repository
- The wiki repository must have at least one commit (empty repos are not supported)

---

## Step 1 — Start the LightRAG demo

```bash
mvn spring-boot:run -pl lightrag-spring-boot-demo
```

Verify it is healthy:
```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

---

## Step 2 — Configure the wiki sync service

Edit `lightrag-wiki-sync/src/main/resources/application.yml`, or supply environment variables.

### GitHub wiki

GitHub wiki repositories follow the URL pattern `https://github.com/<owner>/<repo>.wiki.git`.

```yaml
wiki:
  sync:
    remote-url: https://github.com/my-org           # base URL of the git host
    project-path: my-repo                           # owner/repo or just repo name
    access-token: ${WIKI_ACCESS_TOKEN}              # GitHub PAT with repo scope
    local-clone-path: /tmp/my-repo-wiki-clone
    lightrag-api-url: http://localhost:8080
    workspace-id: my-repo-wiki
    sync-on-startup: true                           # run a full sync on first start
    schedule: "0 0 2 * * ?"                        # nightly at 02:00
```

This constructs the clone URL as:
```
https://github.com/my-org/my-repo.wiki.git
```

> **Note:** Both GitHub and GitLab are supported out of the box. The `credentials()` method in `WikiSyncer` uses `x-access-token` as the PAT username, which is accepted by both platforms. For public repositories, leave `access-token` blank and no authentication is sent.

### GitLab wiki

```yaml
wiki:
  sync:
    remote-url: https://gitlab.example.com
    project-path: mygroup/myproject
    access-token: ${WIKI_ACCESS_TOKEN}              # GitLab PAT with read_repository scope
    local-clone-path: /tmp/myproject-wiki-clone
    lightrag-api-url: http://localhost:8080
    workspace-id: myproject-wiki
    sync-on-startup: true
    schedule: "0 0 2 * * ?"
```

### Environment variable reference

```bash
export WIKI_REMOTE_URL=https://github.com/my-org
export WIKI_PROJECT_PATH=my-repo
export WIKI_ACCESS_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxx
export LIGHTRAG_API_URL=http://localhost:8080
export LIGHTRAG_WORKSPACE_ID=my-repo-wiki
export WIKI_SYNC_ON_STARTUP=true
```

---

## Step 3 — Start the sync service

```bash
mvn spring-boot:run -pl lightrag-wiki-sync
```

With `sync-on-startup: true`, the first full sync runs immediately on startup. You will see log output like:

```
Cloning wiki repo https://github.com/my-org/my-repo.wiki.git → /tmp/my-repo-wiki-clone
Clone complete
No sync state found; performing full sync…
Full sync: 47 wiki files found
Uploaded: Home.md (docId=home-3a4b5c6d7e8f)
Uploaded: Installation.md (docId=installation-1a2b3c4d5e6f)
...
Wiki sync complete — uploaded=47, deleted=0, failed=0, duration=12340ms
```

---

## Step 4 — Query the indexed wiki

Once the sync finishes, query through the demo service on port 8080.

### Simple question

```bash
curl -X POST http://localhost:8080/query \
  -H "Content-Type: application/json" \
  -H "X-Workspace-Id: my-repo-wiki" \
  -d '{
    "query": "How do I install this project?",
    "mode": "hybrid"
  }'
```

### Keyword-guided search

```bash
curl -X POST http://localhost:8080/query \
  -H "Content-Type: application/json" \
  -H "X-Workspace-Id: my-repo-wiki" \
  -d '{
    "query": "What databases are supported?",
    "mode": "hybrid",
    "hlKeywords": ["database", "storage"],
    "llKeywords": ["PostgreSQL", "MySQL", "MongoDB"]
  }'
```

### Return source context alongside the answer

```bash
curl -X POST http://localhost:8080/query \
  -H "Content-Type: application/json" \
  -H "X-Workspace-Id: my-repo-wiki" \
  -d '{
    "query": "What is the authentication flow?",
    "mode": "hybrid",
    "includeReferences": true
  }'
```

### Multi-turn conversation

```bash
curl -X POST http://localhost:8080/query \
  -H "Content-Type: application/json" \
  -H "X-Workspace-Id: my-repo-wiki" \
  -d '{
    "query": "Does it support OAuth2?",
    "mode": "hybrid",
    "conversationHistory": [
      { "role": "user",      "content": "What authentication methods are supported?" },
      { "role": "assistant", "content": "The project supports basic auth, API keys, and SSO." }
    ]
  }'
```

### Streaming answer (for UIs)

```bash
curl -X POST http://localhost:8080/query/stream \
  -H "Content-Type: application/json" \
  -H "X-Workspace-Id: my-repo-wiki" \
  -H "Accept: text/event-stream" \
  -d '{"query": "Summarise the architecture", "mode": "hybrid"}' \
  --no-buffer
```

---

## Step 5 — Trigger a manual sync

After pushing changes to the wiki, you can sync immediately without waiting for the cron schedule:

```bash
curl -X POST http://localhost:8090/sync/trigger
```

Response:
```json
{
  "filesUploaded": 2,
  "filesDeleted": 0,
  "filesFailed": 0,
  "filesSkipped": 0,
  "fromCommit": "abc1234",
  "toCommit": "def5678",
  "duration": "PT1.23S",
  "errors": []
}
```

Check for failures:
```bash
curl -X POST http://localhost:8090/sync/trigger | jq '.filesFailed, .errors'
```

---

## Common scenarios

### Scenario — Multiple teams, isolated workspaces

Run one sync service instance per team by changing `workspace-id` and `local-clone-path`:

```yaml
# Team A
workspace-id: team-a-wiki
local-clone-path: /tmp/team-a-wiki-clone
project-path: team-a/documentation

# Team B (separate instance)
workspace-id: team-b-wiki
local-clone-path: /tmp/team-b-wiki-clone
project-path: team-b/docs
```

Each workspace's documents are isolated — queries against `team-a-wiki` never see `team-b-wiki` content.

---

### Scenario — Re-seed a workspace from scratch

Delete all documents from a workspace via the demo API, then trigger a fresh full sync.

```bash
# 1. Delete all documents in the workspace (repeat until empty)
#    No bulk-delete endpoint exists; the sync service handles this automatically
#    on the next full sync. To force a full re-sync, delete the state file:

rm /tmp/my-repo-wiki-clone/.wiki-sync-state
rm /tmp/my-repo-wiki-clone/.wiki-doc-registry.json

# 2. Trigger sync — no state file means full sync
curl -X POST http://localhost:8090/sync/trigger
```

---

### Scenario — Limit which file types are synced

By default only `.md` and `.adoc` files are synced. Override in `application.yml`:

```yaml
wiki:
  sync:
    file-extensions:
      - .md
      - .adoc
      - .txt
      - .rst
```

---

### Scenario — Check what was indexed

List all document IDs and their status via the demo API:

```bash
curl http://localhost:8080/documents/status \
  -H "X-Workspace-Id: my-repo-wiki"
```

---

### Scenario — Investigate a failed sync

```bash
# 1. Trigger and capture full response
curl -X POST http://localhost:8090/sync/trigger | jq .
```

```json
{
  "filesUploaded": 10,
  "filesDeleted": 0,
  "filesFailed": 2,
  "fromCommit": "abc1234",
  "toCommit": null,
  "errors": [
    "Large-Page.md: Upload failed — HTTP 413 Request Entity Too Large",
    "Binary.md: File not found on disk: /tmp/clone/Binary.md"
  ]
}
```

`toCommit` is `null` when `filesFailed > 0` — the sync state is not advanced, so the next run will retry the same commits.

---

## Observability

Both services expose Spring Boot Actuator:

```bash
# Demo service
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/metrics

# Sync service
curl http://localhost:8090/actuator/health
```

Key log lines from the sync service:

| Log level | Message | Meaning |
|---|---|---|
| INFO | `Cloning wiki repo … → …` | First run, cloning the repository |
| INFO | `Full sync: N wiki files found` | Fresh sync processing all files |
| INFO | `Incremental sync abc1234 → def5678…` | Processing only changed files |
| INFO | `No changes since last sync (HEAD: abc1234)` | Wiki has not changed |
| INFO | `Uploaded: Home.md (docId=…)` | File successfully indexed |
| INFO | `Deleted: About.md (docId=…)` | File removed from LightRAG |
| WARN | `Sync finished with N failure(s); state not advanced` | Partial failure, will retry |
| ERROR | `Failed to upload X.md: …` | Individual file failure |

---

## State files

The sync service writes two files inside the clone directory:

| File | Purpose |
|---|---|
| `.wiki-sync-state` | SHA of the last successfully synced commit |
| `.wiki-doc-registry.json` | Map of `git file path → LightRAG document ID` |

These files are never committed to git (they start with `.` and live outside any git-tracked path). Deleting them forces a full re-sync on the next run.
