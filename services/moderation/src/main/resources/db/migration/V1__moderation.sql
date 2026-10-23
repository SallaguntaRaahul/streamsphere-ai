create table review_cases (
    id             uuid primary key,
    title_id       uuid        not null,
    creator_id     text        not null,
    creator_name   text,
    title          text        not null,
    synopsis       text,
    media_asset_id uuid,
    media_version  int,
    kind           text        not null check (kind in ('SUBMISSION','REPORT','APPEAL')),
    state          text        not null check (state in ('OPEN','APPROVED','REJECTED','ACTIONED','DISMISSED')),
    reason         text,
    decided_by     text,
    decided_at     timestamptz,
    created_at     timestamptz not null,
    version        int         not null default 0
);
create index ix_cases_open on review_cases (created_at) where state = 'OPEN';
create unique index ux_cases_one_open on review_cases (title_id, kind) where state = 'OPEN';

create table content_reports (
    id          uuid primary key,
    title_id    uuid        not null,
    reporter_id text        not null,
    category    text        not null check (category in ('COPYRIGHT','ILLEGAL','HARASSMENT','SEXUAL','VIOLENCE','SPAM','OTHER')),
    details     text,
    case_id     uuid references review_cases (id),
    created_at  timestamptz not null,
    unique (title_id, reporter_id, category)
);

create table audit_events (
    id       uuid primary key,
    case_id  uuid,
    title_id uuid        not null,
    actor    text        not null,
    action   text        not null,
    detail   text,
    at       timestamptz not null
);
create index ix_mod_audit_title on audit_events (title_id, at);

create table title_projection (
    title_id     uuid primary key,
    creator_id   text not null,
    title        text not null,
    creator_name text,
    published    boolean not null default false,
    removed      boolean not null default false
);
