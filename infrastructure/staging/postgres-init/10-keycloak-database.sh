#!/bin/bash
# Database e usuário próprios do Keycloak (ADR-0043 §3). Roda só no primeiro init do volume do PostgreSQL.
# Nada da aplicação lê essas tabelas; o usuário do Keycloak não tem acesso ao banco da aplicação.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
	-v kc_db="$KC_DB_NAME" -v kc_user="$KC_DB_USERNAME" -v kc_password="$KC_DB_PASSWORD" <<-'SQL'
	CREATE ROLE :"kc_user" LOGIN PASSWORD :'kc_password';
	CREATE DATABASE :"kc_db" OWNER :"kc_user";
	REVOKE ALL ON DATABASE :"kc_db" FROM PUBLIC;
	SQL

# O banco da aplicação também deixa de aceitar conexão de qualquer papel por padrão.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v app_db="$POSTGRES_DB" <<-'SQL'
	REVOKE CONNECT ON DATABASE :"app_db" FROM PUBLIC;
	SQL
