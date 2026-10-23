# ADR-0009: Search & recommendations: PostgreSQL FTS + pgvector + local CPU embeddings

- Status: Accepted · Date: 2026-10-22

## Context
Need keyword + semantic search and explainable recommendations with no paid AI and no OpenSearch.

## Options
(a) OpenSearch; (b) PG FTS + pgvector; (c) hosted vector DB/LLM.

## Decision
Search service: `search_documents` with generated `tsvector` and `vector(384)`, hybrid ranking by Reciprocal Rank Fusion. Embeddings from `BAAI/bge-small-en-v1.5` via fastembed (ONNX, CPU) inside the Python recommendations service (`/internal/embed`), with a deterministic hashing embedder fallback (flagged in responses). Recommendations: content similarity to the viewer's history + genre affinity, popularity fallback for cold start; every item carries `reasons[]`. Offline evaluation script reports Recall@K, NDCG@K, MRR, diversity, latency.

## Rationale
Fits in a few hundred MB, explainable, zero inference cost.

## Consequences
Semantic quality bounded by a small model.

## Revisit Trigger
Catalog > 100k titles or measured NDCG drop.
