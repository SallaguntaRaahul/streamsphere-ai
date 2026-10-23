create table notifications (
    id         uuid primary key,
    user_id    text        not null,
    kind       text        not null,
    title      text        not null,
    body       text        not null,
    link       text,
    source_event uuid      not null unique,  -- one notification per event (idempotent)
    created_at timestamptz not null,
    read_at    timestamptz
);
create index ix_notif_user on notifications (user_id, created_at desc);

create table delivery_attempts (
    id              uuid primary key,
    notification_id uuid        not null references notifications (id) on delete cascade,
    channel         text        not null,
    status          text        not null check (status in ('DELIVERED','SKIPPED','FAILED')),
    error           text,
    attempted_at    timestamptz not null
);
