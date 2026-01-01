-- =============================================================================
-- AI Context Orchestrator - Database Initialization Script
-- Iteration 2: Persistent Vector Storage with PGVector
-- =============================================================================

-- Enable the pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- =============================================================================
-- Table: document_chunks
-- Stores document chunks with their vector embeddings for similarity search
-- =============================================================================
CREATE TABLE IF NOT EXISTS document_chunks (
    id UUID PRIMARY KEY,
    content TEXT NOT NULL,
    embedding vector(1536),  -- OpenAI text-embedding-3-small produces 1536 dimensions
    source_file VARCHAR(500) NOT NULL,
    chunk_index INTEGER NOT NULL,
    file_hash VARCHAR(64) NOT NULL,  -- SHA-256 hash for change detection
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Index for fast similarity search using HNSW (Hierarchical Navigable Small World)
-- This enables approximate nearest neighbor search with excellent performance
CREATE INDEX IF NOT EXISTS idx_document_chunks_embedding
ON document_chunks
USING hnsw (embedding vector_cosine_ops);

-- Index for looking up chunks by source file
CREATE INDEX IF NOT EXISTS idx_document_chunks_source_file
ON document_chunks (source_file);

-- Index for looking up by file hash (for change detection)
CREATE INDEX IF NOT EXISTS idx_document_chunks_file_hash
ON document_chunks (file_hash);

-- =============================================================================
-- Table: ingested_sources
-- Tracks metadata about ingested files for incremental ingestion
-- =============================================================================
CREATE TABLE IF NOT EXISTS ingested_sources (
    id UUID PRIMARY KEY,
    file_path VARCHAR(500) UNIQUE NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    file_size BIGINT,
    chunk_count INTEGER DEFAULT 0,
    ingested_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Index for looking up by file path
CREATE INDEX IF NOT EXISTS idx_ingested_sources_file_path
ON ingested_sources (file_path);

-- Index for looking up by file hash
CREATE INDEX IF NOT EXISTS idx_ingested_sources_file_hash
ON ingested_sources (file_hash);

-- =============================================================================
-- Comments for documentation
-- =============================================================================
COMMENT ON TABLE document_chunks IS 'Stores document chunks with vector embeddings for semantic search';
COMMENT ON COLUMN document_chunks.embedding IS '1536-dimensional vector from OpenAI text-embedding-3-small';
COMMENT ON COLUMN document_chunks.file_hash IS 'SHA-256 hash of source file for change detection';

COMMENT ON TABLE ingested_sources IS 'Tracks ingested files for incremental ingestion';
COMMENT ON COLUMN ingested_sources.file_hash IS 'SHA-256 hash to detect file changes';