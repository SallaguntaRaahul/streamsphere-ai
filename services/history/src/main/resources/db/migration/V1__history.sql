create table watch_progress (
    user_id          text        not null,
    title_id         uuid        not null,
    position_seconds int         not null check (position_seconds >= 0),
    duration_seconds int         not null check (duration_seconds > 0),
    last_milestone   int         not null default 0,
    completed        boolean     not null default false,
    updated_at       timestamptz not null,
    primary key (user_id, title_id)
);
create index ix_progress_continue on watch_progress (user_id, updated_at desc) where not completed;

create table watchlists (
    user_id  text        not null,
    title_id uuid        not null,
    added_at timestamptz not null,
    primary key (user_id, title_id)
);

create table removed_titles (title_id uuid primary key);
