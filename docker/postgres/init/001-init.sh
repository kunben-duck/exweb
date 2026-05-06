psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'EOSQL'
CREATE SCHEMA IF NOT EXISTS supervisor_dev AUTHORIZATION fin_supervisor;
SET search_path TO supervisor_dev;
\i /schema.sql
EOSQL
