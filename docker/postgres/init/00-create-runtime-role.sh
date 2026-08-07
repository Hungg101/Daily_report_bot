#!/bin/sh
set -eu

: "${APP_DATABASE_PASSWORD:?APP_DATABASE_PASSWORD_must_be_set}"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=app_database_password="$APP_DATABASE_PASSWORD" <<'SQL'
CREATE ROLE daily_report_app
    LOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOBYPASSRLS
    NOINHERIT
    PASSWORD :'app_database_password';

GRANT CONNECT ON DATABASE daily_report_bot TO daily_report_app;
GRANT USAGE, CREATE ON SCHEMA public TO daily_report_app;
SQL
