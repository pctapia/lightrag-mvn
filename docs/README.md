# Documentation

This directory contains comprehensive documentation for the LightRAG Java project organized by topic.

## 📁 Documentation Structure

### [`/api`](./api)
REST API reference and usage guides
- **[API_REFERENCE.md](./api/API_REFERENCE.md)** - Complete REST API documentation, React integration examples

### [`/architecture`](./architecture)
System design, storage options, and architectural decisions
- **[STORAGE_ARCHITECTURE.md](./architecture/STORAGE_ARCHITECTURE.md)** - Database implementations (IN_MEMORY, PostgreSQL, MySQL+Milvus+Neo4j)
- **[DB_OPTION_EVALUATION.md](./architecture/DB_OPTION_EVALUATION.md)** - Storage comparison: Redis vs PostgreSQL vs Qdrant vs Milvus

### [`/guides`](./guides)
How-to guides and integration tutorials
- **[GITLAB_WIKI_SYNC_GUIDE.md](./guides/GITLAB_WIKI_SYNC_GUIDE.md)** - GitLab wiki synchronization using JGit (.md and .adoc files)

## 🚀 Quick Start

1. **Running the demo**: See main [README.md](../README.md)
2. **Using the API**: See [API_REFERENCE.md](./api/API_REFERENCE.md)
3. **Choosing a database**: See [DB_OPTION_EVALUATION.md](./architecture/DB_OPTION_EVALUATION.md)
4. **Syncing your wiki**: See [GITLAB_WIKI_SYNC_GUIDE.md](./guides/GITLAB_WIKI_SYNC_GUIDE.md)

## 📝 Contributing Documentation

When adding new documentation:
- **API docs** → `docs/api/`
- **Architecture/design docs** → `docs/architecture/`
- **How-to guides** → `docs/guides/`
- **Project-level docs** → Root directory (README.md, CONTRIBUTING.md, etc.)

---

For the main project README, see [../README.md](../README.md)
