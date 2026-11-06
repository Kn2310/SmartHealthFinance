# ADR-0035 — API de Objetivos e aportes (M10, Sessão 7.2, parte B)

## Status

Accepted

> Contrato aprovado em 2026-11-06, como proposto:
> - CRUD de `/goals`, mais `PUT /goals/{id}/plan` ("Aplicar ao objetivo") e as ações `pause`, `resume`, `complete` e
>   `archive`;
> - `POST /goals/{id}/contributions` idempotente, que cria uma `TRANSFER` e devolve o `TransactionResponse`;
> - `GET /goals/{id}/contributions` e `GET /goals/recent-contributions`, derivados das transações;
> - a previsão (Estimado) entra na parte C como campo aditivo;
> - "Registrar resgate" fica fora (o layout não tem a ação).

## Context

O ADR-0034 fixou o modelo `goals-v1`: o progresso é o saldo do destino dedicado (fato), os aportes são as
`TRANSFER` `POSTED` do destino, a reserva espelha as contas `SAVINGS` ativas e nada de progresso ou aporte é gravado no
módulo. O 05.6 lista o recurso `/goals` e o `Idempotency-Key` em `contributions`; o 05.3, os casos de uso `CreateGoal`,
`AddGoalContribution` e `GetGoals`. Faltava o contrato.

## Decision

### 1. Recursos

Todos sob `/api/v1/workspaces/{workspaceId}/goals`, com a autorização do Workspace primeiro (ADR-0003) e o objetivo
buscado dentro dele: inexistente e de outro Workspace respondem o mesmo `404 GOAL_NOT_FOUND`.

| Método e rota | O que faz | Respostas |
|---|---|---|
| `GET /goals?status=` | Sem filtro, os `ACTIVE` e `PAUSED`; com `status`, só os daquele status. Mais recentes primeiro. Traz o resumo | 200, 400 |
| `POST /goals` | Cria o objetivo `ACTIVE`, com `trackingStart` = hoje | 201 + `Location`, 400, 404, 409 |
| `GET /goals/recent-contributions?limit=` | Aportes (só `CONTRIBUTION`) dos objetivos abertos, mais recentes primeiro; `limit` 1 a 20, padrão 5 | 200, 400 |
| `GET /goals/{goalId}` | Detalhe com o progresso | 200, 404 |
| `PUT /goals/{goalId}` | Substitui nome, alvo, aporte planejado, prazo, conta de origem e destino; tipo e origem não mudam | 200, 400, 404, 409 |
| `PUT /goals/{goalId}/plan` | Troca só `plannedMonthly` e `deadline` ("Aplicar ao objetivo", ADR-0034 §5) | 200, 400, 404, 409 |
| `POST /goals/{goalId}/pause`, `/resume`, `/complete`, `/archive` | Ações de status, idempotentes (ADR-0034 §1); concluir congela o progresso (§2.5) | 200, 404, 409 |
| `POST /goals/{goalId}/contributions` | "Registrar aporte" (§2) | 201 + `Location` da transação, 200 no replay, 400, 404, 409, 422 |
| `GET /goals/{goalId}/contributions?page&pageSize` | Histórico do destino (§3); `page` 0-based, `pageSize` 1 a 50, padrão 20 | 200, 400, 404 |

Payloads:
- **criação:** `{name, type, targetAmount, plannedMonthly, deadline?, sourceAccountId?, destinationAccountId?,
  origin?: {kind, insightId?}}`. `deadline` no formato `"2027-07"`; `origin` ausente assume `MANUAL`;
  `destinationAccountId` obrigatório em `PURCHASE`/`CUSTOM` e proibido em `EMERGENCY_RESERVE`;
- **edição:** `{name, targetAmount, plannedMonthly, deadline?, sourceAccountId?, destinationAccountId?}`. Um destino ou
  uma origem que não mudaram não são revalidados (a conta pode ter sido arquivada depois, ADR-0034 §2.1);
- **plano:** `{plannedMonthly, deadline?}`;
- **`GoalResponse`:** `id`, `name`, `type`, `status`, `origin {kind, insightId}`, `targetAmount`, `plannedMonthly`,
  `deadline`, `sourceAccountId`, `destination {kind, accountId}`, `trackingStart`, `createdAt`, `completedAt`,
  `completedAmount` e `progress {basis: "FACT", saved, percent, remaining, reached, destinationBalance,
  destinationState: OK | ACCOUNT_ARCHIVED | NO_RESERVE_ACCOUNT}`;
- **lista:** `{summary: {savedTotal, plannedMonthlyTotal, activeCount}, goals: [GoalResponse]}`. O resumo é sempre dos
  objetivos abertos, qualquer que seja o filtro: `savedTotal` soma o guardado dos `ACTIVE` e `PAUSED` (os destinos
  nunca se sobrepõem, ADR-0034 §2.6), `plannedMonthlyTotal` soma os planos dos `ACTIVE` e `activeCount` conta os
  `ACTIVE`;
- dinheiro em `MoneyDto` (valor em string, BRL). Enums como string com os valores possíveis no OpenAPI.

### 2. "Registrar aporte"

- Header `Idempotency-Key` obrigatório. Corpo: `{sourceAccountId, destinationAccountId?, amount, occurredOn,
  description?}`.
- Cria uma `TRANSFER` `POSTED` pelo `CreateTransaction` (regras do ADR-0005: contas ativas e distintas do Workspace,
  valor positivo, idempotência com hash do pedido). A resposta é o `TransactionResponse` dela.
- **Conta dedicada:** o destino é sempre a do objetivo; um `destinationAccountId` diferente é `400 NOT_ALLOWED`.
- **Reserva:** `destinationAccountId` obrigatório, uma conta `SAVINGS` ativa (`400 NOT_RESERVE_ACCOUNT`); a origem não
  pode ser outra `SAVINGS` (`400 sourceAccountId RESERVE_ACCOUNT`), porque o dinheiro não entraria na reserva.
- `occurredOn` entre `trackingStart` e hoje (`400 FUTURE` / `BEFORE_TRACKING_START`).
- Aceito em `ACTIVE` e `PAUSED`; nos demais, `409 GOAL_STATUS_CONFLICT`.
- `description` ausente assume "Aporte · {nome do objetivo}". Fica na transação, nunca em log ou evento.
- Nenhum evento `goal.*`: a transferência já emite `transaction.created`.

### 3. Histórico do destino

- `openingBalance {date, amount}`: o saldo do destino na véspera de `trackingStart` ("Aporte inicial").
- `movements`: as transações `POSTED` que tocam o destino desde `trackingStart`, mais recentes primeiro, com
  `{transactionId, kind: CONTRIBUTION | WITHDRAWAL | OTHER, direction: IN | OUT, occurredOn, amount,
  counterpartAccountId}`. O valor é sempre positivo. Transferência entre duas contas da reserva é interna e não
  aparece. `direction` é um campo a mais em relação à proposta, para os movimentos `OTHER`.
- Paginação em memória sobre a lista do destino: os destinos são contas dedicadas, com poucos movimentos.
- Nova consulta de leitura no repositório de Transactions, `findPostedTouchingAccounts` (conta na origem ou no
  destino, por Workspace, entre datas), sem mudança de contrato nem de schema.

### 4. Erros novos

| Código | HTTP | Quando |
|---|---|---|
| `GOAL_NOT_FOUND` | 404 | objetivo inexistente ou de outro Workspace |
| `GOAL_STATUS_CONFLICT` | 409 | ação não permitida no status (editar concluído, retomar arquivado, aportar em concluído) |
| `GOAL_RESERVE_ALREADY_EXISTS` | 409 | segunda reserva aberta no Workspace |
| `GOAL_DESTINATION_IN_USE` | 409 | conta já é destino de outro objetivo aberto |

Validação (400 `VALIDATION_FAILED`): `destinationAccountId` `REQUIRED`, `NOT_ALLOWED`, `SAVINGS_NOT_ALLOWED` ou
`NOT_RESERVE_ACCOUNT`; `deadline` `PAST` ou `INVALID`; `sourceAccountId` `SAME_AS_DESTINATION` ou `RESERVE_ACCOUNT`;
`occurredOn` `FUTURE` ou `BEFORE_TRACKING_START`; `origin.insightId` `REQUIRED` ou `NOT_ALLOWED`; moeda
`UNSUPPORTED`. Reaproveitados: `ACCOUNT_NOT_FOUND`, `ACCOUNT_ARCHIVED`, `IDEMPOTENCY_KEY_REUSED` e o `CONFLICT` do
lock otimista.

### 5. Persistência

`V22__create_goals.sql`: uma tabela `goals` com o plano e o estado. Dinheiro em `numeric(19,4)`, FKs compostas para
`accounts (id, workspace_id)` (o destino e a origem nunca apontam para outro Workspace), `check` espelhando as regras
do domínio e dois índices únicos parciais: um destino por conta entre os abertos e uma reserva aberta por Workspace.
Uma corrida entre duas criações cai no índice e responde `409 CONFLICT`. Sem tabela de aportes nem de simulações.

### 6. Eventos

`goal.created`, `goal.plan-changed` (com os **nomes** dos campos alterados) e `goal.status-changed` (`from`, `to`), v1,
pela outbox, só quando algo mudou. Repetir o mesmo plano ou a mesma ação não emite evento. Sem consumidor no M10.

### 7. Fronteiras (ArchUnit)

- Ninguém depende de `goals`.
- `goals` lê contas e transações pelo domínio de Accounts e Transactions, saldos pela porta `OverviewReadModel` e lança o
  aporte só pelo caso de uso `CreateTransaction`; nunca adapters, serviços ou portas internas de outros módulos, nem
  Analytics, Cards, Health, Insights ou Forecast (o Forecast entra na parte C).
- A porta `GoalBalanceSource` (ADR-0034 §8) é implementada sobre o Overview e as Transactions.

## Alternatives

- **Resposta própria para o aporte** (`{transaction, goal}`): rejeitada para manter um único contrato de transação,
  como nos Cartões (ADR-0026 §10).
- **Paginação no banco** do histórico: desnecessária para contas dedicadas; o ordenamento é o mesmo da lista de
  transações.
- **Endpoint de resgate:** fora do layout; uma transferência manual já conta como resgate.

## Consequences

- O progresso e o histórico estão sempre consistentes com os saldos e as transações: não há cópia a reconciliar nem
  rebuild.
- Cada leitura faz uma consulta de saldos por Workspace (todos os destinos de uma vez) e, no histórico, uma consulta de
  transações do destino.
- O frontend (parte D) monta o D-Goals com `GET /goals` + `GET /goals/recent-contributions`, e o D-GoalDetail com
  `GET /goals/{id}` + `GET /goals/{id}/contributions`.

## Security / Privacy

- Toda consulta filtra por `workspace_id`; os endpoints estão na suíte de isolamento (alheio = inexistente, inclusive
  contas alheias como destino, origem ou origem do aporte).
- Logs com ids, tipo, status e nomes de campos; nunca o nome do objetivo nem valores. Eventos idem.

## Financial Integrity

- O aporte passa pelas regras e pela idempotência das Transactions; nenhum saldo é calculado fora da regra do Overview.
- Progresso em `BigDecimal`, `HALF_UP` só no percentual inteiro.
- O objetivo nunca escreve em Transactions além do aporte pedido pelo usuário.

## Operational Impact

- Sem jobs, filas, snapshots ou runbook novos.
- Snapshot do OpenAPI e `schema.d.ts` atualizados (só adições).

## Related Specs

- ADR-0003, 0004, 0005, 0006, 0010, 0017, 0026, 0034
- `specs/05-system-design/05.3`, `05.6`, `05.10`, `05.12`

## Date

2026-11-06
