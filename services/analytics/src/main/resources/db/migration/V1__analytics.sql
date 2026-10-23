-- user ids are stored only as salted SHA-256 hashes (pseudonymous); raw IPs/user agents are never stored
create table playback_events (
    id              uuid primary key,
    kind            text        not null check (kind in ('STARTED','PROGRESS','COMPLETED','QOE')),
    session_id      uuid,
    title_id        uuid        not null,
    user_hash       char(64),
    watched_seconds int,
    startup_ms      int,
    rebuffer_ms     int,
    played_ms       int,
    bitrate_kbps    int,
    bitrate_switches int,
    error_code      text,
    occurred_at     timestamptz not null
);
create index ix_pe_title_time on playback_events (title_id, occurred_at);
create index ix_pe_time on playback_events (occurred_at);

create table title_owners (
    title_id         uuid primary key,
    creator_id       text not null,
    title            text not null,
    duration_seconds int
);
create index ix_owner_creator on title_owners (creator_id);

create table creator_daily_stats (
    title_id          uuid        not null,
    day               date        not null,
    creator_id        text        not null,
    views             int         not null,
    unique_viewers    int         not null,
    completions       int         not null,
    watch_seconds     bigint      not null,
    avg_startup_ms    int,
    rebuffer_ratio    double precision,
    computed_at       timestamptz not null,
    primary key (title_id, day)
);
create index ix_stats_creator on creator_daily_stats (creator_id, day);
