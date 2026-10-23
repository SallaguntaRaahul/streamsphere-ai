#!/usr/bin/env bash
# Restore drill: restores a backup into scratch databases (restore_<db>) and compares row counts with
# the live database. Never overwrites live data. Usage: scripts/restore-drill.sh backups/<ts>
set -euo pipefail
cd "$(dirname "$0")/.."
DIR=${1:?backup dir}; ( cd "$DIR" && shasum -a 256 -c SHA256SUMS >/dev/null ) && echo "checksums OK"
fail=0
for f in "$DIR"/*.dump; do
  db=$(basename "$f" .dump); [ "$db" = keycloak ] && continue
  docker compose exec -T postgres psql -U postgres -qc "drop database if exists restore_$db" -c "create database restore_$db"
  [ "$db" = search ] || [ "$db" = recommendations ] && docker compose exec -T postgres psql -U postgres -d "restore_$db" -qc "create extension vector; create extension pg_trgm"
  docker compose exec -T postgres pg_restore -U postgres --no-owner -d "restore_$db" < "$f" 2>/dev/null || true
  live=$(docker compose exec -T postgres psql -U postgres -d "$db" -Atc "select coalesce(sum(n_live_tup),0) from pg_stat_user_tables" )
  q="select string_agg(c::text, ',') from (select (xpath('/row/c/text()', query_to_xml('select count(*) as c from '||quote_ident(table_name), false, true, '')))[1]::text::int as c from information_schema.tables where table_schema='public' order by table_name) t"
  a=$(docker compose exec -T postgres psql -U postgres -d "$db" -Atc "$q"); b=$(docker compose exec -T postgres psql -U postgres -d "restore_$db" -Atc "$q")
  if [ "$a" = "$b" ]; then echo "OK   $db (row counts per table identical)"; else echo "DIFF $db live=[$a] restored=[$b]"; fail=1; fi
  docker compose exec -T postgres psql -U postgres -qc "drop database restore_$db"
done
exit $fail
