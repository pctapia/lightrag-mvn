# LightRAG Spring Boot Demo - API Reference

## Overview

The LightRAG Spring Boot Demo is a **backend REST API application** designed to be accessed programmatically. It does **not** provide a web-based user interface.

## What This Application Provides

### REST API Endpoints

The application exposes the following API endpoints:

#### Document Management
- `POST /documents/upload` - Upload files for ingestion
- `POST /documents/ingest` - Ingest structured documents
- `GET /documents/status/{documentId}` - Check document processing status
- `DELETE /documents/{documentId}` - Delete a document

#### Query Endpoints
- `POST /query` - Query the RAG system (buffered response)
- `POST /query/stream` - Query with streaming response (Server-Sent Events)

#### Graph Management
- `GET /graph/entities` - Retrieve graph entities
- Additional graph endpoints for entity and relationship management

#### Monitoring & Health
- `GET /actuator/health` - Application health check
- `GET /actuator/info` - Application information
- Other Spring Boot Actuator endpoints under `/actuator/*`

### Workspace Support

All endpoints support multi-workspace isolation via the `X-Workspace-Id` header. When omitted, the request uses the default workspace.

## What This Application Does NOT Have

- ❌ No HTML pages or web interface
- ❌ No static resources (CSS, JavaScript, images)
- ❌ No UI framework (Thymeleaf, React, Vue, Angular)
- ❌ No Swagger UI or OpenAPI documentation interface
- ❌ No interactive web console

## How to Use the API

### Starting the Application

```bash
mvn spring-boot:run -pl lightrag-spring-boot-demo
```

The application starts on `http://localhost:8080` by default.

### Accessing Endpoints

#### Using curl

**Health Check:**
```bash
curl http://127.0.0.1:8080/actuator/health
```

**Upload Documents:**
```bash
curl -X POST http://127.0.0.1:8080/documents/upload \
  -H 'X-Workspace-Id: team-a' \
  -F 'files=@notes.md;type=text/markdown' \
  -F 'files=@facts.txt;type=text/plain'
```

**Ingest Structured Documents:**
```bash
curl -X POST http://127.0.0.1:8080/documents/ingest \
  -H 'Content-Type: application/json' \
  -H 'X-Workspace-Id: team-a' \
  -d '{
    "documents": [
      {
        "id": "doc-1",
        "title": "Title",
        "content": "Alice works with Bob"
      }
    ]
  }'
```

**Query the RAG System:**
```bash
curl -X POST http://127.0.0.1:8080/query \
  -H 'Content-Type: application/json' \
  -H 'X-Workspace-Id: team-a' \
  -d '{
    "query": "Who does Alice work with?",
    "mode": "hybrid"
  }'
```

#### Using Other HTTP Clients

- **Postman**: Import the API endpoints and test interactively
- **HTTPie**: `http POST :8080/query query="test" mode="hybrid"`
- **Programming Languages**: Use HTTP client libraries (OkHttp, Axios, Requests, etc.)

### Why You See "No static resource" Error

When accessing `http://localhost:8080/` directly in a browser, you'll see:
```json
{"error":"No static resource .","message":"No static resource ."}
```

This is **expected behavior**. There is no root webpage or index.html. You must access specific API endpoints like `/actuator/health` or POST to `/query`, `/documents/ingest`, etc.

## Building a React UI (Optional)

If you need a web interface, you can build a React frontend application that communicates with this REST API.

### Getting Started with React

1. **Create a new React application:**
```bash
npx create-react-app lightrag-ui
cd lightrag-ui
npm install axios
```

2. **Configure API proxy** (in `package.json`):
```json
{
  "proxy": "http://localhost:8080"
}
```

### Example React Components

#### API Service Layer

Create `src/services/lightragApi.js`:

```javascript
import axios from 'axios';

const API_BASE_URL = process.env.REACT_APP_API_URL || 'http://localhost:8080';

export const lightragApi = {
  // Query the RAG system
  async query(question, workspaceId = 'default', mode = 'hybrid') {
    const response = await axios.post(`${API_BASE_URL}/query`, {
      query: question,
      mode: mode
    }, {
      headers: {
        'X-Workspace-Id': workspaceId
      }
    });
    return response.data;
  },

  // Upload documents
  async uploadDocuments(files, workspaceId = 'default') {
    const formData = new FormData();
    files.forEach(file => formData.append('files', file));

    const response = await axios.post(`${API_BASE_URL}/documents/upload`, formData, {
      headers: {
        'X-Workspace-Id': workspaceId,
        'Content-Type': 'multipart/form-data'
      }
    });
    return response.data;
  },

  // Ingest structured documents
  async ingestDocuments(documents, workspaceId = 'default') {
    const response = await axios.post(`${API_BASE_URL}/documents/ingest`, {
      documents: documents
    }, {
      headers: {
        'X-Workspace-Id': workspaceId
      }
    });
    return response.data;
  },

  // Check document status
  async getDocumentStatus(documentId, workspaceId = 'default') {
    const response = await axios.get(`${API_BASE_URL}/documents/status/${documentId}`, {
      headers: {
        'X-Workspace-Id': workspaceId
      }
    });
    return response.data;
  },

  // Health check
  async healthCheck() {
    const response = await axios.get(`${API_BASE_URL}/actuator/health`);
    return response.data;
  }
};
```

#### Query Component

Create `src/components/QueryComponent.jsx`:

```jsx
import React, { useState } from 'react';
import { lightragApi } from '../services/lightragApi';

function QueryComponent() {
  const [query, setQuery] = useState('');
  const [answer, setAnswer] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);
    setError(null);

    try {
      const result = await lightragApi.query(query);
      setAnswer(result);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="query-component">
      <h2>Query RAG System</h2>
      <form onSubmit={handleSubmit}>
        <textarea
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Enter your question..."
          rows="4"
          style={{ width: '100%' }}
        />
        <button type="submit" disabled={loading || !query}>
          {loading ? 'Searching...' : 'Search'}
        </button>
      </form>

      {error && (
        <div className="error" style={{ color: 'red' }}>
          Error: {error}
        </div>
      )}

      {answer && (
        <div className="answer">
          <h3>Answer:</h3>
          <p>{answer.answer}</p>

          {answer.contexts && answer.contexts.length > 0 && (
            <div>
              <h4>Contexts:</h4>
              <ul>
                {answer.contexts.map((ctx, idx) => (
                  <li key={idx}>{ctx.content}</li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default QueryComponent;
```

#### Document Upload Component

Create `src/components/UploadComponent.jsx`:

```jsx
import React, { useState } from 'react';
import { lightragApi } from '../services/lightragApi';

function UploadComponent() {
  const [files, setFiles] = useState([]);
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState(null);

  const handleFileChange = (e) => {
    setFiles(Array.from(e.target.files));
  };

  const handleUpload = async () => {
    if (files.length === 0) return;

    setUploading(true);
    try {
      const response = await lightragApi.uploadDocuments(files);
      setResult(response);
      setFiles([]);
    } catch (err) {
      alert('Upload failed: ' + err.message);
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="upload-component">
      <h2>Upload Documents</h2>
      <input
        type="file"
        multiple
        onChange={handleFileChange}
        accept=".txt,.md,.pdf,.doc,.docx"
      />
      <button onClick={handleUpload} disabled={uploading || files.length === 0}>
        {uploading ? 'Uploading...' : 'Upload'}
      </button>

      {result && (
        <div className="upload-result">
          <p>Job ID: {result.jobId}</p>
          {result.documentIds && (
            <p>Uploaded {result.documentIds.length} documents</p>
          )}
        </div>
      )}
    </div>
  );
}

export default UploadComponent;
```

### Running the React App

1. **Start the backend API:**
```bash
mvn spring-boot:run -pl lightrag-spring-boot-demo
```

2. **Start the React development server:**
```bash
npm start
```

The React app will run on `http://localhost:3000` and proxy API requests to `http://localhost:8080`.

## Configuration

The application configuration is located at:
- `lightrag-spring-boot-demo/src/main/resources/application.yml`

Default configuration:
- Storage: `in-memory`
- Port: `8080`
- Model settings: Resolved from environment variables
- Async ingest: Enabled

## Additional Resources

For more detailed usage examples and API documentation, see:
- Main README: [README.md](README.md)
- Spring Boot Actuator endpoints: `http://localhost:8080/actuator`
