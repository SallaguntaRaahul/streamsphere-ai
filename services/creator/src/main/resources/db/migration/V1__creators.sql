create table creators (
    user_id       text primary key,
    display_name  text        not null check (length(display_name) between 1 and 80),
    bio           text,
    country       char(2),
    status        text        not null check (status in ('ACTIVE','SUSPENDED')),
    terms_version text        not null,
    created_at    timestamptz not null,
    version       int         not null default 0
);

-- Append-only: an attestation is a legal statement and is never edited.
create table rights_attestations (
    id                uuid primary key,
    creator_id        text        not null references creators (user_id),
    title_id          uuid        not null,
    license_type      text        not null check (license_type in ('ORIGINAL','LICENSED','PUBLIC_DOMAIN','CC_BY','CC_BY_SA','CC0')),
    evidence_url      text,
    statement_version text        not null,
    attested_at       timestamptz not null
);
create index ix_attest_creator_title on rights_attestations (creator_id, title_id);
