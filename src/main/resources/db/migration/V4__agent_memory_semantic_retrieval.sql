-- V4: semantic-memory relevance retrieval groundwork (pgvector + pg_trgm).
--
-- PostgreSQL-only, degrade-safe. This migration is written so a MISSING or non-installable
-- extension logs a NOTICE and leaves the schema usable instead of failing Flyway and bricking
-- application startup. When the vector column ends up absent, the Java retrieval layer detects it
-- at runtime and falls back to keyword-only, then to the legacy newest-N injection.
--
-- H2 (test schema) does NOT run this file; tests use src/test/resources/schema.sql instead.
--
-- ${embeddingDimension} is a Flyway placeholder (spring.flyway.placeholders.embeddingDimension),
-- kept in lock-step with rcdis.agent.memory.embedding-dimension via one shared env var. The value
-- must equal the configured embedding model's output dimension.

-- 1) Try to enable the extensions. IF NOT EXISTS makes this idempotent; the wrapper swallows a
--    failure to install (e.g. binaries unavailable) so the migration still succeeds.
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pgvector unavailable (%): semantic vector recall disabled, keyword/newest-N fallback in effect', SQLERRM;
END
$$;

DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pg_trgm unavailable (%): trigram keyword index skipped', SQLERRM;
END
$$;

-- 2) Add the embedding column ONLY if the vector type actually exists. Guarded so a missing
--    extension does not fail the ALTER (which would abort the whole migration).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_type WHERE typname = 'vector') THEN
        ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS embedding vector(${embeddingDimension});
    ELSE
        RAISE NOTICE 'vector type absent: skipping agent_memory.embedding column';
    END IF;
END
$$;

-- 3) HNSW cosine index for approximate nearest-neighbour recall, only when the column exists.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'agent_memory' AND column_name = 'embedding'
    ) THEN
        CREATE INDEX IF NOT EXISTS idx_agent_memory_embedding
            ON agent_memory USING hnsw (embedding vector_cosine_ops);
    END IF;
END
$$;

-- 4) Trigram index on content for keyword similarity, only when pg_trgm is present.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        CREATE INDEX IF NOT EXISTS idx_agent_memory_content_trgm
            ON agent_memory USING gin (content gin_trgm_ops);
    END IF;
END
$$;
