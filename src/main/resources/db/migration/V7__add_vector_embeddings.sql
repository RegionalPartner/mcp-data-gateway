-- RAG-001: Add pgvector extension and embedding column to document_chunks.
-- Enables semantic (cosine similarity) search via the semantic_search_documents MCP tool.
--
-- Embedding model: nomic-embed-text (768 dimensions, served by Ollama).
-- EmbeddingInitializer populates null embeddings for existing rows on startup.
-- New chunks should have embeddings set at write time (future ingestion pipeline).

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS embedding vector(768);

-- HNSW index for approximate nearest-neighbour cosine similarity queries.
-- <=> operator (vector_cosine_ops) matches the EmbeddingInitializer distance metric.
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw
    ON document_chunks USING hnsw (embedding vector_cosine_ops);
