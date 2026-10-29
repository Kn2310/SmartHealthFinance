---
name: release-validation
description: Roda e relata as verificações determinísticas do Smart Health Finance (backend ./mvnw verify com ArchUnit, Testcontainers e snapshot OpenAPI; frontend lint, typecheck, Vitest completo e build; E2E Playwright) antes de commit, push, PR ou release. Use ao fechar uma parte, antes de pedir push, ou quando o usuário perguntar se está pronto.
---

# Release validation

Espelha `.github/workflows/ci.yml` e `e2e.yml`. Nunca afirme que algo passou sem ter lido a saída.

## Pré-requisitos locais (Windows)

- Sem `pnpm` global: use `corepack pnpm`. Os scripts `pre*` chamam `pnpm` pelo nome — ponha um shim (`pnpm`/`pnpm.cmd` → `corepack pnpm`) no PATH a partir do scratchpad.
- `*IT` e E2E precisam do Docker: se `docker info` falhar, inicie o Docker Desktop e espere.
- `./mvnw -B -ntp verify` leva vários minutos: rode em background.

## Por área

| Mudou | Rode |
|---|---|
| `backend/` | `cd backend && ./mvnw -B -ntp verify` |
| `frontend/` | em `frontend/`: `pnpm lint`, `pnpm typecheck`, `pnpm test` (**suíte completa**: `design-conformance.test.ts` varre todos os CSS Modules), `pnpm build` |
| contrato de API | backend verify (`OpenApiContractIT`) + frontend `src/lib/api/openapi.test.ts`; mudança intencional: `./mvnw verify -Dshf.openapi.update=true` e revise o diff do `openapi.json` (ADR-0010) |
| fluxo de ponta a ponta | E2E (`pnpm e2e`) contra o ambiente local completo (skill `frontend-tests`) |
| só docs / `.claude/` | nenhuma verificação de build; confira links e caminhos citados |

## Antes de PR ou release

1. Todas as linhas aplicáveis acima, com a saída observada.
2. `git status` limpo e diff revisado (`git diff main...HEAD`); nada de `.env`, segredos ou dados reais.
3. Backlog e ADRs/adendos atualizados quando a parte os afeta.
4. Revisão do `quality-reviewer` para mudança média ou alta.

## Relatório

Tabela comando → resultado (passou/falhou, contagens, duração). Liste o que não rodou e por quê, falhas intermitentes observadas (com a repetição) e riscos remanescentes. Push e PR só com confirmação do usuário.
