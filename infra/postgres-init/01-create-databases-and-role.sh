#!/usr/bin/env bash
# Runs once, only when the postgres container's data volume is first
# initialized (official postgres image convention for docker-entrypoint-initdb.d).
# Creates one database per service (Decision #5 in the plan: one Postgres
# server, one database per service, not shared tables) and two roles every
# service's own DataSource beans connect as:
#   - app_user: ordinary, RLS-enforced. Every request-handling code path uses
#     this. RLS policies are meaningless against a superuser connection
#     (which bypasses them unconditionally) - app_user is deliberately NOT
#     a superuser.
#   - svc_system: BYPASSRLS, used ONLY by a handful of genuinely
#     cross-tenant scheduled jobs (Phase 2's overdue-invoice sweep and
#     near-limit-alert scan, which must see every tenant's rows, not one).
#     Narrowly scoped on purpose: nothing in the normal request path ever
#     uses this role, so a bug there can't accidentally leak another
#     tenant's data the way a blanket RLS bypass would. (Named svc_system,
#     not system_user - SYSTEM_USER is a reserved SQL keyword and CREATE
#     ROLE system_user fails with a syntax error without quoting it as a
#     delimited identifier everywhere; easier to just not use that name.)
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
    CREATE ROLE app_user LOGIN PASSWORD 'app_user';
    CREATE ROLE svc_system LOGIN PASSWORD 'svc_system' BYPASSRLS;
SQL

for db in tenant_db order_db credit_db invoice_db; do
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
        CREATE DATABASE ${db};
        GRANT ALL PRIVILEGES ON DATABASE ${db} TO app_user;
        GRANT ALL PRIVILEGES ON DATABASE ${db} TO svc_system;
SQL

    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${db}" <<-SQL
        GRANT ALL ON SCHEMA public TO app_user;
        GRANT ALL ON SCHEMA public TO svc_system;
        ALTER DEFAULT PRIVILEGES FOR ROLE app_user IN SCHEMA public GRANT ALL ON TABLES TO svc_system;
SQL
done
