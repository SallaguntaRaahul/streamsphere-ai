create table media_assets (
    id               uuid primary key,
    title_id         uuid        not null,
    creator_id       text        not null,
    upload_id        uuid        not null unique,      -- one asset per verified upload (idempotency)
    asset_version    int         not null,
    source_bucket    text        not null,
    source_key       text        not null,
    source_sha256    char(64)    not null,
    source_bytes     bigint      not null,
    state            text        not null check (state in ('QUEUED','PROCESSING','READY','FAILED','DELETED')),
    provider         text        not null,             -- transcoding provider, recorded per asset (never silently migrated)
    provider_version text        not null,
    delivery         text        not null default 'selfhosted-hls',
    output_bucket    text        not null,
    output_prefix    text,
    duration_seconds int,
    width            int,
    height           int,
    has_audio        boolean,
    total_bytes      bigint,
    has_poster       boolean     not null default false,
    thumbnail_count  int         not null default 0,
    published        boolean     not null default false,
    removed          boolean     not null default false,
    error            text,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    ready_at         timestamptz,
    version          int         not null default 0,
    unique (title_id, asset_version)
);
create index ix_assets_title on media_assets (title_id, asset_version desc);

create table processing_jobs (
    id            uuid primary key,
    asset_id      uuid        not null references media_assets (id),
    attempt       int         not null,
    launcher      text        not null,
    external_ref  text,
    state         text        not null check (state in ('QUEUED','RUNNING','SUCCEEDED','FAILED','TIMED_OUT')),
    output_prefix text        not null,
    queued_at     timestamptz not null,
    started_at    timestamptz,
    finished_at   timestamptz,
    deadline_at   timestamptz not null,
    wall_seconds  double precision,
    cpu_seconds   double precision,
    error         text,
    unique (asset_id, attempt)
);
create index ix_jobs_open on processing_jobs (deadline_at) where state in ('QUEUED','RUNNING');

create table renditions (
    asset_id      uuid   not null references media_assets (id) on delete cascade,
    name          text   not null,
    width         int    not null,
    height        int    not null,
    video_kbps    int    not null,
    audio_kbps    int    not null,
    bandwidth     int    not null,
    codecs        text   not null,
    playlist_key  text   not null,
    segment_count int    not null,
    bytes         bigint not null,
    primary key (asset_id, name)
);

create table caption_tracks (
    id         uuid primary key,
    asset_id   uuid        not null references media_assets (id) on delete cascade,
    language   text        not null,
    label      text        not null,
    object_key text        not null,
    is_default boolean     not null default false,
    created_at timestamptz not null,
    unique (asset_id, language)
);
