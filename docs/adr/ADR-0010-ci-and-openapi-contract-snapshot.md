# ADR-0010 — CI no GitHub Actions e snapshot versionado do contrato OpenAPI

## Status

Accepted

> Estratégia de contrato escolhida em 2026-10-09 entre três opções (snapshot versionado, geração no build com plugin
> Maven, só o teste atual). O restante segue as specs 05.10/05.11.

## Context

- Não havia CI (M0 do backlog). As specs pedem CI de backend (compile, unit, arquitetura, integração, contrato) e de
  frontend (install, lint, typecheck, unit/component, build) — 05.10 e 05.11.
- O frontend usa um contrato manual (`frontend/src/lib/api/schema.d.ts`, ADR-0007 §11), porque o springdoc publica
  enums como `string` e não declara nulos.
- `contract.test.ts` já compara enums, campos de records e limites com o **código-fonte** Java. Ficavam sem
  verificação rotas, métodos HTTP, parâmetros (path/query/header) e corpos de requisição. O OpenAPI real que o
  springdoc gera nunca era olhado.

## Decision

### 1. Workflow `.github/workflows/ci.yml`

- Disparo: `pull_request` e `push` na `main`. `concurrency` por PR/ref com `cancel-in-progress`.
- `permissions: contents: read`. Nenhum segredo: Testcontainers e valores fictícios do perfil `test`.
- Actions **somente oficiais** (`actions/*`), fixadas por SHA de commit, com a tag no comentário. Só usamos release
  com pelo menos duas semanas. O pnpm vem do Corepack, na versão do `packageManager`, para não depender de action
  de terceiros. O cache do store do pnpm usa `actions/cache`.
- Jobs em paralelo, com os mesmos comandos usados localmente:
  - **backend**: Temurin 25 (`<java.version>`), cache do Maven, `./mvnw -B -ntp verify` (unit + ArchUnit no `test`;
    `*IT` com Testcontainers no `verify`);
  - **frontend**: Node 22 (`engines`), `pnpm install --frozen-lockfile`, `lint`, `typecheck`, `test`, `build`.
- `backend/mvnw` e `infrastructure/scripts/local-token.sh` passam a ser executáveis no Git (modo 755).

### 2. Contrato OpenAPI: snapshot versionado, validado dos dois lados

- **Backend**: `OpenApiContractIT` gera o `/v3/api-docs` (springdoc, já dependência) e compara com
  `backend/src/test/resources/openapi/openapi.json`, que é versionado.
  - Normalização: sem `servers` (varia por ambiente), sem os endpoints de teste (`/api/v1/test-probe`), chaves
    ordenadas, LF (`.gitattributes`).
  - Diferença → `./mvnw verify` falha. A versão gerada fica em `target/openapi/openapi.json` para comparar.
  - Mudança intencional: `./mvnw verify -Dshf.openapi.update=true` regrava o snapshot. O diff do contrato aparece
    no PR.
  - Só o perfil `test` liga `springdoc.api-docs.enabled` e `shf.security.public-api-docs`; os padrões de produção
    continuam desligados.
- **Frontend**: `src/lib/api/openapi.test.ts` lê o `schema.d.ts` pela AST do TypeScript (dependência já existente) e
  confere contra o snapshot:
  - toda rota + método do contrato manual existe no backend;
  - parâmetros de path, query e header com os mesmos nomes e a mesma obrigatoriedade;
  - corpos de requisição com os mesmos media types e o mesmo schema;
  - schemas de topo com os mesmos nomes de campo.
- O contrato manual continua sendo a fonte de verdade do frontend (ADR-0007 §11). Enums e nulos continuam
  cobertos pelo `contract.test.ts`.
- Os schemas que o OpenAPI ainda não descreve (`ImportPageResponse`, `ImportRecordPageResponse`,
  `ImportRecordResponse`, porque os endpoints devolvem corpo genérico) ficam numa lista explícita. Um teste falha
  quando o backend passar a declará-los, para que entrem na comparação.

## Alternatives

- **Gerar o spec no build com `springdoc-openapi-maven-plugin` e comparar o diff dos tipos gerados:** rejeitada.
  - É uma dependência Maven nova.
  - Exige subir a aplicação no `integration-test`.
  - O diff bruto com o `schema.d.ts` nunca zeraria, por causa de enums e nulos, então ainda precisaria de uma
    comparação estrutural.
  - O contrato não ficaria visível no repositório.
- **Manter só o `contract.test.ts` no CI:** rejeitada. Rotas, métodos e parâmetros continuariam sem verificação, e o
  OpenAPI real nunca seria validado.
- **`pnpm/action-setup`:** rejeitada. É action de terceiros, e o Corepack resolve o mesmo problema.
- **Actions por tag (`@v7`) em vez de SHA:** rejeitada. Uma tag é móvel, então o código que roda no CI poderia
  mudar sem revisão.

## Consequences

- Positivas:
  - toda mudança de API passa a aparecer como diff do `openapi.json` no PR;
  - o frontend quebra no CI se declarar rota, parâmetro ou campo que o backend não tem;
  - CI sem segredos e sem actions de terceiros.
- Negativas:
  - mudar a API exige regenerar o snapshot (um comando) e, quando aplicável, atualizar o `schema.d.ts`;
  - o snapshot reflete limitações do springdoc: records aninhados com o mesmo nome (`OverviewResponse.Period` e
    `ImportResponse.Period`) viram um único schema `Period`. Corrigir isso com `@Schema(name)` muda o OpenAPI e fica
    para um PR próprio;
  - os SHAs fixados precisam de atualização manual (ou Dependabot, a decidir).

## Security / Privacy

- `permissions: contents: read`, `persist-credentials: false` no checkout e nenhum secret referenciado.
- O snapshot é só a descrição pública da API (rotas e schemas), sem dados.
- Em produção, `/v3/api-docs` continua desligado e exige autenticação (`API_DOCS_ENABLED=false`,
  `public-api-docs: false`).

## Financial Integrity

Sem impacto em regras. O CI passa a garantir em todo PR os testes de integridade já existentes (paridade do
Overview, idempotência, concorrência de reembolso, ArchUnit sem float/double).

## Operational Impact

- Tempo de CI dominado pelo `verify` do backend (Testcontainers).
- Proteção da `main` com os checks `backend` e `frontend` obrigatórios, configurada manualmente no GitHub.
- E2E (Playwright) ficam fora deste workflow; a política de execução será decidida no ADR do E2E.

## Related Specs

- `specs/05-system-design/05.10-testing.md`
- `specs/05-system-design/05.11-devops-deployment.md`
- `specs/05-system-design/05.6-api-integration.md`
- `docs/adr/ADR-0007-frontend-foundation-and-bff-auth.md` (§11)

## Date

2026-10-09
