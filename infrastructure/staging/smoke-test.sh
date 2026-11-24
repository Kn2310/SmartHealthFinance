#!/bin/bash
# Smoke test externo do staging (ADR-0044 §5 item 27). Roda no workflow de deploy (runner do GitHub) ou da sua
# máquina:
#   STAGING_APP_HOST=app.staging.<domínio> STAGING_AUTH_HOST=auth.staging.<domínio> infrastructure/staging/smoke-test.sh
# Só lê cabeçalhos e status; não autentica nem imprime corpo de resposta.
set -euo pipefail

app=${STAGING_APP_HOST:?defina STAGING_APP_HOST}
auth=${STAGING_AUTH_HOST:?defina STAGING_AUTH_HOST}
failures=0

check() {
	local description=$1; shift
	if "$@"; then echo "ok   $description"; else echo "FALHA $description"; failures=$((failures + 1)); fi
}

status() { curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$1" || true; }
headers() { curl -s -D - -o /dev/null --max-time 20 "$1" | tr -d '\r'; }

app_headers=$(headers "https://$app/" || true)
if [[ -z "$app_headers" ]]; then
	echo "FALHA sem resposta de https://$app/ (DNS, firewall ou certificado ainda não emitido?)" >&2
	exit 1
fi
app_status=$(printf '%s\n' "$app_headers" | sed -n '1s/^HTTP\/[0-9.]* \([0-9]*\).*/\1/p')

check "app responde em HTTPS (status $app_status)" test -n "$app_status" -a "$app_status" -lt 500
check "app envia CSP com nonce" grep -qiE '^content-security-policy:.*nonce-' <<<"$app_headers"
check "app envia HSTS" grep -qi '^strict-transport-security: max-age=' <<<"$app_headers"
check "HTTP redireciona para HTTPS" grep -qE '^30[18]$' <<<"$(status "http://$app/")"

discovery=$(curl -s --max-time 20 "https://$auth/realms/smart-health-finance/.well-known/openid-configuration" || true)
check "discovery do realm com o issuer esperado" \
	grep -q "\"issuer\":\"https://$auth/realms/smart-health-finance\"" <<<"$discovery"
check "console de administração fechado (/admin → 404)" test "$(status "https://$auth/admin/")" = 404
check "realm master fechado (→ 404)" \
	test "$(status "https://$auth/realms/master/.well-known/openid-configuration")" = 404
check "health/metrics do IdP fechados (→ 404)" test "$(status "https://$auth/metrics")" = 404

if (( failures > 0 )); then
	echo "$failures verificação(ões) falharam" >&2
	exit 1
fi
echo "smoke test OK"
