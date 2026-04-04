# Storage Architecture - Database Implementations

## Overview

The LightRAG application uses a **modular storage architecture** that separates different types of data into specialized stores. When you upload or ingest documents via the API, the data is processed and distributed across multiple database stores based on the configured storage provider.

## Storage Components

Every storage provider implements six core stores:

1. **DocumentStore** - Stores original document metadata and content
2. **ChunkStore** - Stores text chunks created from documents
3. **VectorStore** - Stores embeddings (vector representations) for semantic search
4. **GraphStore** - Stores knowledge graph (entities and relationships)
5. **DocumentStatusStore** - Tracks document processing status
6. **SnapshotStore** - Manages system snapshots for backup/restore

## Available Storage Providers

### 1. IN_MEMORY (Default)

**Configuration:**
```yaml
lightrag:
  storage:
    type: in-memory
```

**Implementation:**
- **ALL stores**: In-memory data structures (Maps, Lists)
- **Best for**: Development, testing, demos
- **Limitations**: Data is lost when application restarts
- **Performance**: Fastest (no network/disk I/O)

**Database stack:**
- None (everything in JVM memory)

---

### 2. POSTGRES

**Configuration:**
```yaml
lightrag:
  storage:
    type: postgres
  postgres:
    jdbc-url: jdbc:postgresql://localhost:5432/lightrag
    username: postgres
    password: password
```

**Implementation:**
- **DocumentStore**: PostgreSQL table (`rag_documents`)
- **ChunkStore**: PostgreSQL table (`rag_chunks`)
- **VectorStore**: PostgreSQL with pgvector extension (`rag_vectors`)
- **GraphStore**: PostgreSQL tables (`rag_entities`, `rag_relations`)
- **DocumentStatusStore**: PostgreSQL table (`rag_document_status`)
- **SnapshotStore**: File system (JSON files)

**Database stack:**
- **PostgreSQL** with **pgvector** extension

**Features:**
- Single database for all data
- ACID transactions
- Uses pgvector for similarity search
- Workspace isolation via `workspace_id` column
- Advisory locks for concurrency control

**Schema:**
```sql
-- Documents table
CREATE TABLE rag_documents (
    workspace_id VARCHAR,
    id VARCHAR PRIMARY KEY,
    title VARCHAR,
    content TEXT,
    metadata JSONB
);

-- Chunks table
CREATE TABLE rag_chunks (
    workspace_id VARCHAR,
    id VARCHAR PRIMARY KEY,
    document_id VARCHAR,
    content TEXT,
    tokens INTEGER,
    start_index INTEGER,
    metadata JSONB
);

-- Vectors table (with pgvector)
CREATE TABLE rag_vectors (
    workspace_id VARCHAR,
    namespace VARCHAR,
    id VARCHAR PRIMARY KEY,
    vector vector(1536),  -- pgvector type
    metadata JSONB
);

-- Graph: Entities table
CREATE TABLE rag_entities (
    workspace_id VARCHAR,
    id VARCHAR PRIMARY KEY,
    name VARCHAR,
    type VARCHAR,
    description TEXT,
    metadata JSONB
);

-- Graph: Relations table
CREATE TABLE rag_relations (
    workspace_id VARCHAR,
    id VARCHAR PRIMARY KEY,
    source_id VARCHAR,
    target_id VARCHAR,
    type VARCHAR,
    description TEXT,
    weight DOUBLE PRECISION
);
```

---

### 3. POSTGRES_NEO4J

**Configuration:**
```yaml
lightrag:
  storage:
    type: postgres-neo4j
  postgres:
    jdbc-url: jdbc:postgresql://localhost:5432/lightrag
    username: postgres
    password: password
  neo4j:
    uri: bolt://localhost:7687
    username: neo4j
    password: password
```

**Implementation:**
- **DocumentStore**: PostgreSQL (`rag_documents`)
- **ChunkStore**: PostgreSQL (`rag_chunks`)
- **VectorStore**: PostgreSQL with pgvector (`rag_vectors`)
- **GraphStore**: **Neo4j** (graph database)
- **DocumentStatusStore**: PostgreSQL (`rag_document_status`)
- **SnapshotStore**: File system + Neo4j snapshots

**Database stack:**
- **PostgreSQL** with **pgvector** (documents, chunks, vectors)
- **Neo4j** (knowledge graph)

**Why use Neo4j for the graph?**
- Optimized for graph queries and traversals
- Cypher query language for complex graph patterns
- Better performance for multi-hop relationships
- Native graph storage format

**Neo4j Schema:**
```cypher
// Entity nodes
CREATE (e:Entity {
    id: 'entity-1',
    name: 'Alice',
    type: 'Person',
    description: 'Software engineer',
    workspaceId: 'default'
})

// Relationship edges
CREATE (e1:Entity)-[:WORKS_WITH {
    id: 'rel-1',
    type: 'WORKS_WITH',
    description: 'Collaborates on project',
    weight: 0.95,
    workspaceId: 'default'
}]->(e2:Entity)
```

---

### 4. MYSQL_MILVUS_NEO4J

**Configuration:**
```yaml
lightrag:
  storage:
    type: mysql-milvus-neo4j
  mysql:
    jdbc-url: jdbc:mysql://localhost:3306/lightrag
    username: root
    password: password
  milvus:
    uri: http://localhost:19530
    token: root:Milvus
    vector-dimensions: 1536
  neo4j:
    uri: bolt://localhost:7687
    username: neo4j
    password: password
```

**Implementation:**
- **DocumentStore**: MySQL (`rag_documents`)
- **ChunkStore**: MySQL (`rag_chunks`)
- **VectorStore**: **Milvus** (vector database)
- **GraphStore**: **Neo4j** (graph database)
- **DocumentStatusStore**: MySQL (`rag_document_status`)
- **SnapshotStore**: File system + Milvus + Neo4j snapshots

**Database stack:**
- **MySQL** (documents, chunks, status)
- **Milvus** (vector embeddings)
- **Neo4j** (knowledge graph)

**Why use Milvus for vectors?**
- Purpose-built vector database
- Highly scalable (billions of vectors)
- Advanced indexing (IVF, HNSW, DiskANN)
- GPU acceleration support
- Hybrid search (vector + keyword)
- Better performance for large-scale vector operations

**Milvus Collections:**
```python
# Each workspace has collections for:
- rag_{workspace}_chunks      # Chunk embeddings
- rag_{workspace}_entities    # Entity embeddings
- rag_{workspace}_relations   # Relation embeddings

# Schema for chunks collection
{
    "id": "chunk-1",
    "vector": [0.1, 0.2, ...],  # 1536 dimensions
    "content": "Full chunk text",
    "documentId": "doc-1",
    "metadata": {...}
}
```

---

## Document Upload & Ingest Flow

When you call the upload or ingest API, here's what happens:

### Upload Flow (`POST /documents/upload`)

1. **File Reception**
   - Multipart files received by Spring Boot controller
   - Files temporarily stored in memory/disk

2. **Document Extraction**
   - Apache Tika parses files (PDF, DOCX, TXT, etc.)
   - Extracts text content and metadata
   - Creates Document objects

3. **Storage in DocumentStore**
   - Document metadata saved to DocumentStore (PostgreSQL/MySQL)
   - Full content stored with document

4. **Async Job Creation**
   - Job ID returned to client immediately
   - Document processing queued for background execution

### Ingest Flow (Background Processing)

1. **Chunking**
   - Documents split into smaller chunks (default: 1000 tokens, 100 overlap)
   - SmartChunker preserves structure (headings, lists, tables)
   - Chunks stored in **ChunkStore**

2. **Embedding Generation**
   - Each chunk sent to embedding model (e.g., nomic-embed-text)
   - Vector embeddings (1536 dimensions) generated
   - Vectors stored in **VectorStore** (PostgreSQL/Milvus)

3. **Entity & Relationship Extraction**
   - LLM analyzes chunks to extract entities (Person, Organization, Location, etc.)
   - Identifies relationships between entities
   - Multiple gleaning passes for accuracy

4. **Graph Construction**
   - Entities and relationships stored in **GraphStore** (PostgreSQL/Neo4j)
   - Graph structure built for knowledge traversal

5. **Status Updates**
   - Document status tracked in **DocumentStatusStore**
   - States: PENDING → PROCESSING → PROCESSED / FAILED

### Storage Provider Comparison Matrix

| Component | IN_MEMORY | POSTGRES | POSTGRES_NEO4J | MYSQL_MILVUS_NEO4J |
|-----------|-----------|----------|----------------|---------------------|
| Documents | Memory | PostgreSQL | PostgreSQL | MySQL |
| Chunks | Memory | PostgreSQL | PostgreSQL | MySQL |
| Vectors | Memory | PostgreSQL (pgvector) | PostgreSQL (pgvector) | Milvus |
| Graph | Memory | PostgreSQL | Neo4j | Neo4j |
| Status | Memory | PostgreSQL | PostgreSQL | MySQL |
| Snapshots | Memory | File System | File System + Neo4j | File System + Milvus + Neo4j |

## Query Flow

When you query via `POST /query`:

1. **Keyword Extraction**
   - LLM extracts keywords from query (if enabled)

2. **Vector Search**
   - Query embedded to vector (1536 dimensions)
   - Similarity search in VectorStore
   - Top-K most relevant chunks retrieved

3. **Graph Search** (if mode includes graph)
   - Entities related to query keywords found in GraphStore
   - Graph traversal to find connected entities/relationships
   - Community detection for context

4. **Hybrid Ranking**
   - Combines vector similarity scores with graph relevance
   - Re-ranking based on multiple signals

5. **Answer Generation**
   - Retrieved contexts sent to LLM
   - Final answer generated with citations

## Choosing a Storage Provider

### Use **IN_MEMORY** when:
- Developing/testing locally
- Running demos
- Data loss on restart is acceptable
- Small dataset (< 10,000 documents)

### Use **POSTGRES** when:
- Single database simplifies operations
- Moderate scale (< 1M vectors)
- Transactional consistency is critical
- Limited infrastructure (one database server)

### Use **POSTGRES_NEO4J** when:
- Complex graph queries are important
- Need to traverse multi-hop relationships
- Graph performance is critical
- Still want simple vector search (< 1M vectors)

### Use **MYSQL_MILVUS_NEO4J** when:
- Large-scale vector search (millions+ of vectors)
- Need high-performance similarity search
- Multiple workspaces with isolation
- Production deployment with specialized databases
- GPU acceleration for vectors desired

## Configuration Examples

### Development (IN_MEMORY)
```yaml
lightrag:
  storage:
    type: in-memory
```

### Simple Production (POSTGRES)
```yaml
lightrag:
  storage:
    type: postgres
  postgres:
    jdbc-url: jdbc:postgresql://db.example.com:5432/lightrag
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    table-prefix: rag_
    pool-size: 10
```

### Advanced Production (MYSQL_MILVUS_NEO4J)
```yaml
lightrag:
  storage:
    type: mysql-milvus-neo4j
  mysql:
    jdbc-url: jdbc:mysql://mysql.example.com:3306/lightrag
    username: ${MYSQL_USER}
    password: ${MYSQL_PASSWORD}
    table-prefix: rag_
  milvus:
    uri: https://milvus.example.com:19530
    token: ${MILVUS_TOKEN}
    database: lightrag
    collection-prefix: rag_
    vector-dimensions: 1536
    hybrid-ranker: rrf
  neo4j:
    uri: bolt://neo4j.example.com:7687
    username: ${NEO4J_USER}
    password: ${NEO4J_PASSWORD}
    database: lightrag
```

## Required Database Extensions

### PostgreSQL
```sql
-- Install pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;
```

### Milvus
- No special setup required
- Supports multiple index types: IVF_FLAT, HNSW, DiskANN
- Automatically creates collections on first use

### Neo4j
- No special extensions required
- Uses standard Cypher query language
- Workspace isolation via node properties

## Performance Characteristics

| Operation | IN_MEMORY | POSTGRES | POSTGRES_NEO4J | MYSQL_MILVUS_NEO4J |
|-----------|-----------|----------|----------------|---------------------|
| Document ingest | Fastest | Fast | Fast | Fast |
| Vector search (10K vectors) | Fastest | Fast | Fast | Fastest |
| Vector search (1M+ vectors) | N/A | Slow | Slow | Very Fast |
| Graph traversal (2 hops) | Fast | Slow | Very Fast | Very Fast |
| Graph traversal (4+ hops) | Fast | Very Slow | Fast | Fast |
| Concurrent writes | Good | Good | Good | Excellent |
| Workspace isolation | Perfect | Good | Good | Excellent |

## Additional Resources

- PostgreSQL pgvector documentation: https://github.com/pgvector/pgvector
- Milvus documentation: https://milvus.io/docs
- Neo4j documentation: https://neo4j.com/docs/
