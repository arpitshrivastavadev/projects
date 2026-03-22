#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE ecp_user_mgmt;
    CREATE DATABASE ecp_edge_mgmt;
    CREATE DATABASE ecp_events;
    GRANT ALL PRIVILEGES ON DATABASE ecp_user_mgmt TO ecp_user;
    GRANT ALL PRIVILEGES ON DATABASE ecp_edge_mgmt TO ecp_user;
    GRANT ALL PRIVILEGES ON DATABASE ecp_events TO ecp_user;
EOSQL
