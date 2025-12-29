# AI Context Orchestrator

Transform scattered project knowledge into optimized context documents for AI assistants.

## What It Does

This tool ingests your project documentation (markdown files, code, etc.) and makes it queryable via semantic search. When you need context for an AI assistant like Claude or GitHub Copilot, just ask a question and get the most relevant chunks back.

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+
- OpenAI API key

### Run the Application

```bash
# Set your OpenAI API key
export OPENAI_API_KEY=sk-your-key-here

# Build and run
./mvnw spring-boot:run
```

### Verify It's Working

```bash
# API root (endpoint discovery)
curl http://localhost:8080/api/v1

# Health check (Spring Actuator)
curl http://localhost:8080/actuator/health
```

## Project Structure

```
ai-context-orchestrator/
├── src/main/java/com/adlanda/contextorchestrator/
│   ├── OrchestratorApplication.java    # Main application
│   ├── controller/
│   │   └── ApiController.java          # API endpoints
│   ├── service/                        # Business logic (Iteration 1+)
│   └── config/                         # Configuration classes
├── src/main/resources/
│   └── application.properties          # Configuration
├── docs/                               # Sample documents to ingest
└── pom.xml                             # Maven dependencies
```

## Iteration Status

- [x] **Iteration 0**: Project setup, health endpoints
- [x] **Iteration 1**: Minimal RAG (ingest markdown, query via embeddings) - [docs](docs/iteration-1.md)
- [ ] **Iteration 2**: Persistent storage with PGVector
- [ ] **Iteration 3**: Smart chunking
- [ ] **Iteration 4**: Multiple source types
- [ ] **Iteration 5**: API refinement
- [ ] **Iteration 6**: Context optimization
- [ ] **Iteration 7**: Production hardening

## Configuration

Key properties in `application.properties`:

| Property | Description | Default |
|----------|-------------|---------|
| `spring.ai.openai.api-key` | OpenAI API key | `$OPENAI_API_KEY` |
| `spring.ai.openai.embedding.options.model` | Embedding model | `text-embedding-3-small` |
| `orchestrator.docs.path` | Path to docs folder | `./docs` |
| `spring.threads.virtual.enabled` | Enable virtual threads | `true` |

## Tech Stack

- **Spring Boot 3.3** - Framework with virtual thread support
- **Spring AI** - AI/ML integration
- **Java 21** - Virtual threads (JEP 444)
- **OpenAI** - Embeddings API
- **PGVector** - Vector storage (Iteration 2+)
