#!/usr/bin/env bash
# Loads the schema orch and the default customers into the shared Postgres container. Run by hand after `docker compose up -d`.
set -euo pipefail
cd "$(dirname "$0")/.."
C=cern-v2-postgres   # the shared Postgres: start it first with `docker compose up -d` in the parent folder
for f in db/01_schema.sql db/02_default_customers.sql; do
  echo "loading $f"
  docker exec -i "$C" psql -v ON_ERROR_STOP=1 -U "${APP_DB_USER:-cern}" -d cern_v2 < "$f"
done
