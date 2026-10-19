---
name: frontend-feature
description: Implementa uma tela ou funcionalidade no frontend do Smart Health Finance (Next.js 16 App Router + React 19 + TypeScript, BFF em Route Handlers, contrato schema.d.ts, Design System próprio com CSS Modules e tokens). Use ao criar ou alterar página, componente, rota do BFF, hook de dados ou estado de UI no frontend.
---

# Frontend feature

> **Next.js 16 tem mudanças incompatíveis com versões antigas** (ver `frontend/AGENTS.md`). Antes de usar uma API do Next, leia o guia em `frontend/node_modules/next/dist/docs/` ou consulte o Context7 (`/vercel/next.js`). Exemplo: `params` e `searchParams` são **Promises** em páginas e Route Handlers e precisam de `await`.

Referência canônica: `features/accounts` + `app/(app)/accounts` + `app/api/bff/accounts` + `server/accounts.ts`. A fonte de verdade visual é `design/` (specs `design/specs/01..09`, telas em `design/layouts`). **Não invente tela, texto ou fluxo**: siga a tela de `design/specs/03-screens.md` e os estados de `05-states.md`. Se algo faltar, aponte a lacuna em vez de redesenhar.

## Regras invioláveis no frontend

- **O browser nunca chama o backend.** Ele fala só com o BFF (`/api/bff/*`), que roda no servidor Next, lê a sessão do cookie selado e chama a API com o token (ADR-0007). Tokens nunca chegam ao cliente.
- **Nenhuma regra financeira no frontend.** Saldos, totais, projeções e scores vêm prontos do backend (ex.: saldos do Overview). Não some movimentações nem recalcule nada.
- **Dinheiro é string decimal** (`MoneyDto {amount: "123.45", currency}`). Exiba sempre com `<Money value={...} />` e formate com `lib/format/money`. Nunca `parseFloat`/`Number()` em valores; para entrada do usuário use `lib/format/amount-input`.
- **A UI mostra só textos próprios.** Erros chegam como códigos estáveis e viram mensagem via `messages.ts` da feature, com fallback neutro. Nunca exiba o código cru nem texto vindo do servidor.
- Workspace vem da sessão no BFF, nunca da URL ou do cliente.

## Estrutura

```
src/app/(app)/<rota>/page.tsx            # Server Component fino: metadata + await params + <XxxView/>
src/app/api/bff/<recurso>/route.ts       # Route Handlers do BFF (GET/POST/...)
src/app/api/bff/<recurso>/[id]/route.ts
src/server/<recurso>.ts                  # 'server-only': validação do corpo + tradução de falhas
src/features/<feature>/
  api.ts          # chamadas ao BFF com bffCall<T>() e tipos derivados do schema
  hooks.ts        # 'use client': useKeyed + retry
  messages.ts     # código estável → texto pt-BR
  XxxView.tsx     # 'use client': tela com estados
  parts.tsx       # subcomponentes da feature
  Xxx.module.css  # só tokens do DS
  Xxx.test.tsx    # ver skill frontend-tests
src/lib/api/schema.d.ts + contract.ts    # contrato manual (ADR-0007 §6 / ADR-0010)
```

## 1. Contrato

- Tipos sempre de `components['schemas']['XxxResponse']` (`@/lib/api/schema`). Não declare à mão um tipo que já existe no contrato.
- Endpoint ou campo novo no backend → atualize **`src/lib/api/schema.d.ts`** (manual) e, se houver enum ou limite, **`src/lib/api/contract.ts`** (espelho em runtime com checagem de tipos). `openapi.test.ts` e `contract.test.ts` comparam esses arquivos com o snapshot do backend e falham em caso de drift.
- `pnpm api:generate` gera `openapi.generated.d.ts` a partir da API rodando, **só para comparação**. Ele não é importado nem versionado.

## 2. BFF (Route Handler)

```ts
import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'
type Context = { params: Promise<{ xxxId: string }> }

export async function GET(_request: NextRequest, { params }: Context) {
  const { xxxId } = await params
  if (!isUuid(xxxId)) return noStoreJson({ code: 'XXX_NOT_FOUND' }, 404)
  return withSession('xxx', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/xxx/{xxxId}',
      { params: { path: { workspaceId: session.workspaceId, xxxId } } },
    )
    if (data) return noStoreJson(data)
    return xxxFailure('xxx', response.status, error)
  })
}
```

- **Toda resposta passa por `noStoreJson`** (`Cache-Control: no-store`). Dado financeiro nunca é cacheado.
- `withSession(label, handler)`: sem sessão → 401 `UNAUTHENTICATED`; IdP ou API fora → 502 `UNAVAILABLE`.
- **Rotas que alteram dados** (POST/PUT/PATCH/DELETE) começam com `const forbidden = rejectCrossOrigin(request); if (forbidden) return forbidden` (ADR-0007 §6).
- Corpo de entrada: em `server/<recurso>.ts`, leia com limite de bytes, valide com as mesmas regras do domínio (limites de `contract.ts`) e **remonte um objeto novo só com os campos do contrato**. Modelo: `readAccountFields`.
- Falhas: `xxxFailure(label, status, error)` repassa só códigos conhecidos via `stableCode()` (401, 400 `VALIDATION_FAILED` com `details` filtrados, 404/409 do recurso). Qualquer outra falha → `console.error(label + ' upstream failure', { status })` e 502 `UNAVAILABLE`. **Nunca logue corpo, valores ou dados pessoais.**
- Ids da URL validados com `isUuid` antes de chamar a API. Id inválido recebe o mesmo 404 do inexistente.

## 3. Dados no cliente

- `api.ts`: `export const fetchXxx = (id: string) => bffCall<Xxx>(\`/api/bff/xxx/${encodeURIComponent(id)}\`)`. Corpo JSON com `headers: { 'Content-Type': 'application/json' }`. `bffCall` já trata 401 (redireciona para `/auth/login`) e converte falhas em `{ ok: false, code, details? }`.
- `hooks.ts` (`'use client'`), com `useKeyed` (`@/lib/use-keyed`), que devolve `Loadable<T>` = `loading | error | success`:
  ```ts
  export function useXxx(id: string) {
    const [attempt, setAttempt] = useState(0)
    const { value, replace } = useKeyed<Xxx>(`${id}#${attempt}`, async () => {
      const result = await fetchXxx(id)
      return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
    })
    return { ...value, replace, retry: () => setAttempt((n) => n + 1) }
  }
  ```
  Os parâmetros da requisição entram na **chave**. Use `replace(data)` para aplicar na hora a resposta de uma mutação.
- Uma falha secundária não derruba a tela: mostre o estado parcial (ex.: contas sem saldo "—" com aviso).

## 4. Página e estados

- `page.tsx` é Server Component: `export const metadata: Metadata = { title: '...' }`, `await params`/`searchParams`, valida (`isUuid`) e renderiza a View. Sem fetch de dados financeiros aqui.
- A View (`'use client'`) trata **todos** os estados de `design/specs/05-states.md`:
  - **loading**: `<div role="status" aria-busy="true">` + `<span className="sr-only">Carregando…</span>` + `<Skeleton />`;
  - **error**: `<section role="alert" aria-labelledby=...>` com título, mensagem de `messages.ts` e `<Button onClick={retry}>Tentar novamente</Button>`;
  - **empty**: seção com ícone (`aria-hidden`), título, texto e ação primária;
  - **success** e **parcial**.
- Um `<h1>` por página; hierarquia de headings correta; `aria-labelledby` nas seções.
- Formulários: `<label>` associado a cada campo, erro por campo vindo de `details` (`FIELD_ORDER` decide qual recebe o foco), `aria-invalid`/`aria-describedby`, botão desabilitado enquanto envia, e nunca duplique a submissão.
- Ícones `lucide-react` com `size`/`strokeWidth={1.75}` e `aria-hidden="true"`. Texto sempre em pt-BR.
- Navegação: `Button href` (Link) ou `useRouter()` de `next/navigation`. Item de menu novo só se estiver em `design/specs/04-navigation-flows.md` (`components/shell/nav-items.ts`).

## 5. Estilo (Design System)

- CSS Modules por feature (`Xxx.module.css`) + componentes de `src/components/ui` (`Button`, `Card`, `Money`, `Skeleton`) e `src/components/charts`. Reaproveite antes de criar.
- **Só tokens** de `src/styles/design-tokens.css` (gerado de `design/tokens` por `pnpm sync:design`; não edite o gerado): `var(--color-*)`, `var(--space-*)`, `var(--type-*)`, `var(--size-touch-min-mobile)`...
  - **proibido**: `#hex`, `rgb()/rgba()`, `hsl()/hsla()`, `font-size` em px (exceto 13 px do label), escalas paralelas `--text-*`. O `design-conformance.test.ts` falha em todos esses casos;
  - para sobreposição translúcida: token + pseudo-elemento com `mix-blend-mode: multiply`.
- Mobile first. Media queries **só** em 640 / 1024 / 1440 px. Sem rolagem horizontal em 375, 768, 1024, 1280 e 1440 (`min-width: 0`, `overflow-wrap: anywhere` em textos longos).
- Foco visível vem do global (`:focus-visible`). Não remova outline. Alvos de toque ≥ `--size-touch-min-mobile`. Sem animação em loop; respeite `prefers-reduced-motion`.
- Comentário no topo do CSS citando as telas do design (`D-Accounts`, ...).

## 6. Segurança e privacidade

- Nada sensível em `console.*`, URL ou query string (ex.: `?registered=` carrega só um nome de aviso fixo, nunca dados).
- `src/lib/security/csp.ts` define a CSP: não adicione scripts ou estilos inline nem origens externas.
- Arquivos em `src/server/*` começam com `import 'server-only'`.

## Comandos (em `frontend/`)

Sem pnpm global nesta máquina: use `corepack pnpm <script>`. Os scripts `pre*` chamam `pnpm` pelo nome, então ponha um shim no PATH se necessário.

```
corepack pnpm typecheck   # tsc --noEmit
corepack pnpm lint        # eslint
corepack pnpm test        # vitest run — SEMPRE a suíte inteira antes de push
corepack pnpm dev         # http://localhost:3000 (precisa do backend + Keycloak locais)
```

## Checklist

- [ ] Tela, estados e textos conforme `design/specs`; nenhuma regra financeira no cliente
- [ ] BFF: `withSession`, `rejectCrossOrigin` em mutações, `noStoreJson`, corpo remontado, só códigos estáveis
- [ ] `schema.d.ts`/`contract.ts` atualizados quando o contrato mudou
- [ ] loading / empty / error / parcial; acessibilidade (labels, roles, foco, `sr-only`)
- [ ] CSS só com tokens e breakpoints do DS; sem overflow horizontal
- [ ] Testes da skill `frontend-tests`; `typecheck`, `lint` e `test` completos verdes
