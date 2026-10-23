create table titles (
    id               uuid primary key,
    creator_id       text        not null,
    creator_name     text        not null,
    kind             text        not null check (kind in ('FILM','SERIES')),
    title            text        not null check (length(title) between 1 and 160),
    synopsis         text,
    genres           text[]      not null default '{}',
    language         text        not null default 'en',
    maturity_rating  text        not null default 'NR',
    release_year     int,
    duration_seconds int,
    editorial_state  text        not null check (editorial_state in ('DRAFT','IN_REVIEW','APPROVED','REJECTED','PUBLISHED','REMOVED')),
    media_state      text        not null check (media_state in ('NONE','PROCESSING','READY','FAILED')),
    media_asset_id   uuid,
    media_version    int,
    has_poster       boolean     not null default false,
    rejection_reason text,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    published_at     timestamptz,
    version          int         not null default 0
);
create index ix_titles_published on titles (published_at desc, id desc) where editorial_state = 'PUBLISHED';
create index ix_titles_creator on titles (creator_id, created_at desc);
create index ix_titles_genres on titles using gin (genres);

create table seasons (
    id       uuid primary key,
    title_id uuid not null references titles (id) on delete cascade,
    number   int  not null check (number > 0),
    name     text,
    unique (title_id, number)
);

create table episodes (
    id               uuid primary key,
    season_id        uuid not null references seasons (id) on delete cascade,
    number           int  not null check (number > 0),
    name             text not null,
    synopsis         text,
    media_asset_id   uuid,
    duration_seconds int,
    unique (season_id, number)
);

create table audit_events (
    id       uuid primary key,
    title_id uuid        not null,
    actor    text        not null,
    action   text        not null,
    detail   text,
    at       timestamptz not null
);
create index ix_audit_title on audit_events (title_id, at);
