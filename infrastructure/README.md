# Infraestrutura

| Ambiente | Arquivo | Variáveis |
|---|---|---|
| Local | `docker-compose.yml` | `backend/.env` (modelo: `backend/.env.example`) |
| CI | `.github/workflows/ci.yml` (Testcontainers sobe PostgreSQL, RabbitMQ e Redis nos testes de integração — ADR-0010) | nenhuma (sem segredos) |
| Staging | `staging/compose.yml` + `backend/Dockerfile` + `frontend/Dockerfile` (ADR-0044) | `/etc/shf/staging.env` no host (modelo: `.env.staging.example`) |
| Production | ainda não existe (M14) | — |

## Local

Pré-requisitos: Docker, JDK 25.

1. Crie `backend/.env` a partir de `backend/.env.example` e preencha os valores em branco (usuários e senhas de
   desenvolvimento de sua escolha). Nenhuma credencial é versionada: sem o `.env` preenchido, o Compose e a API
   falham na inicialização indicando a variável que falta.
2. Suba a infraestrutura (na raiz do repositório):

   ```bash
   docker compose --env-file backend/.env -f infrastructure/docker-compose.yml up -d
   ```

   Observabilidade opcional (Grafana em http://localhost:3001; troque `OTEL_EXPORT_ENABLED=true` no `.env`):

   ```bash
   docker compose --env-file backend/.env -f infrastructure/docker-compose.yml --profile observability up -d
   ```

3. Rode a API a partir de `backend/` com o perfil `local`:

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```

| Serviço | Endereço |
|---|---|
| API | http://localhost:8080 (Swagger: `/swagger-ui.html`) |
| PostgreSQL | localhost:5432 |
| Redis | localhost:6379 |
| RabbitMQ | localhost:5672 — console: http://localhost:15672 |
| Keycloak | http://localhost:8180 — realm `smart-health-finance` |
| Grafana (opcional) | http://localhost:3001 |

O realm `keycloak/realm-smart-health-finance.json` cria dois usuários sintéticos cujos usuário e senha vêm de
`SHF_DEV_*` no `backend/.env` (placeholders `${...}` resolvidos no import). O realm só é reimportado quando o
container do Keycloak é recriado; após alterar essas variáveis:

```bash
docker compose --env-file backend/.env -f infrastructure/docker-compose.yml up -d --force-recreate keycloak
```

Token para testar a API (expira em 5 minutos):

```bash
TOKEN=$(./infrastructure/scripts/local-token.sh)
```

Para zerar os dados locais (o `--env-file` é necessário porque as credenciais são obrigatórias no Compose):

```bash
docker compose --env-file backend/.env -f infrastructure/docker-compose.yml down -v
```

## Staging

Host único no AWS Lightsail (São Paulo) com Docker Compose (ADR-0044). Só o Caddy publica portas (80/443, TLS
automático); PostgreSQL, Redis, RabbitMQ, API e web ficam na rede interna; Grafana, Mailpit e o console do Keycloak
só em 127.0.0.1 do host, pelo túnel SSH. Deploy manual por SHA e digest (workflow **Deploy staging**), com imagens do
workflow **Images** (GHCR, Trivy, SBOM). Staging nunca recebe dados reais: só o seed fictício.

| Runbook | Conteúdo |
|---|---|
| `docs/runbooks/staging.md` | preparação do host (passo a passo), túnel, operação, alertas |
| `docs/runbooks/staging-deploy.md` | deploy por digest e rollback |
| `docs/runbooks/staging-backup-restore.md` | backup, teste de restore, reset e seed |
| `docs/runbooks/keycloak.md` | realm de staging, verificações do IdP |

Produção: decidida e criada no M14.
