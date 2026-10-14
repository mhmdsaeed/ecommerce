#!/usr/bin/env bash
# Creates the application database and its three roles (SPEC 4.3).
# Runs once, as the postgres superuser, on a fresh data volume. Testcontainers mounts the same script.
#
#   app_owner     owns the database and schema; runs Flyway. NOBYPASSRLS, so FORCE ROW LEVEL SECURITY applies to it too.
#   app_user      runtime role. NOBYPASSRLS and owns nothing, so RLS policies always apply.
#   app_platform  platform-admin/billing role. BYPASSRLS, owns nothing.
set -euo pipefail

: "${DB_NAME:=ecommerce}"
: "${DB_OWNER_USER:=app_owner}"
: "${DB_OWNER_PASSWORD:=app_owner_local}"
: "${DB_APP_USER:=app_user}"
: "${DB_APP_PASSWORD:=app_user_local}"
: "${DB_PLATFORM_USER:=app_platform}"
: "${DB_PLATFORM_PASSWORD:=app_platform_local}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v db="$DB_NAME" \
  -v owner="$DB_OWNER_USER" -v owner_pw="$DB_OWNER_PASSWORD" \
  -v app="$DB_APP_USER" -v app_pw="$DB_APP_PASSWORD" \
  -v platform="$DB_PLATFORM_USER" -v platform_pw="$DB_PLATFORM_PASSWORD" <<'SQL'
CREATE ROLE :"owner"    LOGIN PASSWORD :'owner_pw'    NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE :"app"      LOGIN PASSWORD :'app_pw'      NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE :"platform" LOGIN PASSWORD :'platform_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS;

CREATE DATABASE :"db" OWNER :"owner" ENCODING 'UTF8' TEMPLATE template0;
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO :"app", :"platform";
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$DB_NAME" \
  -v owner="$DB_OWNER_USER" -v app="$DB_APP_USER" -v platform="$DB_PLATFORM_USER" <<'SQL'
ALTER SCHEMA public OWNER TO :"owner";
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"app", :"platform";

-- Every table and sequence Flyway (app_owner) creates is usable by the runtime roles, never alterable by them.
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app", :"platform";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO :"app", :"platform";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO :"app", :"platform";
SQL
