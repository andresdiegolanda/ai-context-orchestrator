# AI Context Orchestrator

Transform scattered project knowledge into optimized context documents for AI assistants.

## What It Does

This tool ingests your project documentation (markdown files, code, etc.) and makes it queryable via semantic search. When you need context for an AI assistant like Claude or GitHub Copilot, just ask a question and get the most relevant chunks back.

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker Desktop (for PostgreSQL + PGVector)
- OpenAI API key

### Run the Application

```bash
# 1. Start PostgreSQL with PGVector
docker-compose up -d

# 2. Set your OpenAI API key
export OPENAI_API_KEY=sk-your-key-here

# 3. Build and run
./mvnw spring-boot:run
```

### Verify It's Working

```bash
# API root (endpoint discovery)
curl http://localhost:8080/api/v1

# Health check (includes ingestion status)
curl http://localhost:8080/actuator/health

# Query the indexed documents
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What is this project about?"}'
```

## Project Structure

```
ai-context-orchestrator/
├── src/main/java/com/adlanda/contextorchestrator/
│   ├── OrchestratorApplication.java    # Main application
│   ├── IngestionRunner.java            # Startup ingestion with health reporting
│   ├── controller/                     # REST API endpoints
│   ├── service/                        # Business logic
│   │   ├── IngestionService.java       # Document chunking & incremental ingestion
│   │   ├── EmbeddingService.java       # OpenAI embedding generation
│   │   ├── RetrievalService.java       # Semantic search
│   │   └── FileHashService.java        # Change detection (SHA-256)
│   ├── repository/                     # Data access
│   │   ├── PgVectorStore.java          # Vector storage with orphan cleanup
│   │   └── IngestedSourceRepository.java # File tracking
│   ├── health/                         # Health indicators
│   │   └── IngestionHealthIndicator.java # Ingestion status monitoring
│   ├── entity/                         # JPA entities
│   └── config/                         # Configuration classes
├── src/main/resources/
│   ├── application.properties          # Configuration
│   └── db/init.sql                     # Database schema
├── src/test/java/                      # Test classes
│   └── com/adlanda/contextorchestrator/
│       ├── service/                    # Service tests
│       └── repository/                 # Repository tests
├── docs/                               # Documents to ingest
├── docker-compose.yml                  # PostgreSQL + PGVector
└── pom.xml                             # Maven dependencies
```

## Iteration Status

- [x] **Iteration 0**: Project setup, health endpoints
- [x] **Iteration 1**: Minimal RAG (ingest markdown, query via embeddings) - [docs](docs/iteration-1.md)
- [x] **Iteration 2**: Persistent storage with PGVector + incremental ingestion - [docs](docs/iteration-2.md)
- [ ] **Iteration 3**: Smart chunking
- [ ] **Iteration 4**: Multiple source types
- [ ] **Iteration 5**: API refinement
- [ ] **Iteration 6**: Context optimization
- [ ] **Iteration 7**: Production hardening

## Key Features (Iteration 2)

| Feature | Description |
|---------|-------------|
| **Persistent Storage** | Embeddings stored in PostgreSQL with PGVector extension |
| **Incremental Ingestion** | Only new/changed files are processed (SHA-256 hash detection) |
| **Orphan Cleanup** | Old chunks automatically deleted when files change or are removed |
| **File Deletion Detection** | Removed files have their chunks cleaned from the index |
| **Health Monitoring** | Custom health indicator reports ingestion status |
| **HNSW Index** | Fast approximate nearest neighbor search |
| **Cost Efficient** | Avoids redundant OpenAI API calls for unchanged files |

## Configuration

Key properties in `application.properties`:

| Property | Description | Default |
|----------|-------------|---------|
| `spring.ai.openai.api-key` | OpenAI API key | `$OPENAI_API_KEY` |
| `spring.ai.openai.embedding.options.model` | Embedding model | `text-embedding-3-small` |
| `orchestrator.docs.path` | Path to docs folder | `./docs` |
| `orchestrator.ingestion.incremental` | Enable incremental ingestion | `true` |
| `spring.threads.virtual.enabled` | Enable virtual threads | `true` |

### Database Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `orchestrator` | Database name |
| `DB_USER` | `orchestrator` | Database user |
| `DB_PASSWORD` | `orchestrator` | Database password |

## Docker Commands

```bash
# Start PostgreSQL with PGVector
docker-compose up -d

# Stop (keeps data)
docker-compose stop

# Stop and remove containers (keeps data in volume)
docker-compose down

# Stop and remove everything including data
docker-compose down -v

# View logs
docker-compose logs -f postgres

# Connect to database
docker exec -it orchestrator-postgres psql -U orchestrator -d orchestrator
```

## Health Endpoint

The `/actuator/health` endpoint includes ingestion status:

```json
{
  "status": "UP",
  "components": {
    "ingestion": {
      "status": "UP",
      "details": {
        "lastRun": "2026-01-01T10:30:00Z",
        "filesTracked": 15,
        "filesProcessed": 2,
        "filesSkipped": 13,
        "filesDeleted": 0,
        "chunksIndexed": 12
      }
    }
  }
}
```

## Tech Stack

- **Spring Boot 3.3** - Framework with virtual thread support
- **Spring AI** - AI/ML integration with PGVector support
- **Java 21** - Virtual threads (JEP 444)
- **OpenAI** - Embeddings API (text-embedding-3-small)
- **PostgreSQL 16** - Relational database
- **PGVector** - Vector similarity search extension