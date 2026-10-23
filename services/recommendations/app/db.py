import logging, pathlib
from pgvector.psycopg import register_vector
from psycopg_pool import ConnectionPool
from . import config

log = logging.getLogger(__name__)
pool: ConnectionPool | None = None


def _configure(conn):
    register_vector(conn)


def init():
    global pool
    pool = ConnectionPool(config.DB_DSN, min_size=1, max_size=6, configure=_configure, open=True, timeout=10)
    migrate()


def migrate():
    """Minimal versioned migrations (service-owned schema)."""
    with pool.connection() as c:
        c.execute("create table if not exists schema_migrations (version text primary key, applied_at timestamptz not null default now())")
        done = {r[0] for r in c.execute("select version from schema_migrations").fetchall()}
        for f in sorted(pathlib.Path(__file__).resolve().parent.parent.joinpath("migrations").glob("V*.sql")):
            v = f.name.split("__")[0]
            if v in done:
                continue
            log.info("Applying migration %s", f.name)
            with c.transaction():
                c.execute(f.read_text())
                c.execute("insert into schema_migrations(version) values (%s)", (v,))
