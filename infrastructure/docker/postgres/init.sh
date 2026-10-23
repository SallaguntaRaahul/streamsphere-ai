#!/bin/bash
# One database + one login role per service (ADR-0003). Roles cannot connect to other databases.
set -euo pipefail
for svc in profile catalog creator upload media playback history search recommendations moderation analytics notifications keycloak; do
  pw_var="DB_PASSWORD_${svc^^}"
  pw="${!pw_var:-${svc}_local_pw}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
CREATE ROLE ${svc}_svc LOGIN PASSWORD '${pw}';
CREATE DATABASE ${svc} OWNER ${svc}_svc;
REVOKE CONNECT ON DATABASE ${svc} FROM PUBLIC;
GRANT CONNECT ON DATABASE ${svc} TO ${svc}_svc;
SQL
done
# pgvector only where needed, created by superuser (extension creation requires it)
for db in search recommendations; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" -c "CREATE EXTENSION IF NOT EXISTS vector;"
done
