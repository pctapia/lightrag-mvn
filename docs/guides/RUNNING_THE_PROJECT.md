# Running the Project

This guide covers how to build, start, and query the LightRAG stack locally, and how to point it at a corporate AI endpoint instead of a local Ollama instance.

## Prerequisites

- Java 17+
- Maven 3.9+
- [Ollama](https://ollama.com) running locally (for local inference)
- Required Ollama models pulled:
  ```bash
  ollama pull llama3.2:3b
  ollama pull nomic-embed-text
  ```

---

## 1. Build

Rebuild all modules before the first run, or after changing `lightrag-core`:

```bash
mvn install -pl lightrag-core -DskipTests -q
mvn install -pl lightrag-spring-boot-demo -DskipTests -q
mvn install -pl lightrag-wiki-sync -DskipTests -q
```

---

## 2. Start the demo service (port 8080)

```bash
export LIGHTRAG_CHAT_MODEL=llama3.2:3b
export LIGHTRAG_CHAT_TIMEOUT=PT120S
export LIGHTRAG_QUERY_AUTOMATIC_KEYWORD_EXTRACTION=false
mvn spring-boot:run -pl lightrag-spring-boot-demo
```

Wait until the log shows the application is listening on port 8080 before proceeding.

---

## 3. Start the wiki-sync service (port 8090)

In a separate terminal:

```bash
mvn spring-boot:run -pl lightrag-wiki-sync
```

### Re-sync from scratch

The wiki-sync service stores a state file and document registry under `/tmp/lightrag-wiki-sync/`. Because the demo uses in-memory storage, its data is lost on every restart. Delete the state files to force a full re-upload after restarting the demo:

```bash
rm /tmp/lightrag-wiki-sync/.wiki-sync-state
rm /tmp/lightrag-wiki-sync/.wiki-doc-registry.json
```

---

## 4. Trigger a sync

```bash
curl -s -X POST http://localhost:8090/sync/trigger | jq .
```

Expected response:

```json
{
  "filesUploaded": 22,
  "filesDeleted": 0,
  "filesFailed": 0,
  "filesSkipped": 0,
  "success": true
}
```

---

## 5. Monitor document processing

Documents are processed asynchronously after upload. Poll until PROCESSED count stabilises:

```bash
curl -s "http://localhost:8080/documents/status?workspaceId=professional-programming" \
  | jq 'group_by(.status) | map({status: .[0].status, count: length})'
```

---

## 6. Query

```bash
curl -s -X POST http://localhost:8080/query \
  -H "Content-Type: application/json" \
  -H "X-Workspace-Id: professional-programming" \
  -d '{
    "query": "What are the main antipatterns to avoid?",
    "mode": "naive",
    "includeReferences": true
  }' | jq '{answer: .answer, refs: .references}'
```

Available query modes: `naive`, `local`, `global`, `hybrid`, `mix`, `multi-hop`, `bypass` (case-insensitive).

---

## Connecting to a Corporate AI Endpoint

The demo uses Ollama at `http://localhost:11434/v1/` by default. Both the chat and embedding models communicate over the OpenAI-compatible HTTP API, so pointing the service at a corporate endpoint requires only environment variables — no code changes.

### OpenAI-compatible models (Llama, GPT)

```bash
# Chat model
export LIGHTRAG_CHAT_BASE_URL=https://ai.your-company.com/v1/
export LIGHTRAG_CHAT_MODEL=gpt-4o          # exact model name the endpoint exposes
export LIGHTRAG_CHAT_API_KEY=your-api-key
export LIGHTRAG_CHAT_TIMEOUT=PT60S         # increase for corporate network latency

# Embedding model (can be a different endpoint)
export LIGHTRAG_EMBEDDING_BASE_URL=https://ai.your-company.com/v1/
export LIGHTRAG_EMBEDDING_MODEL=text-embedding-3-small
export LIGHTRAG_EMBEDDING_API_KEY=your-api-key

mvn spring-boot:run -pl lightrag-spring-boot-demo
```

### Splitting chat and embedding across endpoints

If the corporate endpoint does not serve an embedding model, keep Ollama for embeddings and point only the chat model at the corporate endpoint:

```bash
export LIGHTRAG_CHAT_BASE_URL=https://ai.your-company.com/v1/
export LIGHTRAG_CHAT_MODEL=gpt-4o
export LIGHTRAG_CHAT_API_KEY=your-api-key

# Embedding stays on local Ollama (default, no change needed)
```

### Two things to confirm with your IT/AI team

1. **Base URL format** — the client appends `/chat/completions` and `/embeddings` to `base-url`. The URL must end with `/v1/` (or the correct path prefix) so the final paths resolve correctly.
2. **Embedding model availability** — confirm the endpoint exposes an embedding model, and get its exact model name.

### Devstral (Mistral)

Devstral's native API is not OpenAI-compatible. If your corporate AI gateway wraps it with an OpenAI-compatible adapter, the same configuration above applies. If not, a custom `ChatModel` implementation would be required.
