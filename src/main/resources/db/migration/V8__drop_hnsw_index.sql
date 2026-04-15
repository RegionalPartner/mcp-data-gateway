-- RAG-002: Drop HNSW index — incompatible with AVX2-only deployment nodes.
--
-- pgvector 0.8.x compiles HNSW index maintenance (build, insert, update) with
-- AVX-512 VNNI when the build host supports it.  OVH nodes expose avx/avx2 but
-- not avx512, so any write to the HNSW index triggers SIGILL and crashes the
-- PostgreSQL backend, rolling back the transaction.
--
-- For a demo dataset (~4–100 rows), sequential cosine-distance scan is fast
-- enough without any ANN index.  This migration permanently removes the index so
-- EmbeddingInitializer can persist embeddings on the next pod startup.
--
-- IVFFlat is an alternative if the dataset grows; revisit when row count > 1 000.

DROP INDEX IF EXISTS idx_chunk_embedding_hnsw;
