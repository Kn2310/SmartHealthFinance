# Runbook — Staging: preparação do host, operação e alertas

Referência: ADR-0044 (staging, imagens e deploy por digest), ADR-0043 (Keycloak), ADR-0042 §4 (alertas do
Copilot), specs 05.11 e 05.12. Runbooks ligados: `staging-deploy.md` (deploy e rollback),
`staging-backup-restore.md` (backup, restore e reset) e `keycloak.md` (IdP).

**Staging nunca recebe dados reais** (05.11). Só os usuários sintéticos `@example.com` do seed. Se aparecer um dado
real, zere o ambiente (`staging-backup-restore.md#reset`).

## Topologia

```text
internet ─ 80/443 ─► caddy ─┬─ app.staging.<domínio>  → web:3000 (Next.js, NODE_ENV=production)
                            └─ auth.staging.<domínio> → keycloak:8080 (só /realms/smart-health-finance/* e /resources/*)
rede interna shf-staging: web → api:8080 · api → postgres, redis, rabbitmq · api/web/seed → caddy (alias do IdP)
só em 127.0.0.1 do host (túnel SSH): Grafana :3001 · Mailpit :8025 · console do Keycloak :8180
```

| Caminho no host | Conteúdo | Dono / modo |
|---|---|---|
| `/opt/shf/` | compose, Caddyfile, realm, observabilidade, seed (instalados pelo `install.sh`) | root, 755/644 |
| `/usr/local/bin/shf-*` | gate, deploy, backup, restore test, seed, reset | root, 755 |
| `/etc/shf/staging.env` | segredos do ambiente (criado por você) | root:root 600 |
| `/etc/shf/staging.images` | digests em uso (escrito só pelo `shf-deploy`) | root:root 600 |
| `/var/lib/shf/deployments.log` | instalações, deploys, resets | root, 640 |
| `/var/backups/shf/` | `pg_dump` diários (7 dias) | root, 700 |

## Preparação (uma vez, feita por você)

Tudo abaixo tem custo, cria conta ou envolve credencial: só você executa. Nenhum valor real entra no repositório.

### 1. Conta AWS e orçamento

1. Crie a conta AWS (ou use a sua). Ative **MFA na conta raiz** e crie um usuário IAM para o dia a dia.
2. Em **Billing → Budgets**, crie um orçamento mensal com alerta por e-mail (ex.: 80% de US$ 120).

### 2. Instância Lightsail

1. Lightsail → **Create instance** → região **São Paulo (sa-east-1)** → Linux/Unix → **OS Only → Ubuntu 24.04 LTS**.
2. Plano **16 GB RAM / 4 vCPUs** (confira o preço exibido no console).
3. Chave SSH: use uma chave **sua** (ed25519), não a padrão do Lightsail compartilhada.
4. Ative **Automatic snapshots** (diários; o Lightsail guarda os 7 últimos).
5. **Networking → Create static IP** e anexe à instância.
6. **Networking → IPv4 Firewall**: deixe só **SSH 22**, **HTTP 80** e **HTTPS 443** (TCP). Remova qualquer outra regra.
   Em IPv6, as mesmas regras (ou desligue o IPv6).

### 3. DNS

No provedor do seu domínio, crie registros **A** (e **AAAA**, se usar IPv6) para o IP estático:

- `app.staging.<domínio>`
- `auth.staging.<domínio>`

Confira com `nslookup app.staging.<domínio>` antes do primeiro deploy (o Caddy precisa do DNS para emitir o
certificado).

### 4. Docker e endurecimento

Na instância, como `ubuntu`:

```bash
sudo apt-get update && sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update && sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
```

```bash
git clone https://github.com/Kn2310/SmartHealthFinance.git ~/shf && cd ~/shf
git checkout --detach <sha-revisado-da-main>
sudo infrastructure/staging/harden-host.sh ubuntu
sudo infrastructure/staging/install.sh
```

O `harden-host.sh` desliga senha e root no SSH, limita o login a `ubuntu` e `deploy`, liga atualizações automáticas
de segurança e o journald persistente. O `install.sh` mostra o SHA e pede confirmação.

### 5. Env file

```bash
sudo install -m 600 -o root -g root infrastructure/.env.staging.example /etc/shf/staging.env
sudoedit /etc/shf/staging.env
```

- Troque todo `CHANGE_ME` (gere cada segredo com `openssl rand -hex 32`; senhas dos usuários sintéticos com 12+
  caracteres).
- `SEED_*_USERNAME` precisam terminar em `@example.com` (o `shf-seed` recusa outra coisa).
- `TELEGRAM_*`: passo 6.

### 6. Bot do Telegram (canal de alertas)

1. No Telegram, fale com **@BotFather** → `/newbot` → guarde o token em `TELEGRAM_BOT_TOKEN` (só no env file).
2. Crie um grupo só seu (ou use o chat direto com o bot), mande uma mensagem e descubra o chat id em
   `https://api.telegram.org/bot<TOKEN>/getUpdates` (abra no seu navegador; não cole o token em outro lugar).
3. Grave em `TELEGRAM_CHAT_ID`.

A mensagem de alerta leva só nome, prioridade, resumo e runbook (ADR-0044 §7).

### 7. Chave de deploy e GitHub Environment

1. Na **sua máquina**, gere uma chave só para o deploy:
   `ssh-keygen -t ed25519 -f shf-staging-deploy -C github-actions-staging -N ""`.
2. No host, adicione a pública com o comando forçado:

   ```bash
   echo 'restrict,command="/usr/local/bin/shf-deploy-gate" '"$(cat shf-staging-deploy.pub)" | sudo tee /home/deploy/.ssh/authorized_keys
   ```

3. Fixe a chave do host: `ssh-keyscan -t ed25519 <ip-estático>` (confira o fingerprint com
   `sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` no host).
4. GitHub → **Settings → Environments → New environment `staging`**:
   - **Required reviewers**: você. **Deployment branches**: só `main`.
   - Secrets: `STAGING_SSH_PRIVATE_KEY` (conteúdo de `shf-staging-deploy`), `STAGING_SSH_KNOWN_HOSTS` (saída do
     `ssh-keyscan`).
   - Variables: `STAGING_SSH_HOST` (IP estático), `STAGING_APP_HOST`, `STAGING_AUTH_HOST`.
5. Apague a chave privada da sua máquina depois de cadastrá-la (ela só precisa existir no Environment).

### 8. Pacotes públicos no GHCR

Depois do primeiro push na `main` com o workflow **Images** verde: GitHub → seu perfil → **Packages** →
`smart-health-finance-api` e `smart-health-finance-web` → **Package settings → Change visibility → Public**. O host
faz `pull` sem credencial (ADR-0044 §3 item 15).

### 9. Primeiro deploy e seed

1. Actions → **Deploy staging** → Run workflow → SHA da `main` (com CI, E2E e Images verdes) → aprove no Environment.
2. No host: `sudo shf-seed`.
3. Abra `https://app.staging.<domínio>` e entre com a usuária sintética do seed.
4. Faça o primeiro teste de restore (`staging-backup-restore.md`).

## Operação

### Túnel SSH para as ferramentas internas

```bash
ssh -L 3001:127.0.0.1:3001 -L 8025:127.0.0.1:8025 -L 8180:127.0.0.1:8180 ubuntu@<ip-estático>
```

| Ferramenta | Endereço (com o túnel) | Login |
|---|---|---|
| Grafana | http://127.0.0.1:3001 | `admin` / `GRAFANA_ADMIN_PASSWORD` |
| Mailpit | http://127.0.0.1:8025 | — (e-mails do Keycloak; nunca entregues) |
| Console do Keycloak | http://127.0.0.1:8180/admin | `KEYCLOAK_ADMIN_*` |

### Estado e logs

```bash
sudo docker compose -p shf-staging ps
sudo docker compose -p shf-staging logs --tail=200 api web caddy
sudo tail -n 20 /var/lib/shf/deployments.log
journalctl -t shf-deploy-gate --since today
```

Os logs da API e do BFF não têm tokens, e-mails nem valores (testes de logs e ADR-0007). Não copie logs do Keycloak
para fora do host.

### Copilot (feature flag)

`COPILOT_ENABLED` e `COPILOT_PROVIDER` ficam no env file. Depois de mudar, aplique com um redeploy do SHA atual
(Actions → Deploy staging) ou, no host, `sudo shf-deploy <sha> <digest-api> <digest-web>` com os valores da última
linha `OK` do `deployments.log`. O provedor real (`anthropic`) só na Parte E, por você: `copilot-real-validation.md`.

### Atualizar arquivos do host (compose, Caddyfile, scripts, observabilidade)

Mudanças em `infrastructure/staging/` entram por PR. Depois do merge: no clone do host,
`git fetch && git checkout --detach <sha>` → `sudo infrastructure/staging/install.sh` → redeploy do SHA. O deploy
nunca traz esses arquivos sozinho (ADR-0044 §5 item 23).

## Alertas de saúde

Regras em `infrastructure/staging/observability/grafana/provisioning/alerting/shf-alert-rules.yaml` (as do Copilot são
a conversão do `alerts.yml`, ADR-0042; teste `StagingObservabilityAssetsTest`).

| Alerta | Prioridade | O que fazer |
|---|---|---|
| `ApiScrapeDown` | P1 | `docker compose -p shf-staging ps api`; logs da API (Flyway, banco, OOM). Se começou após um deploy, rollback (`staging-deploy.md`). |
| `ApiHigh5xxRate` | P2 | Logs da API pelo `traceId` no Grafana (Tempo/Loki). Deploy recente → rollback. |
| `DeadLetterQueueNotEmpty` | P2 | Fila na label `queue`; siga o runbook do módulo (`import-dlq.md`, `analytics-rebuild.md`, `health-rebuild.md`, `forecast-rebuild.md`, `insights-rebuild.md`, `copilot.md`). |
| `OutboxBacklog` | P2 | RabbitMQ no ar? (`docker compose -p shf-staging ps rabbitmq`); `analytics-rebuild.md`. |
| `ReadModelFreshnessStale` | P3 | `health-rebuild.md#sinais` (e os de Forecast e Insights). |

## Custo de referência

Lightsail 16 GB (São Paulo): conferir no console (referência EUA: US$ 84/mês) · snapshots: poucos dólares/mês ·
domínio: ~US$ 10–15/ano · GHCR, Let's Encrypt, Telegram e Mailpit: sem custo. Copilot: à parte (Parte E).
