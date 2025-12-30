# End-to-End Test Results

This document captures the results of testing the AI Context Orchestrator using the documents in the `docs/` folder as input.

## Test Environment

- **Date**: December 30, 2025
- **Application**: AI Context Orchestrator v0.0.1-SNAPSHOT
- **Embedding Model**: OpenAI `text-embedding-3-small` (1536 dimensions)
- **Java Version**: 21.0.8
- **Spring Boot**: 3.3.0

## Documents Indexed

The application successfully ingested 5 markdown documents from the `docs/` folder:

| Document | Description |
|----------|-------------|
| `iteration-1.md` | Explains the RAG pipeline and application architecture |
| `sample.md` | Contains information about virtual threads and rate limiting |
| `spring-ai-guide.md` | Details Spring AI usage and embeddings explanation |
| `spring-ai-introduction.md` | Comprehensive Spring AI introduction |
| `spring-ai-vs-langchain.md` | Comparison between Spring AI and LangChain |

**Startup Log:**
```
Ingested 1 chunks from iteration-1.md
Ingested 1 chunks from sample.md
Ingested 1 chunks from spring-ai-guide.md
Ingested 1 chunks from spring-ai-introduction.md
Ingested 1 chunks from spring-ai-vs-langchain.md
Total chunks ingested: 5
Generated 5 embeddings
Stored 5 chunks in vector store
Ingestion complete: 5 chunks indexed
```

## Query Test Results

### Test 1: Virtual Threads Query

**Query**: "What are virtual threads?"

**Results** (top 3):

| Rank | Source | Score | Relevance |
|------|--------|-------|-----------|
| 1 | `sample.md` | 0.509 | Correct - Contains detailed virtual threads documentation |
| 2 | `spring-ai-guide.md` | 0.251 | Related - Mentions virtual threads integration |
| 3 | `spring-ai-introduction.md` | ~0.2 | Tangentially related |

**Analysis**: The system correctly identified `sample.md` as the most relevant document since it contains the primary virtual threads content including JEP 444 details, key benefits, and code examples.

---

### Test 2: Spring AI vs LangChain Comparison

**Query**: "How does Spring AI compare to LangChain?"

**Results** (top 3):

| Rank | Source | Score | Relevance |
|------|--------|-------|-----------|
| 1 | `spring-ai-vs-langchain.md` | 0.748 | Perfect match - Dedicated comparison document |
| 2 | `spring-ai-guide.md` | 0.558 | Highly relevant - Spring AI usage details |
| 3 | `spring-ai-introduction.md` | ~0.5 | Relevant - Spring AI overview |

**Analysis**: Excellent result with a high similarity score (0.748) for the exact comparison document. The semantic search correctly understood the query intent.

---

### Test 3: RAG Pipeline Query

**Query**: "What is the RAG pipeline?"

**Results** (top 3):

| Rank | Source | Score | Relevance |
|------|--------|-------|-----------|
| 1 | `iteration-1.md` | 0.372 | Correct - Contains RAG pipeline explanation with diagrams |
| 2 | `spring-ai-vs-langchain.md` | 0.290 | Related - Discusses RAG implementation in both frameworks |
| 3 | `spring-ai-introduction.md` | ~0.28 | Related - Mentions RAG in context of AI applications |

**Analysis**: The system correctly identified `iteration-1.md` which contains the detailed RAG pipeline explanation with PlantUML diagrams showing the ingestion and query flows.

---

### Test 4: Embeddings Query

**Query**: "How do embeddings work?"

**Results** (top 3):

| Rank | Source | Score | Relevance |
|------|--------|-------|-----------|
| 1 | `spring-ai-guide.md` | 0.440 | Perfect match - Contains detailed embeddings explanation |
| 2 | `iteration-1.md` | 0.320 | Related - Explains embedding in RAG context |
| 3 | `spring-ai-introduction.md` | ~0.3 | Related - Covers embedding models |

**Analysis**: Correct result. `spring-ai-guide.md` contains the comprehensive "Understanding Embeddings" section with explanations of vectors, cosine similarity, and how embedding models work.

---

## Similarity Score Interpretation

| Score Range | Interpretation |
|-------------|----------------|
| 0.7 - 1.0 | Strong semantic match (exact topic) |
| 0.4 - 0.7 | Good match (related topic) |
| 0.2 - 0.4 | Moderate match (tangentially related) |
| 0.0 - 0.2 | Weak match (likely unrelated) |

## Key Observations

### What Worked Well

1. **Semantic Understanding**: The system correctly understood query intent, not just keyword matching
   - "virtual threads" query found the virtual threads documentation
   - "RAG pipeline" query found the architecture documentation

2. **Ranking Quality**: More relevant documents consistently scored higher

3. **Cross-Document Relevance**: Related documents (like Spring AI docs for Spring AI queries) appeared in results with appropriate scores

### Areas for Future Improvement

1. **Chunking Granularity**: Currently each document is treated as a single chunk. For larger documents, more granular chunking would improve precision.

2. **Score Normalization**: Some queries return lower absolute scores even for good matches. Score calibration could improve consistency.

3. **Multi-Topic Documents**: Documents covering multiple topics (like `spring-ai-guide.md`) may rank highly for various queries. Topic-aware chunking could help.

## API Usage Examples

### Query Endpoint

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{"question": "How do virtual threads work?", "maxResults": 3}'
```

### Response Structure

```json
{
  "results": [
    {
      "content": "# Sample Document\n\nThis is a sample document...",
      "sourceFile": "sample.md",
      "chunkIndex": 0,
      "score": 0.5090206963936412
    }
  ]
}
```

### Sources Endpoint

```bash
curl http://localhost:8080/api/v1/sources
```

```json
{
  "totalChunks": 5,
  "status": "indexed"
}
```

## Conclusion

The AI Context Orchestrator successfully demonstrates semantic search capabilities:

- **5 documents indexed** from the `docs/` folder
- **Real OpenAI embeddings** via `text-embedding-3-small` model
- **Accurate semantic retrieval** with relevant documents ranking highest
- **Sub-second query response** times

The system correctly maps user questions to relevant documentation, enabling effective RAG-based context retrieval for AI assistants.
