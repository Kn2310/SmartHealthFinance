# Development Strategy

## Fases

```text
06 Foundation
07 Financial Core
08 Data Ingestion
09 Financial Intelligence
10 Insights + Health
11 Credit Cards
12 Copilot
13 Monetization
14 Hardening
MVP Release
```

## Estratégia

Vertical slices.

```text
Domain
 ↓
Application
 ↓
Database
 ↓
API
 ↓
Event
 ↓
Tests
 ↓
Frontend
 ↓
Observability
```

## First Financial Loop

```text
Workspace
 ↓
Account
 ↓
Transaction
 ↓
Balance
 ↓
Home
```

## Foundation

- monorepo
- Spring Boot
- Next.js
- PostgreSQL
- Redis
- RabbitMQ
- Docker Compose
- Flyway
- Spring Security
- OpenAPI
- OpenTelemetry
- Testcontainers
- ArchUnit
- CI/CD
- Design System foundation
- ADR template

## Milestones

M0 Foundation
M1 Workspace + Accounts
M2 Transactions
M3 First Home
M4 CSV/OFX Import
M5 Analytics
M6 Financial Health
M7 Insights
M8 Credit Cards
M9 Forecast
M10 Goals
M11 Copilot
M12 Monetization
M13 Security/Privacy
M14 MVP

## Dupla

Sugestão inicial:
- Pessoa A: backend/domain/application/API/data/events/AI
- Pessoa B: frontend/design system/UX/screens/API integration

Mas as duas pessoas devem trabalhar em vertical slices e integrar frequentemente.

## Regra

Evitar longas fases isoladas de backend e frontend. O produto deve ganhar comportamento real continuamente.
