# ADR-0026 — API de Cartões e Faturas (M8, Sessão 6.1 parte B)

## Status

Accepted

> Contrato aprovado em 2026-10-22 como proposto. Compras, parcelamentos, pagamentos e estornos (parte C) entram
> como adendo deste ADR.

## Context

O ADR-0025 fixou o modelo de Cartões: agregado `CreditCard`, ciclo da fatura, estados derivados, parcelas,
utilização e eventos. Faltava o contrato HTTP de leitura e de cadastro sobre o qual a parte C (lançamentos) e a
Sessão 6.2 (telas D-Cards, D-CardDetail, D-Invoice e D-FutureInvoices) vão trabalhar.

## Decision

### 1. Convenções

- **Rotas:** `/api/v1/workspaces/{workspaceId}/cards/...`. A autorização no Workspace vem antes da validação
  (ADR-0003). Cartão alheio e inexistente devolvem o mesmo `404 CARD_NOT_FOUND`.
- **Dinheiro:** `MoneyDto` (`{"amount": "3412.80", "currency": "BRL"}`).
- **Datas:**
  - `LocalDate` ISO;
  - meses como `YYYY-MM`;
  - "hoje" no fuso de negócio `shf.overview.zone` (ADR-0006 §10), devolvido como `asOf`.
- **Leituras calculadas** devolvem `calculationVersion` (`invoice-v1`, ADR-0025 §5).
- **Códigos novos:**
  - `CARD_NOT_FOUND` (404);
  - `CARD_ARCHIVED` (409): editar um cartão arquivado; na parte C, também lançar compra ou pagamento;
  - `INVOICE_NOT_FOUND` (404): fatura passada sem lançamentos.

### 2. Cartão

| Método e rota | Resposta |
|---|---|
| `GET /cards?includeArchived=false` | `{items: [Card]}`, em ordem de criação |
| `POST /cards` | `201` + `Location` + `Card` |
| `GET /cards/{cardId}` | `Card` |
| `PUT /cards/{cardId}` | `Card`. Substitui todos os campos editáveis; lock otimista interno (`409 CONFLICT`) |
| `POST /cards/{cardId}/archive`, `/reactivate` | `Card`, idempotentes |

- **Corpo:** `{nickname, institutionName?, brand, last4, limit: Money, closingDay, dueDay, defaultPaymentAccountId?}`.
- **`Card`:** os mesmos campos, mais `id`, `workspaceId`, `status`, `createdAt` e `updatedAt`.
- **Campos desconhecidos são recusados** (`400 MALFORMED_REQUEST`): `number`, `cvv` ou `expiry` nunca são aceitos,
  nem descartados em silêncio.
- **Validação** (`400 VALIDATION_FAILED`, com `field` + `code`):
  - `nickname`: `REQUIRED`, `TOO_LONG` ou `INVALID_CHARACTERS`;
  - `institutionName`: `TOO_LONG` ou `INVALID_CHARACTERS`;
  - `brand`: `REQUIRED` ou `INVALID`;
  - `last4`: `REQUIRED` ou `INVALID`;
  - `limit`: `NOT_POSITIVE`, `TOO_MANY_DECIMALS`, `INVALID_FORMAT` ou `CURRENCY`;
  - `closingDay` e `dueDay`: `OUT_OF_RANGE`;
  - `dueDay`: `SAME_AS_CLOSING`, `CONFLICTS_WITH_CLOSING` ou `DUE_MONTH_CONFLICT` (§5).
    - `CONFLICTS_WITH_CLOSING`: num ciclo de mesmo mês (vencimento depois do fechamento), o fechamento vai só até o
      dia 27. Sem isso, em fevereiro o vencimento cairia no próprio dia do fechamento (ex.: fecha 28, vence 30), e duas
      faturas venceriam no mesmo mês. O banco tem o mesmo `check`.
- **Conta padrão:** `404 ACCOUNT_NOT_FOUND` (inexistente ou de outro Workspace) ou `409 ACCOUNT_ARCHIVED`.
- **Mudar `closingDay` ou `dueDay`** recalcula as datas das faturas não fechadas (§5).

### 3. Resumo

`GET /cards/summary`:
- **totais dos cartões ativos:** `totalLimit`, `utilized`, `openInvoices` (soma do `remaining` das faturas não
  futuras) e `nextDueDate`;
- **por cartão ativo:** o `Card`, a fatura atual resumida, a utilização (§6) e `nextInvoiceCommitted`;
- `asOf` e `calculationVersion`.

Uma chamada só, sem N+1.

### 4. Faturas (pelo mês de vencimento)

| Método e rota | Resposta |
|---|---|
| `GET /cards/{id}/invoices?from=YYYY-MM&to=YYYY-MM` | `{items: [InvoiceSummary]}`, mais recente primeiro: faturas com lançamentos no intervalo, mais a atual. Padrão: 6 meses terminando na atual; no máximo 24 meses (`VALIDATION_FAILED` em `from`/`to`) |
| `GET /cards/{id}/invoices/current` | `Invoice` da fatura cujo período contém "hoje" |
| `GET /cards/{id}/invoices/{dueMonth}` | `Invoice`. Fatura passada sem lançamentos → `404 INVOICE_NOT_FOUND`; a atual e as futuras sempre `200` |
| `GET /cards/{id}/invoices/{dueMonth}/items?page&pageSize` | lançamentos da fatura, paginados (`pageSize` ≤ 100, padrão 50, envelope de `/transactions`) |
| `GET /cards/{id}/invoices/future` | faturas futuras com o valor já comprometido |

- **Validação** (`400 VALIDATION_FAILED`, sempre depois da autorização):
  - `dueMonth`, `from` e `to`: `INVALID` (fora de `YYYY-MM`);
  - `from`: `AFTER_TO`;
  - `to`: `RANGE_TOO_LONG` (mais de 24 meses);
  - `page` e `pageSize`: `OUT_OF_RANGE`.
- **`InvoiceSummary`:**
  - `dueMonth`;
  - datas: `periodStart`, `closingDate`, `dueDate`, `effectiveDueDate` e `datesSource` (`CALCULATED`/`IMPORTED`);
  - `status`;
  - valores: `charges`, `credits`, `amount`, `paid` e `remaining`;
  - `minimumPayment`, nulo até a 6.2;
  - `partial` (`true` em `OPEN`).
- **`Invoice`:** o resumo, mais `itemCount`, `asOf` e `calculationVersion`.
- **Item:**
  - `kind` (`CHARGE`/`CREDIT`) e `transactionId`;
  - dados da transação: `occurredOn`, `description`, `categoryCode`, `merchantName` e `source`;
  - `installment {number, count}`, `amount` e `cancelledAmount`.
  - Ordem: data da compra desc, depois número da parcela e id.
- **Futuras:** `{months: [{dueMonth, committed, installments: [{transactionId, description, number, count, amount}]}],
  totalCommitted, asOf, calculationVersion}`, da próxima fatura até a última parcela contratada. Sem estimativa (M9).

### 5. Persistência e regras de datas

- **`card_invoices`** guarda, por cartão, `closing_month` e `due_month` (únicos por cartão), `period_start`,
  `closing_date`, `due_date`, `dates_source`, `minimum_payment` e `closed_notified_at`. **Não guarda valor.**
  - A linha nasce com a primeira parcela atribuída (parte C).
  - Uma fatura sem linha é calculada pela regra atual do cartão. O início do período é sempre o fechamento da fatura
    anterior.
- **Faturas fechadas ficam congeladas.**
  - Fechada = linha com `closing_date ≤ hoje`.
  - Ao mudar o ciclo, só as linhas ainda não fechadas são recalculadas, em ordem, e na mesma transação de banco do
    `PUT`.
  - A reatribuição das parcelas dessas faturas entra na parte C, junto com as compras.
- **Conflito de mês de vencimento.** Uma mudança de `dueDay` pode fazer uma fatura nova vencer no mesmo mês de uma
  fatura fechada. Exemplo: fecha 28 e vence 05 → fecha 20 e vence 30, com a fatura de setembro fechada.
  - A API identifica a fatura pelo mês de vencimento, então isso é recusado com `VALIDATION_FAILED` `dueDay`
    `DUE_MONTH_CONFLICT`.
  - O caso é raro e documentado. O usuário pode ajustar o dia depois do vencimento da fatura em conflito.
- **`card_installments`** (preenchida na parte C): transação, número, total de parcelas, data da compra
  (cópia imutável), valor, valor cancelado, fatura e versão das regras.
  - A soma das parcelas de uma compra é garantida pelo domínio e por teste.
  - As FKs compostas com `workspace_id` impedem misturar Workspaces.

### 6. Utilização

`GET /cards/{id}/utilization`:
- `limit`, `utilized`, `available` (pode ser negativo) e `credit`;
- `utilizationPercent`, inteiro `HALF_EVEN`;
- `composition {previousOutstanding, currentInvoice, futureInstallments}`, cuja soma é `utilized` sempre que não
  há crédito;
- `utilizationWithoutFuturePercent`;
- `asOf` e `calculationVersion`.

### 7. Cálculo

- **Regras puras** em `cards.domain.service`:
  - `InvoiceCalendar`: ciclo, datas e atribuição por data;
  - `InvoiceLedger`: estados, alocação de pagamentos e utilização;
  - `InstallmentSplitter`: centavos na 1ª parcela.
- O SQL só agrega por fatura (`JdbcCardLedgerReader`). O teste de paridade compara essa agregação com a referência
  em memória.
- Créditos (estornos) e pagamentos entram no leitor na parte C, com os lançamentos. Até lá valem zero.

### 8. Eventos e job

- `card.created`, `card.updated`, `card.archived` e `card.reactivated` v1, com payload `{cardId}`, gravados só quando
  algo mudou.
- **Job diário** (`shf.cards.invoice-closed.cron`, padrão 00:10 no fuso de negócio):
  - grava `invoice.closed` v1 (`{cardId, invoiceId, dueMonth}`) para as linhas com `closing_date ≤ hoje` e
    `closed_notified_at` nulo;
  - marca a linha na mesma transação, então é idempotente;
  - várias instâncias se repartem com `FOR UPDATE SKIP LOCKED`.

### 9. Arquitetura, segurança e observabilidade

- **Direção dos módulos:** `identity → accounts → transactions → cards`.
- **ArchUnit:**
  - Identity, Accounts, Transactions, Ingestion e Overview não dependem de `cards`;
  - `cards` não usa adapters nem casos de uso de Accounts ou Transactions, nem nada de Ingestion, Overview,
    Analytics, Health ou Insights;
  - Analytics, Health e Insights continuam sem depender de `cards` até a 6.2.
- **Logs:** `Card <ação> cardId workspaceId`, sem apelido, `last4`, limite ou valores (há teste).
- **Timers:** `shf.cards.invoice.get`, `shf.cards.utilization.get` e `shf.cards.summary.get`.
- **Contador:** `shf.cards.invoice.closed`.
- **Isolamento:** todas as rotas entram no `WorkspaceIsolationIT`.

### 10. Adendo (2026-10-22, aprovado): lançamentos de cartão (Sessão 6.1 parte C)

> Decisões aprovadas:
> - efeito dos estornos nas parcelas **calculado na leitura**;
> - lançamentos por **rotas em `/cards`**;
> - **formulário mínimo** de cadastro do cartão;
> - **Analytics com cartões já na parte C**.

**Rotas** (sob `/cards/{cardId}`). Todo `POST` exige `Idempotency-Key`: `201` na primeira vez, `200` no replay com o
mesmo conteúdo e `422 IDEMPOTENCY_KEY_REUSED` com outro conteúdo.

| Método e rota | Corpo | Resposta |
|---|---|---|
| `POST …/purchases` | `{amount, occurredOn, description, installmentCount}` (1–48, padrão 1) | `{transaction, installments: [{number, amount, dueMonth}], calculationVersion}` |
| `POST …/payments` | `{accountId, amount, occurredOn, description?}` (padrão "Pagamento de fatura") | `{transaction}`, uma `TRANSFER` conta → cartão |
| `POST …/credits` | `{amount, occurredOn, description, refundOfTransactionId?}` | `{transaction}`, um `REFUND` no cartão |
| `GET …/installments?status=ACTIVE\|FINISHED\|ALL` | | parcelamentos: compra, total, parcelas, pagas, atual, restante, última fatura, status |
| `GET …/installments/{transactionId}` | | o parcelamento e cada parcela (`dueMonth`, valor, cancelado, `PAID\|CURRENT\|FUTURE\|CANCELLED`) |

- **Erros:**
  - `CARD_NOT_FOUND` e `CARD_ARCHIVED`;
  - `ACCOUNT_NOT_FOUND` e `ACCOUNT_ARCHIVED`, na origem do pagamento;
  - `VALIDATION_FAILED`:
    - `installmentCount`: `OUT_OF_RANGE` ou `TOO_MANY_FOR_AMOUNT`;
    - `refundOfTransactionId`: `NOT_FOUND`, `NOT_SAME_CARD` ou as regras do ADR-0005 §6.
  - Parcelamento inexistente, alheio ou que não é compra deste cartão → `404 TRANSACTION_NOT_FOUND`.
- **Correção de lançamento errado:** `POST /transactions/{id}/reverse`, que já existe.
- **Mudanças aditivas em Transactions:**
  - `TransactionResponse` ganha `cardId` e `destinationCardId`;
  - `accountId` passa a aceitar nulo em compras e estornos de cartão;
  - `GET /transactions` e `/transactions/summary` aceitam `cardId`;
  - `POST /transactions` continua sem campos de cartão.
  - O hash da `Idempotency-Key` ganha um componente opcional do chamador, preenchido só pelo Cards com o número de
    parcelas. Sem ele o formato `v1` não muda.
- **Overview:**
  - `recentTransactions[].account` passa a aceitar nulo e ganha `card {id, nickname, last4}`;
  - compras e estornos de cartões ativos entram em `cashFlow.expense` e em `refunds`;
  - pagamento só em `transfers`.
- **Analytics:**
  - `analytics_daily_flows` ganha `card_id` (exatamente um entre conta e cartão);
  - a leitura conta conta ativa no total **ou** cartão ativo;
  - `RebuildAnalyticsProjection.VERSION` = 3.
- **Estornos calculados na leitura** (ADR-0025 §8, adendo):
  - o `CreditAllocator` do domínio aplica os `REFUND` `POSTED` às parcelas a cada leitura, de forma determinística;
  - estornar o próprio estorno por `/transactions/{id}/reverse` reflete na hora;
  - a coluna `card_installments.cancelled_amount` sai na `V17`;
  - o leitor SQL passa a devolver os fatos `POSTED` (parcelas, créditos e soma de pagamentos), e as somas por fatura
    ficam no domínio, com paridade testada.
- **Mudança de ciclo:** reatribui as parcelas das faturas não fechadas, na mesma transação. Se o mês de destino já
  estiver fechado, a parcela vai para a primeira fatura não fechada.
- **Frontend (mínimo):**
  - BFF: transferência e reembolso (fecha a pendência do M2), `GET|POST /api/bff/cards` e
    `POST /api/bff/cards/{id}/purchases|payments|credits`;
  - `/transactions/new` com "Compra no cartão" e "Pagamento de fatura";
  - página mínima "Adicionar cartão": apelido, bandeira, 4 últimos dígitos, limite, fechamento, vencimento e conta
    padrão;
  - nunca pede número completo nem CVV.

## Alternatives

- **Estornos persistidos por um consumidor de `transaction.*` (parte C):** rejeitado. A consistência seria eventual e
  exigiria fila, DLQ e um caminho a mais para divergir.
- **Estender `POST /transactions` com campos de cartão (parte C):** rejeitado. Transactions passaria a depender de
  Cards e um contrato existente mudaria.
- **Fatura por id (`/invoices/{invoiceId}`):** rejeitada. A fatura atual e as futuras sem linha não teriam id, e o
  mês é o que o usuário reconhece ("Fatura de outubro").
- **Utilização dentro do `Card`:** rejeitada. Todo GET do cartão faria o cálculo de fatura; o resumo (§3) cobre a
  lista sem N+1.
- **Itens da fatura sem paginação:** rejeitada. Uma fatura pode ter centenas de lançamentos.
- **Rota plana `/invoices`:** rejeitada. Esconde o cartão, que é o dono do ciclo.
- **Chavear a fatura pelo mês de fechamento na API:** rejeitada pelo contrato aprovado. Por isso o
  `DUE_MONTH_CONFLICT` (§5).

## Consequences

- A parte C e a 6.2 recebem um contrato estável. Até a parte C, faturas e utilização retornam zero, porque não há
  lançamentos.
- O ciclo pode mudar sem reescrever faturas fechadas.

## Security / Privacy

- Requests de cartão estritos: sem campos desconhecidos, `last4` só com 4 dígitos, `check` no banco.
- Logs sem dados do cartão. FKs compostas com `workspace_id` em todas as tabelas novas.

## Financial Integrity

- `Money`/`NUMERIC(19,4)`, sem float/double.
- Valores e estados derivados, nunca persistidos.
- Paridade entre o SQL e a referência em memória.
- Testes de tabela com o Aurora e as bordas do ciclo.

## Operational Impact

- Migration `V16__create_cards.sql`, somente aditiva.
- Job diário com cron configurável. Sem dependência nova.

## Related Specs

- `docs/adr/ADR-0025-credit-cards-model.md`
- `specs/05-system-design/05.6-api-integration.md`, `05.9-observability-operations.md`
- `design/specs/03-screens.md` (CARDS), `09-dados-de-exemplo.md`

## Date

2026-10-22
