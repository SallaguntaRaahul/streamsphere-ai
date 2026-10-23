create table upload_sessions (
    id                  uuid primary key,
    title_id            uuid        not null,
    creator_id          text        not null,
    bucket              text        not null,
    object_key          text        not null unique,
    s3_upload_id        text        not null,
    file_name           text        not null,
    content_type        text        not null,
    size_bytes          bigint      not null check (size_bytes > 0),
    part_size           bigint      not null,
    part_count          int         not null check (part_count between 1 and 10000),
    sha256              char(64)    not null,
    state               text        not null check (state in ('INITIATED','UPLOADING','UPLOADED','FAILED','CANCELLED','EXPIRED')),
    error               text,
    provider            text        not null,
    idempotency_key     text,
    created_at          timestamptz not null,
    completed_at        timestamptz,
    expires_at          timestamptz not null,
    source_delete_after timestamptz,
    source_deleted_at   timestamptz,
    version             int         not null default 0,
    unique (creator_id, idempotency_key)
);
create index ix_upload_creator on upload_sessions (creator_id, created_at desc);
create index ix_upload_open on upload_sessions (expires_at) where state in ('INITIATED','UPLOADING');

create table upload_parts (
    session_id  uuid   not null references upload_sessions (id) on delete cascade,
    part_number int    not null,
    etag        text   not null,
    size_bytes  bigint not null,
    primary key (session_id, part_number)
);

-- projection of rendition bytes reported by media (MediaReady.v1) for the cost guard
create table media_storage (
    asset_id uuid primary key,
    title_id uuid   not null,
    bytes    bigint not null
);

create table cost_meter (
    month    text   not null,
    op_class text   not null,
    count    bigint not null,
    primary key (month, op_class)
);
