create table if not exists embeddings (
    title_id     uuid primary key,
    title        text        not null,
    genres       text[]      not null default '{}',
    creator_id   text        not null,
    embedding    vector(384) not null,
    model        text        not null,
    published_at timestamptz,
    removed      boolean     not null default false,
    updated_at   timestamptz not null default now()
);
create index if not exists ix_emb_hnsw on embeddings using hnsw (embedding vector_cosine_ops);

create table if not exists interactions (
    user_id  text        not null,
    title_id uuid        not null,
    kind     text        not null check (kind in ('STARTED','PROGRESS','COMPLETED')),
    weight   real        not null,
    at       timestamptz not null,
    primary key (user_id, title_id, kind)
);
create index if not exists ix_inter_title on interactions (title_id, at);

create table if not exists recommendation_runs (
    id          uuid primary key,
    user_hash   text,
    strategy    text        not null,
    item_count  int         not null,
    latency_ms  real        not null,
    created_at  timestamptz not null default now()
);

create table if not exists inbox_deduplication (
    consumer     text        not null,
    event_id     uuid        not null,
    processed_at timestamptz not null default now(),
    primary key (consumer, event_id)
);
