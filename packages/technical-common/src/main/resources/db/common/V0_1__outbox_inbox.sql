-- Technical messaging tables. Every service database gets its OWN copy via Flyway
-- (location classpath:db/common); no table is shared between services.
create table outbox_events (
    id             uuid primary key,
    event_type     text        not null,
    event_version  int         not null,
    aggregate_id   text        not null,
    payload        jsonb       not null,
    correlation_id text,
    occurred_at    timestamptz not null,
    published_at   timestamptz
);
create index ix_outbox_unpublished on outbox_events (occurred_at) where published_at is null;

create table inbox_deduplication (
    consumer     text        not null,
    event_id     uuid        not null,
    processed_at timestamptz not null default now(),
    primary key (consumer, event_id)
);
