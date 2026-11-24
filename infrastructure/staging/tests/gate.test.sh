#!/bin/bash
# Testes do gate de deploy (ADR-0044 §5 item 23): só o formato exato passa; injeções são recusadas.
#   bash infrastructure/staging/tests/gate.test.sh
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=../bin/shf-deploy-gate
source "$here/../bin/shf-deploy-gate"

sha=0123456789abcdef0123456789abcdef01234567
digest=sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
failures=0

accepts() {
	local output
	if output=$(shf_gate_parse "$1") && [[ "$output" == "$2" ]]; then
		echo "ok    aceita: ${3:-$1}"
	else
		echo "FALHA deveria aceitar: ${3:-$1}"; failures=$((failures + 1))
	fi
}
rejects() {
	if shf_gate_parse "$1" >/dev/null; then
		echo "FALHA deveria recusar: $2"; failures=$((failures + 1))
	else
		echo "ok    recusa: $2"
	fi
}

accepts "deploy $sha $digest $digest" "$sha $digest $digest" "comando válido"

rejects "" "vazio"
rejects "deploy" "sem argumentos"
rejects "deploy $sha $digest" "faltando um digest"
rejects "deploy $sha $digest $digest extra" "argumento a mais"
rejects "deploy $sha $digest $digest; rm -rf /" "ponto e vírgula"
rejects "deploy $sha $digest \$(id)" "substituição de comando"
rejects "deploy $sha $digest \`id\`" "crase"
rejects "deploy $sha $digest $digest && id" "&&"
rejects "deploy $sha $digest $digest | sh" "pipe"
rejects "deploy $sha $digest $digest"$'\n'"id" "quebra de linha"
rejects " deploy $sha $digest $digest" "espaço inicial"
rejects "deploy  $sha $digest $digest" "espaço duplo"
rejects "DEPLOY $sha $digest $digest" "maiúsculas no verbo"
rejects "deploy ${sha^^} $digest $digest" "SHA em maiúsculas"
rejects "deploy ${sha:0:39} $digest $digest" "SHA curto"
rejects "deploy ${sha}0 $digest $digest" "SHA longo"
rejects "deploy $sha sha512:${digest#sha256:} $digest" "algoritmo diferente"
rejects "deploy $sha ${digest}0 $digest" "digest longo"
rejects "deploy $sha ghcr.io/evil/image@$digest $digest" "imagem no lugar do digest"
rejects "bash" "shell"
rejects "scp -t /tmp" "scp"
rejects "deploy $sha $digest $digest"$'\r' "CR no fim"

if (( failures > 0 )); then
	echo "$failures teste(s) falharam" >&2
	exit 1
fi
echo "gate: todos os testes passaram"
