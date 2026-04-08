# Documentation

This directory contains comprehensive documentation for the LightRAG Java project organized by topic.

## 📁 Documentation Structure

### [`/api`](./api)
REST API reference and usage guides
- **[API_REFERENCE.md](./api/API_REFERENCE.md)** - Complete REST API documentation for both services (all endpoints, request/response shapes, curl examples)

### [`/architecture`](./architecture)
System design, storage options, and architectural decisions
- **[AI_FRAMEWORKS.md](./architecture/AI_FRAMEWORKS.md)** - AI libraries, LLM/embedding model integration, query modes, and chunking strategies
- **[STORAGE_ARCHITECTURE.md](./architecture/STORAGE_ARCHITECTURE.md)** - Database implementations (IN_MEMORY, PostgreSQL, MySQL+Milvus+Neo4j)
- **[DB_OPTION_EVALUATION.md](./architecture/DB_OPTION_EVALUATION.md)** - Storage comparison: Redis vs PostgreSQL vs Qdrant vs Milvus
- **[WIKI_SYNC_DELETION.md](./architecture/WIKI_SYNC_DELETION.md)** - Ghost-document problem and deletion design for the wiki sync module

### [`/guides`](./guides)
How-to guides and integration tutorials
- **[RUNNING_THE_PROJECT.md](./guides/RUNNING_THE_PROJECT.md)** - Build, start, and query the stack locally; connect to a corporate AI endpoint
- **[WIKI_SYNC_USAGE_GUIDE.md](./guides/WIKI_SYNC_USAGE_GUIDE.md)** - End-to-end guide: connect a GitHub/GitLab wiki, run sync, query indexed content
- **[GITLAB_WIKI_SYNC_GUIDE.md](./guides/GITLAB_WIKI_SYNC_GUIDE.md)** - Original design guide covering architecture options and rationale

## 🚀 Quick Start

1. **Running the demo**: See main [README.md](../README.md)
2. **Using the API**: See [API_REFERENCE.md](./api/API_REFERENCE.md)
3. **Choosing a database**: See [DB_OPTION_EVALUATION.md](./architecture/DB_OPTION_EVALUATION.md)
4. **Syncing your wiki**: See [WIKI_SYNC_USAGE_GUIDE.md](./guides/WIKI_SYNC_USAGE_GUIDE.md)

## 📝 Contributing Documentation

When adding new documentation:
- **API docs** → `docs/api/`
- **Architecture/design docs** → `docs/architecture/`
- **How-to guides** → `docs/guides/`
- **Project-level docs** → Root directory (README.md, CONTRIBUTING.md, etc.)

---

For the main project README, see [../README.md](../README.md)
