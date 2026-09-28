#!/usr/bin/env bash
# Obtém um access token do Keycloak LOCAL (realm smart-health-finance) para testar a API.
# Somente desenvolvimento: usa o password grant, habilitado apenas no realm local.
#
# Uso (Git Bash / Linux / macOS, na raiz do repositório):
#   TOKEN=$(./infrastructure/scripts/local-token.sh)          # usuária sintética "ana" (padrão)
#   TOKEN=$(./infrastructure/scripts/local-token.sh bruno)    # segundo usuário (teste de isolamento)
#   curl -i -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/users/me
#
# Credenciais e issuer vêm de backend/.env (modelo: backend/.env.example).
# O token expira em 5 minutos (accessTokenLifespan do realm).

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$ROOT_DIR/backend/.env"

if [[ ! -f "$ENV_FILE" ]]; then
	echo "Arquivo $ENV_FILE não encontrado. Copie backend/.env.example para backend/.env." >&2
	exit 1
fi

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

user_key="$(printf '%s' "${1:-ana}" | tr '[:lower:]' '[:upper:]')"
username_var="SHF_DEV_${user_key}_USERNAME"
password_var="SHF_DEV_${user_key}_PASSWORD"
username="${!username_var:-}"
password="${!password_var:-}"

if [[ -z "$username" || -z "$password" ]]; then
	echo "Usuário '${1:-ana}' não configurado: defina $username_var e $password_var em backend/.env." >&2
	exit 1
fi

response="$(curl -sS -m 10 -X POST "${OIDC_ISSUER_URI:?}/protocol/openid-connect/token" \
	-d grant_type=password \
	-d "client_id=${SHF_DEV_CLIENT_ID:-shf-web}" \
	-d scope=openid \
	--data-urlencode "username=$username" \
	--data-urlencode "password=$password")"

token="$(printf '%s' "$response" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"

if [[ -z "$token" ]]; then
	error="$(printf '%s' "$response" | sed -n 's/.*"error_description":"\([^"]*\)".*/\1/p')"
	echo "Falha ao obter token do Keycloak: ${error:-resposta inesperada}" >&2
	exit 1
fi

printf '%s\n' "$token"
