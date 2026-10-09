# ADR-0011 — E2E com Playwright: usuário novo por teste, workflow separado e trace pós-login

## Status

Accepted

> Decisões aprovadas em 2026-10-09: dependência `@playwright/test` 1.63.0 fixa, auto-cadastro por teste,
> `e2e.yml` separado e trace iniciado só depois do login.

## Context

- As specs pedem Playwright (05.10, Frontend/E2E) e o fluxo prioritário é o First Financial Loop
  (development-strategy). Até aqui só existiam testes unitários e de componente (Vitest/RTL) e os `*IT` do backend.
- O E2E precisa do sistema inteiro: Keycloak (login real com PKCE), BFF, API, PostgreSQL, RabbitMQ e o Import Worker.
- Os testes não podem depender da ordem de execução. Os usuários fixos do realm (`SHF_DEV_*`) acumulariam dados entre
  execuções, e limpar exigiria um endpoint de teste ou acesso direto ao banco.
- O trace padrão do Playwright grava os valores digitados (inclusive a senha) e os cookies.

## Decision

1. **Ferramenta:** `@playwright/test` **1.63.0**, versão exata (release com mais de duas semanas), só Chromium.
   - Configuração em `frontend/playwright.config.ts`, testes em `frontend/e2e/` e script `pnpm e2e`.
   - Sem `retries`: teste instável é defeito (05.10).
2. **Ambiente:** o compose local com o perfil `app` (API, `next dev`, Keycloak, PostgreSQL, RabbitMQ, Redis). Não há
   ambiente novo nem mock.
   - Antes dos testes, um `globalSetup` faz uma requisição sem sessão a cada rota, para o `next dev` compilar fora
     dos cenários. No container, a compilação sob demanda passa de 15 s.
3. **Usuário novo por teste**, pelo **auto-cadastro do realm local** (`registrationAllowed: true`, já existente):
   - e-mail `e2e-<uuid>@example.com` e senha aleatória gerada em memória, nunca gravada nem logada;
   - o login passa pela UI real (Keycloak → `/auth/callback` → provisionamento → Workspace pessoal);
   - cada teste tem um Workspace vazio, então não há dependência de ordem nem limpeza;
   - nenhuma mudança no realm, nenhum endpoint de teste, nenhuma credencial de admin no teste.
4. **Cenários:**
   - **First Financial Loop**: conta com saldo inicial (ajuste), receita e despesa. O esperado é calculado em
     centavos (`bigint`) e comparado com o saldo, as receitas, as despesas e o resultado exibidos na Home.
   - **Importação**: o CSV fictício de `e2e/fixtures/` passa por preview, confirmação, processamento assíncrono e
     resultado.
     - A Home é aberta com período personalizado (setembro de 2026) e comparada com somas calculadas do próprio
       fixture: saldo, receitas e despesas.
     - Toda movimentação recente leva o selo "Importado".
     - O teste não depende da data de hoje.
   - **Home vazia**: um usuário novo vê `NO_ACCOUNTS`, sem nenhum valor monetário.
5. **CI:** `.github/workflows/e2e.yml`, separado do `ci.yml`, em `push` na `main` e `workflow_dispatch`.
   - `permissions: contents: read`, actions oficiais fixadas por SHA e nenhum secret do repositório.
   - O `backend/.env` é gerado no job a partir do `.env.example`, com valores aleatórios por execução e mascarados
     no log (`::add-mask::`). O ambiente sobe com `--build` e é derrubado com `down -v` ao final.
6. **Artefatos só em falha:** screenshot, `error-context.md`, relatório HTML e trace, retidos por 7 dias.
   - O trace do runner fica desligado. Um trace próprio (`e2e/support/test.ts`) começa **depois** do cadastro e do
     login e só é salvo se o teste falhar, então a senha gerada nunca entra no artefato (verificado abrindo o
     trace).
   - O que resta no trace é o cookie de sessão selado de um stack efêmero: `SESSION_SECRET` aleatório, IdP e banco
     destruídos no fim do job. Esse cookie não vale nada fora da execução.
   - Na falha, o log do job mostra as últimas linhas da API e do app, que só registram ids e status (há testes). O
     Keycloak fica de fora.

## Alternatives

- **Usuário fixo do realm + limpeza determinística:** rejeitada. Exigiria um endpoint de teste na API ou acesso direto
  ao PostgreSQL a partir do teste.
- **Admin API do Keycloak para criar usuários:** rejeitada. O teste passaria a manusear credenciais de admin.
- **E2E em todo PR (job no `ci.yml`):** rejeitada por ora. Somaria de 8 a 12 minutos a cada PR e ficaria mais exposto
  a instabilidade de infraestrutura. Pode ser revista quando o E2E virar check obrigatório.
- **Trace padrão (`retain-on-failure`):** rejeitado. Grava a senha digitada no Keycloak.
- **CI sem trace:** rejeitada. Dificulta a investigação, e o trace pós-login não carrega a senha.
- **Build de produção do frontend no E2E:** adiado. O perfil `app` usa `next dev`; trocar exigiria um serviço novo
  no compose.

## Consequences

- Positivas:
  - o First Financial Loop e a importação ficam verificados de ponta a ponta, com aritmética exata;
  - os testes são independentes e paralelizáveis;
  - nada muda no realm, na API ou nos segredos.
- Negativas:
  - cada execução local deixa usuários `e2e-*@example.com` no Keycloak e no banco locais, até o próximo `down -v`
    (no CI o ambiente é descartado);
  - o E2E roda contra `next dev`, cujo CSP de desenvolvimento é mais permissivo (ADR-0007 §10);
  - regressões de E2E aparecem depois do merge na `main`, não no PR;
  - os testes dependem do HTML do tema padrão do Keycloak (`#email`, `#password`, "Register").

## Security / Privacy

- Só dados fictícios: e-mails `@example.com`, extrato do fixture (ver `frontend/e2e/fixtures/README.md`).
- Nenhuma credencial real, nenhum secret do repositório e nenhuma credencial repetida em log: os valores gerados
  são mascarados.
- Os artefatos não contêm a senha (trace pós-login; o campo de senha é mascarado no screenshot).

## Financial Integrity

O E2E não calcula nada no app: o esperado é calculado de forma independente no teste e conferido contra o que o
backend calculou (ADR-0006).

## Operational Impact

- `pnpm e2e` exige o compose com `--profile app` no ar e `pnpm exec playwright install chromium` uma vez.
- O `e2e.yml` leva cerca de 8 a 12 minutos (build da imagem da API, instalação do Chromium, testes).

## Related Specs

- `specs/05-system-design/05.10-testing.md`
- `specs/06-development/development-strategy.md` (First Financial Loop)
- `docs/adr/ADR-0006-overview-module.md`, `ADR-0007-frontend-foundation-and-bff-auth.md`,
  `ADR-0009-csv-ofx-import.md`, `ADR-0010-ci-and-openapi-contract-snapshot.md`

## Date

2026-10-09
