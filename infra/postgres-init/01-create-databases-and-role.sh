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
#   - svc_system: used ONLY by a handful of genuinely cross-tenant reads
#     (the admin dashboard's onboarding/utilization/limit-increase-request
#     listings, and Phase 2's overdue-invoice sweep and near-limit-alert
#     scan). Narrowly scoped on purpose: nothing in the normal request path
#     ever uses this role, so a bug there can't accidentally leak another
#     tenant's data the way a blanket RLS bypass would. (Named svc_system,
#     not system_user - SYSTEM_USER is a reserved SQL keyword and CREATE
#     ROLE system_user fails with a syntax error without quoting it as a
#     delimited identifier everywhere; easier to just not use that name.)
#
#     NOT created with BYPASSRLS: a fully-managed Postgres host (Render,
#     Supabase, RDS, ...) never grants its provisioned user the right to
#     grant BYPASSRLS to anyone else - only a role that already has it can
#     hand it out, and managed providers withhold it from their own users
#     by design, for their multi-tenant isolation. Instead, each RLS table
#     has an additional "<table>_admin_bypass" policy (see each service's
#     own later migration) that grants the same effective access through
#     an ordinary session-local flag (app.admin_mode) instead of a role
#     attribute - portable to any Postgres, managed or not. The svc_system
#     connection pools set that flag on every connection via their JDBC
#     URL's "options" parameter (see SystemJdbcConfig / AdminOnboardingController).
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
    CREATE ROLE app_user LOGIN PASSWORD 'app_user';
    CREATE ROLE svc_system LOGIN PASSWORD 'svc_system';
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
