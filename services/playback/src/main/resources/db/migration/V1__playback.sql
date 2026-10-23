create table playback_sessions (
    id            uuid primary key,
    user_id       text        not null,
    title_id      uuid        not null,
    asset_id      uuid        not null,
    asset_version int         not null,
    preview       boolean     not null default false,
    created_at    timestamptz not null,
    expires_at    timestamptz not null,
    revoked_at    timestamptz
);
create index ix_sessions_user on playback_sessions (user_id, created_at desc);
create index ix_sessions_title on playback_sessions (title_id) where revoked_at is null;

create table revoked_titles (
    title_id   uuid primary key,
    reason     text        not null,
    revoked_at timestamptz not null
);

create table delivery_counters (
    month text   not null,
    kind  text   not null,
    count bigint not null,
    primary key (month, kind)
);
