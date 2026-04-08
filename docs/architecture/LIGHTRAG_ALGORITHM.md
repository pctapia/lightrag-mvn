# LightRAG Algorithm

## What is LightRAG?

LightRAG is a retrieval-augmented generation algorithm published in 2024 by researchers at the University of Hong Kong. The core insight is that standard RAG systems are **context-blind** — they retrieve isolated text chunks but miss the relationships between concepts across documents. LightRAG addresses this by building a **knowledge graph** during indexing and using it during retrieval.

---

## How standard RAG works

```
Document → chunks → embeddings → vector store
Query → embed → nearest-neighbour search → top-K chunks → LLM → answer
```

The LLM sees disconnected fragments. If the answer requires connecting facts from different documents, it usually fails.

---

## What LightRAG adds

During indexing, every chunk is also passed to an LLM which extracts **entities** (people, organizations, concepts, etc.) and **relations** between them. These form a knowledge graph stored alongside the vector index.

```
Document → chunks → embeddings → vector store
                 └→ LLM extraction → entities + relations → graph store

Query → multiple retrieval strategies → assembled context → LLM → answer
```

At query time, LightRAG offers several retrieval strategies that combine graph traversal with vector search:

| Mode | Strategy |
|---|---|
| `naive` | Pure vector search — same as standard RAG |
| `local` | Finds entities similar to the query, expands through their immediate graph neighbourhood |
| `global` | Finds relations relevant to the query across the whole graph |
| `hybrid` | Merges local and global graph results |
| `mix` | Graph retrieval combined with vector retrieval |
| `multi-hop` | Follows chains of relations (A→B→C) to answer questions requiring multi-step reasoning |

The result is that the LLM receives **structured, interconnected context** rather than isolated chunks — which dramatically improves answers to questions like "How does X relate to Y?" or "What connects A to B?"

---

## How this differs from LangChain and Spring AI

LangChain and Spring AI are **frameworks** — they provide building blocks (chains, agents, tools, memory, model connectors) that you assemble to build your own RAG pipeline. LightRAG is an **algorithm** — a specific, opinionated pipeline for knowledge-graph-enhanced RAG.

| | LightRAG | LangChain / Spring AI |
|---|---|---|
| **Nature** | Algorithm (specific pipeline) | Framework (building blocks) |
| **What you build** | Use it as-is; configure models and storage | Assemble your own pipeline from components |
| **Knowledge graph** | Built-in, central to the design | Optional, bring your own |
| **Retrieval** | 6 modes combining graph + vector | You implement retrieval logic |
| **Entity extraction** | Automatic via LLM during indexing | Not included; you wire it in |
| **Abstraction level** | High — you call `ingest()` and `query()` | Low to medium — you compose chains/steps |
| **Flexibility** | Less flexible; opinionated about the pipeline | Highly flexible; supports any architecture |
| **Use case fit** | Knowledge-dense corpora with complex relationships | Any LLM application (chatbots, agents, pipelines) |

### Analogy

Think of it like web frameworks: LangChain/Spring AI are like **Express or Spring MVC** — general-purpose, you decide the structure. LightRAG is like **a pre-built CMS** — optimized for one specific purpose, works very well out of the box for that purpose, but you don't redesign its internals.

---

## When to use each

**LightRAG** is the right choice when:
- Your data is a corpus of interconnected documents (wikis, documentation, research papers, legal texts)
- Users ask relational questions spanning multiple sources
- You want knowledge graph construction without building it yourself

**LangChain or Spring AI** is the right choice when:
- You need a custom pipeline: agents that call tools, multi-step workflows, or chatbots with memory
- You need integration with many different data sources and APIs
- You want full control over every step of the retrieval and generation process

---

## This implementation

This Java project implements the LightRAG algorithm directly — no LangChain dependency, no Spring AI dependency — communicating with LLMs purely via the OpenAI-compatible HTTP API. See [AI_FRAMEWORKS.md](AI_FRAMEWORKS.md) for the full list of libraries used.
