#!/bin/bash
# Endurecimento do host de staging (ADR-0044 §9). Uma vez, pelo administrador, depois de instalar o Docker:
#   sudo infrastructure/staging/harden-host.sh <usuario-admin>
# Antes de rodar, confirme que você entra por chave SSH com <usuario-admin> (no Lightsail: ubuntu).
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "execute com sudo" >&2; exit 1; }
admin=${1:?informe o usuário de administração (ex.: ubuntu)}
id "$admin" >/dev/null || { echo "usuário $admin não existe" >&2; exit 1; }
[[ -s "/home/$admin/.ssh/authorized_keys" ]] || { echo "$admin não tem chave SSH; abortando para não te trancar fora" >&2; exit 1; }

# SSH: só chave, sem root, só o admin e o deploy.
cat >/etc/ssh/sshd_config.d/10-shf.conf <<EOF
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
PubkeyAuthentication yes
PermitUserEnvironment no
AllowUsers $admin deploy
X11Forwarding no
MaxAuthTries 3
LoginGraceTime 30
EOF
sshd -t
systemctl reload ssh

# Atualizações de segurança automáticas (o reboot fica a seu critério).
apt-get update -q
DEBIAN_FRONTEND=noninteractive apt-get install -y -q unattended-upgrades
dpkg-reconfigure -f noninteractive unattended-upgrades

# Auditoria mínima: journald persistente (logins SSH, sudo, deploy gate).
install -d -m 2755 -o root -g systemd-journal /var/log/journal
systemctl restart systemd-journald

echo "Host endurecido. Firewall: mantenha no Lightsail só 22, 80 e 443 (TCP)."
