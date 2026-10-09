# ADR-0004 — Módulo Accounts (M1)

## Status

Accepted

## Context

O First Financial Loop (Workspace → Account → Transaction → Balance → Home) exige contas antes de transações.
As specs (05.2, 05.3, 05.4, 05.6) e o design (D-Accounts, D-AddAccount, D-EditAccount) não definem:

- a rota da API;
- como representar a instituição da conta;
- o tratamento do saldo inicial;
- a lista de tipos de conta.

## Decision

1. **Rota aninhada:** `/api/v1/workspaces/{workspaceId}/accounts[/{accountId}]`.
   - Ações de domínio: `POST .../{accountId}/archive` e `.../reactivate` (05.6).
   - A autorização roda primeiro no Workspace (`WorkspaceAccessGuard`, ADR-0003), via
     `accounts.application.service.AccountAccess`.
   - A conta é sempre buscada filtrando por `workspace_id`.
2. **404 uniforme.**
   - Conta inexistente ou de outro Workspace → `404 ACCOUNT_NOT_FOUND`.
   - Workspace sem membership → `404 WORKSPACE_NOT_FOUND`.
3. **Instituição como texto livre** (`institution_name`, opcional).
   - Vira FK quando o módulo Institutions existir, via migration expand/contract.
4. **Sem saldo na conta.**
   - O saldo é derivado das transações (M3).
   - O saldo inicial de uma conta manual será uma transação `ADJUSTMENT` (M2).
   - A conta nunca terá um campo de saldo editável.
5. **Tipos:** `CHECKING`, `SAVINGS`, `PAYMENT` (conta digital/de pagamento), `OTHER`.
   - Cartões ficam em Cards.
6. **Arquivar em vez de excluir.**
   - A conta arquivada mantém o histórico, não é editável e pode ser reativada.
   - A FK para `workspaces` não tem cascade.
7. **Moeda** = moeda base do Workspace (BRL fixa no MVP), garantida por `check` no banco.
8. **Edição** por `PUT` com substituição completa dos campos editáveis (modal "Editar conta").
   - Lock otimista via `version`; conflito → `409 CONFLICT`.
   - Para isso, foi adicionado o código genérico `CONFLICT` em `ErrorCode`.
9. **Eventos `account.*` adiados** até existir o Transactional Outbox (mesma decisão do ADR-0003).
10. **Organização:** segue os subpacotes por camada do ADR-0001 (revisão de 2026-09-29).
11. **UI (adendo de 2026-10-08, sem decisão arquitetural nova):** telas `/accounts`, `/accounts/new` e `/accounts/{id}`
    sobre o BFF do ADR-0007: `GET|POST /api/bff/accounts` (`?includeArchived=true` inclui arquivadas; sem parâmetro,
    só ativas, como a importação usa), `GET|PUT /api/bff/accounts/{id}`, `POST .../{id}/archive|reactivate`.
    - Mutações exigem `Origin` do app; id e corpo (tipos, 100 caracteres, sem controle) validados no BFF; só
      `VALIDATION_FAILED` (com `field` + `code` conhecidos), `ACCOUNT_NOT_FOUND`, `ACCOUNT_ARCHIVED` e `CONFLICT`
      passam; o resto vira `UNAVAILABLE`.
    - Saldos na lista e no detalhe vêm do Overview (ADR-0006); a conta continua sem saldo e o frontend não calcula.
    - Criar a partir de `/import` usa `?returnTo=import` e volta para `/import?accountId=<nova>`. Só nomes de
      destinos fixos são aceitos, nunca uma URL (sem open redirect).
    - D-AddAccount e D-EditAccount (modais no design) viraram página/seção de página; a opção de importar do
      D-AddAccount continua em `/import`, que exige a conta antes (ADR-0009 §4). Movimentações por conta ficam
      para o BFF de transações.

## Alternatives

- **Rota plana (`/api/v1/accounts?workspaceId=`):** rejeitada. Esconde o boundary de isolamento na URL e
  exige resolver o Workspace a partir da conta.
- **Criar o módulo Institutions agora:** rejeitado. Aumenta o escopo do M1 sem necessidade imediata.
- **Campo `openingBalance` na conta:** rejeitado. Criaria uma segunda fonte de verdade para o saldo, além das
  transações.
- **Exclusão física:** rejeitada. Apagaria histórico financeiro.

## Consequences

- Positivas:
  - isolamento verificado na Application e na query;
  - dados financeiros não se perdem por exclusão;
  - edição concorrente é detectada.
- Negativas:
  - o frontend precisa conhecer o `workspaceId`;
  - o saldo das contas só aparece a partir do M2/M3;
  - cada operação lê o Workspace para autorizar (sem cache no MVP).

## Security / Privacy

- Logs registram apenas `accountId` e `workspaceId`, nunca nome ou instituição.
- Testes cobrem listagem, consulta, criação, edição e arquivamento entre Workspaces, além da
  indistinguibilidade entre conta alheia e inexistente.

## Financial Integrity

- Nenhum valor monetário é armazenado na conta.
- Restrições `check` no banco (tipo, status, moeda, nome não vazio) independem da aplicação.

## Related Specs

- `specs/05-system-design/05.2-domain-architecture.md`
- `specs/05-system-design/05.4-data-architecture.md`
- `specs/05-system-design/05.6-api-integration.md`
- `docs/adr/ADR-0001-modular-monolith-clean-architecture.md`
- `docs/adr/ADR-0003-personal-workspace-and-membership-authorization.md`

## Date

2026-10-06
