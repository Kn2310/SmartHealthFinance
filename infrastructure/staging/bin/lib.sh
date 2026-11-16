#!/bin/bash
# Constantes usadas pelos scripts que fazem `source` deste arquivo.
# shellcheck disable=SC2034
# Funções comuns dos scripts shf-* do staging (ADR-0044). Instalado em /opt/shf/bin/lib.sh (root, 0644).
# Nunca imprime o conteúdo do env file. Caminhos fixos: os scripts rodam como root e não aceitam override pelo
# ambiente.

readonly SHF_DIR=/opt/shf
readonly SHF_ENV_FILE=/etc/shf/staging.env
readonly SHF_IMAGES_FILE=/etc/shf/staging.images
readonly SHF_STATE_DIR=/var/lib/shf
readonly SHF_DEPLOY_LOG="$SHF_STATE_DIR/deployments.log"
readonly SHF_BACKUP_DIR=/var/backups/shf

shf_log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
shf_die() { shf_log "ERRO: $*" >&2; exit 1; }

shf_require_root() { [[ $EUID -eq 0 ]] || shf_die "execute como root (sudo)"; }

# O env file precisa existir, ser do root e ter permissão 600 (ADR-0044 §6 item 28).
shf_check_secret_file() {
	local file=$1
	[[ -f "$file" ]] || shf_die "arquivo ausente: $file"
	local mode owner
	mode=$(stat -c '%a' "$file")
	owner=$(stat -c '%U:%G' "$file")
	[[ "$mode" == "600" && "$owner" == "root:root" ]] || shf_die "$file precisa ser root:root 600 (está $owner $mode)"
}

shf_compose() {
	docker compose -p shf-staging --project-directory "$SHF_DIR" -f "$SHF_DIR/compose.yml" \
		--env-file "$SHF_ENV_FILE" --env-file "$SHF_IMAGES_FILE" "$@"
}

# Lê UMA variável do env file sem carregar as outras no ambiente do script. Tira CR e aspas envolventes, como o
# compose faz.
shf_env_value() {
	local key=$1 value
	value=$(sed -n "s/^${key}=//p" "$SHF_ENV_FILE" | tail -n 1 | tr -d '\r')
	if [[ "$value" =~ ^\"(.*)\"$ || "$value" =~ ^\'(.*)\'$ ]]; then
		value=${BASH_REMATCH[1]}
	fi
	printf '%s' "$value"
}

shf_record() {
	install -d -m 750 -o root -g root "$SHF_STATE_DIR"
	printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >>"$SHF_DEPLOY_LOG"
}

readonly SHF_SHA_RE='^[0-9a-f]{40}$'
readonly SHF_DIGEST_RE='^sha256:[0-9a-f]{64}$'
