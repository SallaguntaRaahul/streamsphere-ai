-- extensions vector + pg_trgm are created by the platform init (requires superuser)
create table search_documents (
    title_id         uuid primary key,
    title            text        not null,
    synopsis         text,
    genres           text[]      not null default '{}',
    language         text,
    creator_id       text        not null,
    creator_name     text,
    release_year     int,
    duration_seconds int,
    maturity_rating  text,
    published_at     timestamptz,
    popularity       double precision not null default 0,
    tsv              tsvector    not null,
    embedding        vector(384),
    embedding_model  text,
    updated_at       timestamptz not null
);
create index ix_search_tsv on search_documents using gin (tsv);
create index ix_search_title_trgm on search_documents using gin (title gin_trgm_ops);
create index ix_search_embedding on search_documents using hnsw (embedding vector_cosine_ops);
create index ix_search_genres on search_documents using gin (genres);
