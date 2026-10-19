---
name: frontend-tests
description: Escreve e roda os testes do frontend do Smart Health Finance (Vitest 5 + React Testing Library + user-event + axe-core para views, Vitest em ambiente node para Route Handlers do BFF, testes de contrato e de conformidade com o Design System, Playwright E2E contra o ambiente local completo). Use ao testar telas, hooks, rotas do BFF, formatação ou fluxos ponta a ponta no frontend.
---

# Frontend tests

Config: `frontend/vitest.config.mts` (jsdom, alias `@` → `src`, `server-only` vira módulo vazio, CSS Modules com nomes não escopados, `include: src/**/*.test.{ts,tsx}`), `vitest.setup.ts` (jest-dom + `cleanup`) e `playwright.config.ts`. Helpers em `src/test/` e `e2e/support/`.

## Como rodar (em `frontend/`)

```
corepack pnpm test                         # vitest run: suíte INTEIRA (obrigatório antes de push)
node_modules/.bin/vitest run src/features/xxx   # recorte durante o desenvolvimento
corepack pnpm typecheck && corepack pnpm lint
corepack pnpm e2e                          # Playwright: exige o compose completo no ar (ver abaixo)
```

Sempre rode a suíte inteira antes de push: `src/styles/design-conformance.test.ts` varre **todos** os CSS e já quebrou o CI com um `rgba(...)` que passou nos testes da feature.

## Regras do Vitest 5 (Context7, v5.0.3)

- `vi.mock(...)` e `vi.hoisted(...)` **só no topo do módulo**. O Vitest 5 lança erro se forem chamados dentro de `describe`/`it`. Os `import` do módulo testado vêm **depois** dos `vi.mock`.
- Ambiente por arquivo com comentário na 1ª linha: `// @vitest-environment node` para Route Handlers, `src/server/*` e testes que leem arquivos.
- Quem usa `vi.stubGlobal` restaura com `afterEach(() => vi.unstubAllGlobals())`.

## Views (RTL + user-event + axe)

Modelo: `src/features/accounts/Accounts.test.tsx`.

```tsx
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'

const push = vi.fn()
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  usePathname: () => '/xxx',
  useSearchParams: () => new URLSearchParams(),
}))

afterEach(() => vi.unstubAllGlobals())

it('mostra a lista', async () => {
  const calls = stubBff({ 'GET /api/bff/xxx': () => json({ items: [xxx()] }) })
  const { container } = render(<XxxView />)
  expect(await screen.findByRole('heading', { level: 1, name: 'Xxx' })).toBeInTheDocument()
  expect(await axeViolations(container)).toEqual([])
})
```

- **`stubBff(routes)`** troca o `fetch` global: chave `"MÉTODO prefixo"` (o prefixo mais longo vence), devolve `calls` para inspecionar `method`, `url` e `init.body`, e rota não mapeada responde 502 `UNAVAILABLE`. Nunca faça requisição real nem mocke `bffCall` diretamente.
- Fixtures com factory e `overrides` (`const xxx = (o: Partial<Xxx> = {}): Xxx => ({ ...base, ...o })`), ids UUIDv7 fixos e tipos do `schema.d.ts`. Fixtures compartilhadas ficam em `features/<x>/fixtures.ts` (ex.: `brl('6240.35')`, `readyOverview(...)` em `features/home/fixtures`).
- Consultas por **papel e nome acessível** (`getByRole`, `getByLabelText`, `findByText`). `data-testid` só em último caso. `within(...)` para escopo.
- Interação com `const user = userEvent.setup()` + `await user.type/click/selectOptions`.
- **Cubra todos os estados**: loading (`role="status"`), erro (`role="alert"` + "Tentar novamente" chama de novo o BFF), vazio, sucesso, parcial, erro de campo (`VALIDATION_FAILED` com `details` → mensagem e foco no primeiro campo) e 409/404 específicos.
- Verifique o que foi enviado ao BFF: `JSON.parse(String(calls[0].init?.body))`, só com os campos esperados e com dinheiro em string.
- `axeViolations(container)` deve ser `[]` em cada estado principal. Ele desliga `color-contrast` (o jsdom não calcula cor; o contraste é garantido pelos tokens) e `region`.
- Valores monetários: verifique o texto formatado ("R$ 6.240,35") e o texto falado para o leitor de tela (`sr-only`, ex.: "menos R$ 420,00").

## Route Handlers do BFF (`// @vitest-environment node`)

Modelo: `src/app/api/bff/accounts/route.test.ts`.

```ts
// @vitest-environment node
import { NextRequest } from 'next/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { stubServerEnv } from '@/test/server-helpers'

const getFreshSession = vi.fn()
const GET_BACKEND = vi.fn()
vi.mock('@/server/auth/session', () => ({ getFreshSession: () => getFreshSession() }))
vi.mock('@/server/backend', () => ({ backendClient: () => ({ GET: GET_BACKEND }) }))

import { GET } from './[xxxId]/route'

const params = (xxxId = ID) => ({ params: Promise.resolve({ xxxId }) })   // params é Promise no Next 16

beforeEach(() => {
  stubServerEnv()
  getFreshSession.mockReset().mockResolvedValue({ status: 'ok', session })
})
```

Casos obrigatórios por rota:
- sem sessão → 401 `UNAUTHENTICATED`; IdP indisponível ou exceção → 502 `UNAVAILABLE`;
- mutação sem `Origin` ou com `Origin` diferente de `APP_BASE_URL` → 403 `FORBIDDEN`, **sem chamar o backend**;
- id não UUID → 404 do recurso, sem chamar o backend;
- corpo inválido ou grande demais → 400 sem chamar o backend; o corpo enviado ao backend contém **só os campos do contrato** (campos extras descartados);
- a chamada ao backend usa o `workspaceId` **da sessão**, nunca um vindo do cliente;
- falhas do backend: códigos conhecidos repassados e qualquer outro status → 502 `UNAVAILABLE`, sem vazar a mensagem;
- `Cache-Control: no-store` em todas as respostas.

## Testes de guarda (não remova; mantenha verdes)

| Arquivo | Garante |
|---|---|
| `src/styles/design-conformance.test.ts` | CSS sem cores literais, fontes em px ou breakpoints fora de 640/1024/1440 |
| `src/lib/api/openapi.test.ts` | `schema.d.ts` × snapshot `backend/src/test/resources/openapi/openapi.json` (rotas, métodos, parâmetros, corpos, campos) |
| `src/lib/api/contract.test.ts` | enums e limites de `contract.ts` × DTOs/enums Java do backend |
| `src/lib/security/csp.test.ts` | CSP |
| `src/app/auth/auth-routes.test.ts`, `server/auth/*.test.ts` | sessão selada, login, logout |

Quando o backend muda a API: regrave o snapshot lá (`-Dshf.openapi.update=true`), depois atualize `schema.d.ts` e `contract.ts` até esses testes passarem.

## E2E (Playwright 1.63)

- Roda contra o **ambiente local completo** (ADR-0011): `docker compose --env-file backend/.env -f infrastructure/docker-compose.yml --profile app up -d --build`, a partir da raiz. Base `E2E_BASE_URL` (padrão `http://localhost:3000`), `locale: 'pt-BR'`, `timezoneId: 'America/Sao_Paulo'`.
- Importe `test`/`expect` de **`e2e/support/test.ts`**, nunca de `@playwright/test` direto. A fixture `signedIn` cadastra um **usuário novo** (Workspace vazio) e só depois inicia o trace, para que a senha gerada nunca entre no artefato. Os testes não dependem de ordem nem de limpeza.
- `retries: 0`: teste instável é defeito. Para dados eventualmente consistentes (Analytics, Health, Insights), recarregue com `expect(async () => { await page.reload(); ... }).toPass({ timeout: 30_000, intervals: [500, 1_000, 2_000] })`. **Sempre passe `timeout` no `toPass`**: o padrão é 0, e ele ignora o timeout do `expect`.
- Locators por papel e nome (`page.getByRole('main')`, `getByRole('list', { name: ... })`) e helpers de `e2e/support/locators.ts` (`definition(scope, termo)` para `dl`). Valores com `e2e/support/money.ts`.
- Datas relativas a "hoje" no fuso de negócio (ver `businessToday()` em `insights.spec.ts`). Nunca datas fixas que envelhecem.
- Extratos de teste em `e2e/fixtures/` (dados fictícios) ou gerados no teste com `testInfo.outputPath`.
- Responsividade: para cada largura em `[375, 768, 1024, 1280, 1440]`, `page.setViewportSize({ width, height: 900 })` e `document.documentElement.scrollWidth - window.innerWidth <= 0`.
- Um spec por fluxo de negócio (`first-financial-loop`, `import`, `health`, `insights`...), validando textos e valores exatos derivados dos dados que o próprio teste criou.

## Checklist

- [ ] View: todos os estados, interação com user-event, payload enviado ao BFF, axe sem violações
- [ ] BFF: sessão, Origin, id inválido, corpo remontado, workspace da sessão, mapeamento de falhas, `no-store`
- [ ] `vi.mock` no topo; `// @vitest-environment node` no servidor; `unstubAllGlobals`
- [ ] Contrato atualizado se a API mudou; guardas verdes
- [ ] E2E do fluxo (quando é jornada nova) com `signedIn`, `toPass` com timeout e as 5 larguras
- [ ] `corepack pnpm test` completo + `typecheck` + `lint` verdes
