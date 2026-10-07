# Frontend — Smart Health Finance

Next.js (App Router) + TypeScript, CSS Modules + CSS variables, tokens/fontes de `design/`.
Decisões: [ADR-0007](../docs/adr/ADR-0007-frontend-foundation-and-bff-auth.md) (BFF, sessão, CSP, contrato) e
[ADR-0008](../docs/adr/ADR-0008-production-logout.md) (logout).

## Rodando localmente

Pré-requisitos: Node 22+, pnpm 10, infraestrutura e API no ar (veja `infrastructure/README.md`).

```bash
cd frontend
cp .env.example .env.local   # preencha SESSION_SECRET (>= 32 caracteres)
pnpm install
pnpm dev                     # http://localhost:3000
```

Ao abrir `/home` sem sessão, o app redireciona ao Keycloak (usuários `SHF_DEV_*` do `backend/.env`); após o login o BFF
provisiona o usuário, resolve o Workspace pessoal e abre a Home.

Build de produção na própria máquina (`pnpm build && pnpm start`): em produção o app **não sobe** com
`APP_BASE_URL` em `http://`. Para `http://localhost`, defina `SHF_ALLOW_INSECURE_LOCALHOST=true` no `.env.local`
(só vale para host de loopback).

## Comandos

| Comando | O que faz |
|---|---|
| `pnpm lint` / `pnpm typecheck` | ESLint (`eslint-config-next`) / `tsc --noEmit` |
| `pnpm test` | Vitest + Testing Library + axe-core (inclui drift do contrato e conformidade com o DS) |
| `pnpm build` | Build de produção |
| `pnpm sync:design` | Copia tokens/fontes de `design/` e gera tipografia e grid do `tokens.json` (roda sozinho antes de dev/build/lint/test/typecheck) |
| `pnpm api:generate` | Gera `src/lib/api/openapi.generated.d.ts` do Swagger (`OPENAPI_URL`, padrão `http://localhost:8080/v3/api-docs`) — só para comparação |

## Contrato da API

`src/lib/api/schema.d.ts` é o contrato **manual e temporário** usado pelo app: o OpenAPI atual expõe enums como
`string` e não declara nulos, o que apagaria a distinção null ≠ zero. `src/lib/api/contract.test.ts` compara o
contrato com os records e enums Java do backend e falha se houver drift. O arquivo gerado por `api:generate` não é
importado pelo app.

## Estrutura

```text
src/proxy.ts            CSP com nonce por requisição
src/instrumentation.ts  valida a configuração ao iniciar (produção exige HTTPS)
src/app/                rotas: (app)/home, auth/{login,callback,logout,signed-out,error}, api/bff/overview
src/features/home/      HomeView → HomeContent → BalanceHero, CashFlowSummary, AccountsSummary, RecentTransactions
src/components/         ui/ (Button, Card, Money, Skeleton) e shell/ (sidebar, trilho, bottom nav)
src/lib/                api/ (contrato), format/ (dinheiro e datas), security/ (CSP), overview-query
src/server/             BFF: env, sessão selada, OIDC, cliente da API, respostas no-store (server-only)
src/test/               helpers de teste (cookies/fetch falsos, axe)
```
