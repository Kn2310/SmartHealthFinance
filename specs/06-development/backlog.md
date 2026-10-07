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
- [ ] eventos `transaction.*` (depende do Transactional Outbox)

## M3 First Home

- [x] balance calculation (derivado de transações POSTED — ADR-0006)
- [x] overview query (`GetFinancialOverview`, ADR-0006)
- [x] Home API — bloco Overview em `GET /api/v1/workspaces/{id}/overview` (o composto `/api/v1/home` entra com Health/Insights)
- [ ] Health placeholder/first implementation
- [ ] Home UI
- [ ] responsive states

## M4 Import

- [ ] CSV parser
- [ ] OFX parser
- [ ] import batch
- [ ] import records
- [ ] dedupe
- [ ] async job
- [ ] status API
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
