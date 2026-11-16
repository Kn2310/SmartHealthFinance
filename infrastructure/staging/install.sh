#!/bin/bash
# Instala/atualiza os arquivos do host de staging a partir de um checkout REVISADO da main (ADR-0044 §5 item 23).
# Executado pelo administrador (nunca pelo deploy), na raiz de um clone do repositório:
#
#   git fetch origin && git checkout --detach <sha-revisado>
#   sudo infrastructure/staging/install.sh
#
# O que faz (idempotente):
# - copia compose, Caddyfile, realm, init do PostgreSQL, observabilidade, seed e lib para /opt/shf (root, sem escrita
#   para outros);
# - instala shf-deploy-gate, shf-deploy, shf-backup, shf-restore-test, shf-seed e shf-reset em /usr/local/bin;
# - cria o usuário `deploy` (sem senha, fora do grupo docker) e o sudoers que só permite o shf-deploy;
# - instala e habilita o timer diário de backup;
# - registra a instalação (SHA) em /var/lib/shf/deployments.log.
# NÃO cria nem lê segredos: /etc/shf/staging.env é criado por você (modelo: infrastructure/.env.staging.example).
set -euo pipefail
umask 022

[[ $EUID -eq 0 ]] || { echo "execute com sudo" >&2; exit 1; }
src=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$src/../.." && pwd)

sha=$(git -C "$repo" rev-parse HEAD)
if [[ -n "$(git -C "$repo" status --porcelain)" ]]; then
	echo "o checkout tem alterações locais; instale só de um commit revisado e limpo" >&2
	exit 1
fi
echo "Instalando a infraestrutura do staging do commit $sha"
read -r -p "Confere com o SHA revisado? [s/N] " answer
[[ "$answer" == "s" || "$answer" == "S" ]] || { echo "cancelado"; exit 1; }

# /opt/shf: substituído inteiro, a partir do repositório.
install -d -m 755 -o root -g root /opt/shf
for item in compose.yml Caddyfile deploy.conf; do
	install -m 644 -o root -g root "$src/$item" "/opt/shf/$item"
done
for dir in keycloak postgres-init observability seed bin; do
	rm -rf "/opt/shf/$dir"
	cp -r "$src/$dir" "/opt/shf/$dir"
done
chown -R root:root /opt/shf
find /opt/shf -type d -exec chmod 755 {} +
find /opt/shf -type f -exec chmod 644 {} +
chmod 755 /opt/shf/postgres-init/*.sh

for script in shf-deploy-gate shf-deploy shf-backup shf-restore-test shf-seed shf-reset; do
	install -m 755 -o root -g root "$src/bin/$script" "/usr/local/bin/$script"
done

# Usuário de deploy: sem senha, sem shell interativo útil, fora do grupo docker.
if ! id deploy >/dev/null 2>&1; then
	useradd --system --create-home --shell /bin/bash deploy
	passwd -l deploy >/dev/null
fi
if id -nG deploy | tr ' ' '\n' | grep -qx docker; then
	gpasswd -d deploy docker
fi
# .ssh e authorized_keys do root (o sshd aceita): o `deploy` nunca consegue tirar o comando forçado da própria chave.
install -d -m 755 -o root -g root /home/deploy/.ssh
touch /home/deploy/.ssh/authorized_keys
chown root:root /home/deploy/.ssh/authorized_keys
chmod 644 /home/deploy/.ssh/authorized_keys

sudoers_tmp=$(mktemp)
install -m 440 "$src/system/sudoers-shf-deploy" "$sudoers_tmp"
visudo -cf "$sudoers_tmp" >/dev/null
install -m 440 -o root -g root "$sudoers_tmp" /etc/sudoers.d/shf-deploy
rm -f "$sudoers_tmp"

# Diretórios de estado e segredos.
install -d -m 700 -o root -g root /etc/shf /var/backups/shf
install -d -m 750 -o root -g root /var/lib/shf

for unit in shf-backup.service shf-backup.timer; do
	install -m 644 -o root -g root "$src/system/$unit" "/etc/systemd/system/$unit"
done
systemctl daemon-reload
systemctl enable --now shf-backup.timer >/dev/null

printf '%s INSTALL sha=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$sha" >>/var/lib/shf/deployments.log

if [[ ! -f /etc/shf/staging.env ]]; then
	echo "Falta /etc/shf/staging.env (veja docs/runbooks/staging.md, passo 'env file')."
fi
if [[ -s /home/deploy/.ssh/authorized_keys ]] \
	&& grep -vqE '^restrict,command="/usr/local/bin/shf-deploy-gate" ' /home/deploy/.ssh/authorized_keys; then
	echo "ATENÇÃO: há chave no /home/deploy/.ssh/authorized_keys sem 'restrict,command=\"/usr/local/bin/shf-deploy-gate\"'." >&2
	exit 1
fi
if [[ ! -s /home/deploy/.ssh/authorized_keys ]]; then
	echo "Falta a chave do deploy em /home/deploy/.ssh/authorized_keys (veja o runbook)."
fi
echo "Instalação concluída ($sha). Para aplicar mudanças de compose/Caddyfile, rode um deploy do SHA atual."
