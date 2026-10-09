# Development Backlog

## M0 Foundation

- [x] monorepo (`backend/`, `frontend/`, `infrastructure/`, `design/`, `specs/`, `docs/adr/` — PR #1)
- [x] Docker Compose (`infrastructure/docker-compose.yml` local, perfis `app` e `observability`;
  `docker-compose.deploy.yml` para staging/produção — PR #1)
- [x] Spring Boot (4.1, Java 25, monólito modular — ADR-0001, PR #1)
- [x] Next.js (App Router + BFF — ADR-0007, PR #7)
- [x] PostgreSQL (17, fonte de verdade — PR #1)
- [x] Redis (infra local/deploy e readiness da API; ainda sem uso funcional — nunca fonte de verdade, specs 05.5)
- [x] RabbitMQ (Transactional Outbox + Import Worker com retry e DLQ — ADR-0009 §14/§15, PR #10)
- [x] Flyway (`V1`–`V8`, `ddl-auto: validate`)
- [x] Spring Security (OAuth2 Resource Server sobre o IdP OIDC — ADR-0002, PR #1)
- [x] OpenAPI (springdoc code-first, `/v3/api-docs` desligado por padrão; o frontend usa contrato manual com teste
  de drift — ADR-0007 §11)
- [x] OpenTelemetry (starter + export OTLP opcional, Grafana LGTM no perfil `observability` — PR #1)
- [x] Testcontainers (PostgreSQL, RabbitMQ e Redis reais nos `*IT` — `support/TestcontainersConfiguration`)
- [x] ArchUnit (`architecture/ArchitectureTest` — ADR-0001)
- [x] CI (`.github/workflows/ci.yml`: backend `./mvnw verify` + frontend lint/typecheck/test/build, snapshot do
  contrato OpenAPI verificado dos dois lados — ADR-0010)
- [x] Design System foundation (`design/` como fonte de tokens/fontes, `scripts/sync-design.mjs`, componentes
  `components/ui` — ADR-0007 §13, PR #7)
- [x] ADR template (`specs/06-development/adr-template.md`)

## M1 Workspace + Accounts

- [x] Workspace (pessoal automático no provisionamento — ADR-0003, PR #2)
- [x] membership (`WorkspaceAccessGuard.requireMember`, regra do 404 — ADR-0003, PR #2)
- [ ] roles/permissions (MVP: apenas OWNER — ADR-0003; papéis adicionais exigem revisão do ADR)
- [x] Account aggregate (`accounts.domain.model.Account` — ADR-0004, PR #4)
- [x] account persistence (`V4__create_accounts.sql`, `JpaAccountRepository`, lock otimista por `version` — PR #4)
- [x] create/update account (`CreateAccount`, `UpdateAccount`, `ArchiveAccount`, `ReactivateAccount` — PR #4)
- [x] account API (`/api/v1/workspaces/{id}/accounts`: listar, consultar, criar, editar com lock otimista, arquivar/reativar — ADR-0004, PR #4)
- [x] account UI (`/accounts` lista com saldo do Overview e filtro de arquivadas, `/accounts/new` com retorno para `/import`, `/accounts/{id}` edição + arquivar/reativar; BFF `/api/bff/accounts[/{id}[/archive|/reactivate]]` — ADR-0004 §11, PR #12)
- [x] movimentações por conta em `/accounts/{id}` (D-AccountDetail): lista de transações filtrada pela conta, com "Nova transação" (ADR-0005 §16, PR #13)
- [x] saldo inicial em `/accounts/new` como `ADJUSTMENT` orquestrado pelo BFF, com retry idempotente (ADR-0004 §12, PR #13)
- [ ] isolation tests (hoje espalhados por `*ApiIT`, `JdbcOverviewReadModelIT` e `ImportApiIT`; falta a suíte dedicada
  cobrindo todas as rotas `/workspaces/{id}`)

## M2 Transactions

- [x] Transaction aggregate (ADR-0005)
- [x] money VO (`shared.domain.Money`)
- [x] transaction types
- [x] status
- [x] dedupe (lançamento manual: Idempotency-Key + request hash; dedupe de import por external ID fica no M4)
- [x] transaction API
- [x] transaction list
- [x] filters
- [x] tests
- [x] transaction UI (`/transactions` com filtros, `/transactions/new` receita/despesa/ajuste com `Idempotency-Key` por intenção, `/transactions/{id}` efetivar/cancelar/estornar e editar descrição; BFF `/api/bff/transactions[/{id}[/post|/cancel|/reverse]]`; M-More mínimo — ADR-0005 §16)
- [ ] pendência: métricas do topo de D-Transactions (Entradas/Saídas/Transferências/Resultado do período filtrado) e "Por que meu dinheiro mudou?" (depende de categorias, M5)
- [ ] pendência: transferência e reembolso pela UI (a API já suporta; a UI só exibe)
- [ ] eventos `transaction.*` (a Transactional Outbox já existe desde o M4; falta o consumidor — ADR-0009 §15)

## M3 First Home

- [x] balance calculation (derivado de transações POSTED — ADR-0006)
- [x] overview query (`GetFinancialOverview`, ADR-0006)
- [x] Home API — bloco Overview em `GET /api/v1/workspaces/{id}/overview` (o composto `/api/v1/home` entra com Health/Insights)
- [ ] Health placeholder/first implementation
- [x] Home UI — Overview: saldo, fluxo do período, contas, movimentações recentes, seletor de período (ADR-0007/0008; critérios da etapa validados em 2026-10-07; Health/Insights ficam para M6/M7)
- [x] responsive states — READY, NO_ACCOUNTS, NO_TRANSACTIONS (com PENDING), NO_ACTIVITY_IN_PERIOD, loading, erro; 375/768/1024/1280/1440 sem overflow
- [x] E2E (Playwright) do First Financial Loop (saldo inicial → receita → despesa → saldo e fluxo exatos na Home) e da
  Home vazia (`NO_ACCOUNTS`) — `frontend/e2e/`, `e2e.yml`, ADR-0011
- [ ] dívida técnica: revisar a semântica de `recentTransactions` no Overview quando Transactions/Overview voltarem a evoluir (o ADR-0006 §13 diz que os recentes só são consultados com dados; o código os consulta sempre que há conta ativa, e devolve PENDING em `NO_TRANSACTIONS` — a Home já os exibe como pendentes)

## M4 Import

- [x] CSV parser (modelo canônico + autodetecção — ADR-0009 §6)
- [x] OFX parser (1.x SGML e 2.x XML, parser próprio — ADR-0009 §5)
- [x] import batch (PREVIEW → CONFIRMED → PROCESSING → COMPLETED/FAILED, CANCELLED, EXPIRED — ADR-0009 §2)
- [x] import records (lineage por linha; arquivo bruto nunca guardado)
- [x] dedupe (por conta: FITID+data+valor ou impressão digital com ocorrência — ADR-0009 §11)
- [x] async job (Transactional Outbox + RabbitMQ + Import Worker com inbox, retry e DLQ — ADR-0009 §14/§15)
- [x] status API (`/api/v1/workspaces/{id}/imports`, upload 201, confirm 202)
- [x] import UI (`/import`: conta + arquivo → preview → confirmação → acompanhamento → resultado; selo "Importado" na Home — ADR-0009 §18)
- [x] E2E da importação: CSV fictício → preview → confirmação → resultado → selo "Importado", com somas exatas do
  fixture (`frontend/e2e/import.spec.ts` — ADR-0011)

## M5 Analytics

- [ ] spending analysis
- [ ] income analysis
- [ ] categories
- [ ] merchants
- [ ] evolution
- [ ] read models

## M6 Health

- [ ] dimensions
- [ ] calculator
- [ ] algorithm version
- [ ] evidence
- [ ] freshness

## M7 Insights

- [ ] detector
- [ ] impact
- [ ] relevance
- [ ] confidence
- [ ] evidence
- [ ] insight UI

## M8 Cards

- [ ] card
- [ ] invoice
- [ ] installment
- [ ] utilization
- [ ] payment

## M9 Forecast

- [ ] cash flow forecast
- [ ] bill forecast
- [ ] freshness
- [ ] uncertainty

## M10 Goals

- [ ] goal
- [ ] contributions
- [ ] progress
- [ ] forecast
- [ ] simulation

## M11 Copilot

- [ ] AI Gateway
- [ ] context builder
- [ ] tools
- [ ] structured output
- [ ] claim validation
- [ ] usage ledger
- [ ] evaluation

## M12 Monetization

- [ ] plans
- [ ] subscriptions
- [ ] entitlements
- [ ] usage limits
- [ ] billing abstraction

## M13 Hardening

- [ ] security
- [ ] privacy
- [ ] deletion/export
- [ ] rate limits
- [ ] backup
- [ ] restore test
- [ ] resilience
- [ ] performance

## M14 Release

- [ ] staging
- [ ] production
- [ ] dashboards
- [ ] alerts
- [ ] runbooks
- [ ] release verification
