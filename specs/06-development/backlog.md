# Development Backlog

## M0 Foundation

- [ ] monorepo
- [ ] Docker Compose
- [ ] Spring Boot
- [ ] Next.js
- [ ] PostgreSQL
- [ ] Redis
- [ ] RabbitMQ
- [ ] Flyway
- [ ] Spring Security
- [ ] OpenAPI
- [ ] OpenTelemetry
- [ ] Testcontainers
- [ ] ArchUnit
- [ ] CI
- [ ] Design System foundation
- [ ] ADR template

## M1 Workspace + Accounts

- [x] Workspace (pessoal automático no provisionamento — ADR-0003)
- [x] membership
- [ ] roles/permissions (MVP: apenas OWNER — ADR-0003)
- [ ] Account aggregate
- [ ] account persistence
- [ ] create/update account
- [ ] account API
- [ ] account UI
- [ ] isolation tests

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
- [ ] transaction UI
- [ ] eventos `transaction.*` (a Transactional Outbox já existe desde o M4; falta o consumidor — ADR-0009 §15)

## M3 First Home

- [x] balance calculation (derivado de transações POSTED — ADR-0006)
- [x] overview query (`GetFinancialOverview`, ADR-0006)
- [x] Home API — bloco Overview em `GET /api/v1/workspaces/{id}/overview` (o composto `/api/v1/home` entra com Health/Insights)
- [ ] Health placeholder/first implementation
- [x] Home UI — Overview: saldo, fluxo do período, contas, movimentações recentes, seletor de período (ADR-0007/0008; critérios da etapa validados em 2026-10-07; Health/Insights ficam para M6/M7)
- [x] responsive states — READY, NO_ACCOUNTS, NO_TRANSACTIONS (com PENDING), NO_ACTIVITY_IN_PERIOD, loading, erro; 375/768/1024/1280/1440 sem overflow
- [ ] dívida técnica: revisar a semântica de `recentTransactions` no Overview quando Transactions/Overview voltarem a evoluir (o ADR-0006 §13 diz que os recentes só são consultados com dados; o código os consulta sempre que há conta ativa, e devolve PENDING em `NO_TRANSACTIONS` — a Home já os exibe como pendentes)

## M4 Import

- [x] CSV parser (modelo canônico + autodetecção — ADR-0009 §6)
- [x] OFX parser (1.x SGML e 2.x XML, parser próprio — ADR-0009 §5)
- [x] import batch (PREVIEW → CONFIRMED → PROCESSING → COMPLETED/FAILED, CANCELLED, EXPIRED — ADR-0009 §2)
- [x] import records (lineage por linha; arquivo bruto nunca guardado)
- [x] dedupe (por conta: FITID+data+valor ou impressão digital com ocorrência — ADR-0009 §11)
- [x] async job (Transactional Outbox + RabbitMQ + Import Worker com inbox, retry e DLQ — ADR-0009 §14/§15)
- [x] status API (`/api/v1/workspaces/{id}/imports`, upload 201, confirm 202)
- [ ] import UI

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
