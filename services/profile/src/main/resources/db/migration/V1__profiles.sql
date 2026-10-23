create table profiles (
    user_id            text primary key,
    display_name       text        not null,
    preferred_language text        not null default 'en',
    captions_default   boolean     not null default false,
    autoplay           boolean     not null default true,
    reduced_motion     boolean     not null default false,
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    version            int         not null default 0
);
