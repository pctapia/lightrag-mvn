# AI Frameworks and Libraries

LightRAG Java implements the LightRAG algorithm directly without depending on a higher-level AI framework such as LangChain or Spring AI. All LLM and embedding communication happens over the OpenAI-compatible HTTP API, making the system model-agnostic.

## LLM and Embedding Models

### OpenAI-Compatible API Client

Both the chat and embedding models are implemented as thin HTTP clients against the OpenAI-compatible REST API (`/chat/completions` and `/embeddings`).

| Component | Class | Purpose |
|---|---|---|
| Chat model | `OpenAiCompatibleChatModel` | Text generation, entity extraction, query answering |
| Embedding model | `OpenAiCompatibleEmbeddingModel` | Vector embedding for chunks and queries |

**Supported providers** (anything with an OpenAI-compatible endpoint):

- **Ollama** (default) — local inference; default models: `llama3.2:3b` (chat), `nomic-embed-text` (embeddings)
- **OpenAI** — `gpt-4o-mini`, `text-embedding-3-small`, etc.
- **Any compatible API** — Mistral, Together AI, vLLM, LM Studio, etc.

Configuration is done via environment variables or `application.yml`:

```yaml
lightrag:
  chat:
    base-url: ${LIGHTRAG_CHAT_BASE_URL:http://localhost:11434/v1/}
    model:    ${LIGHTRAG_CHAT_MODEL:llama3.2:3b}
    api-key:  ${LIGHTRAG_CHAT_API_KEY:dummy}
    timeout:  ${LIGHTRAG_CHAT_TIMEOUT:PT30S}
  embedding:
    base-url: ${LIGHTRAG_EMBEDDING_BASE_URL:http://localhost:11434/v1/}
    model:    ${LIGHTRAG_EMBEDDING_MODEL:nomic-embed-text}
    api-key:  ${LIGHTRAG_EMBEDDING_API_KEY:dummy}
```

### Optional Reranking

A `RerankModel` interface is available for result reranking. When no rerank model bean is registered the step is skipped transparently.

---

## Knowledge Extraction

`KnowledgeExtractor` uses the chat model to extract structured entities and relations from each document chunk. The LLM is prompted to return JSON conforming to a fixed schema; the extractor handles common model quirks (markdown code fences, prose wrappers, missing optional fields).

**Configurable parameters:**

| Parameter | Default | Description |
|---|---|---|
| Entity types | Person, Organization, Location, … | Types the LLM should classify entities into |
| Language | English | Language for entity descriptions |
| Max gleaning | 1 | Extra extraction passes per chunk |
| Max input tokens | 20 480 | Token budget per extraction call |

---

## Document Processing

### Parsing

| Library | Version | Purpose |
|---|---|---|
| Apache Tika | 3.2.1 | Parse PDF, DOCX, PPTX, HTML, and other formats |
| Mineru | — | Optional advanced PDF parsing (API or self-hosted) |

### Chunking Strategies

| Strategy | Class | Description |
|---|---|---|
| Fixed window | `FixedWindowChunker` | Sliding window of fixed token size with configurable overlap |
| Regex | `RegexChunker` | Splits on semantic boundaries (headings, paragraphs) |
| Smart | `SmartChunker` | Embedding-aware; merges or splits based on semantic similarity |
| Parent-child | `ParentChildChunkBuilder` | Hierarchical chunks; retrieval on child, context from parent |

Default: fixed window (`window-size=1000`, `overlap=100`), configurable via `LIGHTRAG_INDEXING_CHUNK_WINDOW_SIZE`.

---

## Vector Storage

Vector search underpins all query modes. Three backends are supported:

| Backend | Storage type key | Notes |
|---|---|---|
| In-memory | `in-memory` | Default; data lost on restart |
| PostgreSQL + pgvector | `postgres` | Durable; requires pgvector extension |
| Milvus | `mysql-milvus-neo4j` | Distributed vector database |

See [STORAGE_ARCHITECTURE.md](STORAGE_ARCHITECTURE.md) for full setup instructions.

---

## Knowledge Graph

Entities and relations extracted by `KnowledgeExtractor` are stored in a graph store and used by graph-based query modes.

| Backend | Storage type key | Notes |
|---|---|---|
| In-memory | `in-memory` | Default; ephemeral |
| Neo4j | `postgres-neo4j` or `mysql-milvus-neo4j` | Persistent graph database |
| PostgreSQL | `postgres` | Relations stored in relational tables |

---

## Query Modes

Six retrieval strategies are available, selectable per request via the `mode` field (case-insensitive):

| Mode | Strategy | Description |
|---|---|---|
| `naive` | Vector only | Semantic similarity search on chunk embeddings |
| `local` | Graph — local | Entity neighbourhood traversal |
| `global` | Graph — global | Full graph context aggregation |
| `hybrid` | Graph — local + global | Combines local and global graph results |
| `mix` | Graph + vector | Graph traversal combined with vector search |
| `multi-hop` | Multi-step graph | Iterative path finding for complex questions |
| `bypass` | None | Passes the query directly to the LLM with no retrieval |

---

## Evaluation

RAGAS (Retrieval-Augmented Generation Assessment) integration is available for offline evaluation of retrieval quality.

| Class | Purpose |
|---|---|
| `RagasEvaluationService` | Single-query evaluation |
| `RagasBatchEvaluationService` | Batch evaluation from a dataset |

---

## External Dependencies Summary

| Library | Version | Role |
|---|---|---|
| OkHttp | 4.12.0 | HTTP client for LLM / embedding API calls |
| Jackson | 2.19.1 | JSON serialisation for LLM responses and storage |
| Apache Tika | 3.2.1 | Document format parsing |
| Neo4j driver | 5.28.5 | Graph database access |
| PostgreSQL JDBC | 42.7.10 | Relational and vector storage |
| pgvector JDBC | 0.1.6 | Vector type support for PostgreSQL |
| Milvus SDK | 2.6.13 | Distributed vector database |
| MySQL JDBC | 8.4.0 | Alternative relational storage |
| HikariCP | 7.0.2 | JDBC connection pooling |
