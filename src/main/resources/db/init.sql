-- =============================================================================
-- AI Context Orchestrator - Database Initialization Script
-- Iteration 2: Persistent Vector Storage with PGVector
-- =============================================================================

-- Enable the pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- =============================================================================
-- Table: ingested_sources
-- Tracks metadata about ingested files for incremental ingestion
-- =============================================================================
CREATE TABLE IF NOT EXISTS ingested_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_path VARCHAR(500) UNIQUE NOT NULL,
    file_hash VARCHAR(64) NOT NULL,      -- SHA-256 hash for change detection
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
COMMENT ON TABLE ingested_sources IS 'Tracks ingested files for incremental ingestion';
COMMENT ON COLUMN ingested_sources.file_hash IS 'SHA-256 hash to detect file changes';

-- =============================================================================
-- Note: vector_store table is auto-created by Spring AI PGVector
-- with spring.ai.vectorstore.pgvector.initialize-schema=true
-- Schema: id UUID, content TEXT, metadata JSONB, embedding vector(1536)
-- =============================================================================