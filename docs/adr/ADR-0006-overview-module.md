# ADR-0006 — Módulo Overview (M3: balance + overview query)

## Status

Accepted

## Context

O First Financial Loop (Workspace → Account → Transaction → Balance → Home) fecha com o saldo e a visão
consolidada da Home. As specs definem a query `GetFinancialOverview` (05.3), o bloco "Visão geral" da Home
(saldo total, distribuição por conta, Receitas/Despesas/Resultado — `design/specs/03-screens.md`) e a regra de UX
"transferências entre contas próprias não são gasto". Não definem:

- a rota da API (a spec 05.6 cita `GET /api/v1/home`, mas as rotas de Accounts/Transactions são aninhadas por
  Workspace — ADR-0004/0005);
- quais status e tipos entram no saldo e no fluxo de caixa;
- como transferências, reembolsos e ajustes aparecem nos agregados;
- o período padrão e o fuso que define "hoje";
- como a UI distingue "sem dados" de "zero real";
- como e onde calcular (SQL agregado, JPA, memória).

Observação sobre os documentos: o texto de especificação fala em "confirmed"; no modelo implementado o status que
move saldo é `POSTED` (ADR-0005 §7) e "confirmed/imported" (05.13) se refere à *origem* (`source`). Vale o código
e o ADR-0005: **POSTED é o único status com efeito financeiro**.

## Decision

### Arquitetura

1. **Módulo `overview` read-only e downstream:** `identity → accounts → transactions → overview`.
   - Não é dono de nenhum fato nem tem tabela própria. Nada de `account.balance`: o saldo é sempre derivado.
   - ArchUnit proíbe Identity/Accounts/Transactions/Shared de dependerem de `overview` e proíbe `overview` de
     usar casos de uso ou adapters desses módulos (só portas/tipos de domínio e leitura).
2. **Uma query de aplicação** (`GetFinancialOverview`) atrás de uma porta de leitura (`OverviewReadModel`),
   implementada por JDBC/SQL agregado (`JdbcOverviewReadModel`). Não é um agregado nem passa por JPA: é uma
   projeção. O adapter lê as tabelas `transactions` e `accounts` somente para leitura, sempre com `workspace_id`.
3. **Fonte única da semântica de saldo no domínio de Transactions:**
   `TransactionType.originEffect(direction)`, `TransactionStatus.affectsBalance()` e
   `Transaction.balanceEffectOn(accountId)`. O SQL é uma reimplementação desse contrato por desempenho; um teste de
   **paridade** (`JdbcOverviewReadModelIT`) compara o SQL com uma implementação de referência em memória que usa
   `balanceEffectOn`, para todos os tipos/status/períodos. Mudou a semântica → o teste quebra.
4. **`Money` (shared)** ganhou `minus` e `negate` (aditivo). Nenhum `float`/`double`; somas em `NUMERIC` no
   PostgreSQL e `BigDecimal` na aplicação.

### API

5. **Um endpoint consolidado:** `GET /api/v1/workspaces/{workspaceId}/overview`.
   - Parâmetros: `period` (`CURRENT_MONTH` padrão | `PREVIOUS_MONTH` | `CUSTOM`), `from`/`to` (só `CUSTOM`,
     inclusivos, no máximo 366 dias), `recentLimit` (1–20, padrão 5).
   - Uma chamada alimenta o bloco inteiro da Home (a UI mostra loading único e tem um estado `Partial` simples).
     Endpoints granulares ficam para quando houver necessidade real; o histórico completo continua em
     `/transactions` com filtros e paginação.
   - `/api/v1/home` (05.6) será o composto futuro (Overview + Health + Insights, M6/M7) e consumirá este módulo.
   - Erros: 400 `VALIDATION_FAILED` (`period`, `from`, `to`, `recentLimit`), 404 `WORKSPACE_NOT_FOUND`
     (inexistente e sem membership são indistinguíveis — ADR-0003), 401. A autorização roda antes da validação.
6. **Resposta** (`state`, `period`, `summary`, `cashFlow`, `accounts`, `recentTransactions`), dinheiro como
   `{amount: "string", currency}` (`MoneyDto`).

### Regras financeiras

7. **Status:** só `POSTED` entra em saldo, fluxo de caixa e contagem de movimentações. `PENDING` não entra
   (apenas `summary.pendingTransactions` informa quantas há no período e aparece em `recentTransactions` com seu
   status). `CANCELLED` e `REVERSED` nunca entram em nada.
8. **Saldo por conta** = soma dos efeitos das transações `POSTED` com `occurredOn <= period.to` (a data-base do saldo
   é o fim do período; no mês corrente é "hoje"). Efeitos: `INCOME`/`REFUND` creditam; `EXPENSE` debita;
   `ADJUSTMENT` conforme a direção; `TRANSFER` debita a origem e credita o destino.
   - **Saldo total** = soma das contas **ativas** com `includedInTotal = true`. Contas arquivadas saem do total e da
     lista de contas (mesmo padrão de `ListAccounts`), mas seus nomes continuam resolvidos nas movimentações.
   - Contas fora do total aparecem na lista com saldo próprio e `includedInTotal = false`.
9. **Fluxo de caixa** (`cashFlow`) do período, só `POSTED`, só contas ativas e incluídas no total:
   - `income` = `INCOME`; `expense` = `EXPENSE` **bruta**; `refunds` = `REFUND`;
     `net = income + refunds − expense`.
   - **Transferência nunca é receita nem despesa.** Entra apenas em `cashFlow.transfers` (quantidade e volume
     informativos, todas as contas) e move o saldo das contas envolvidas. Transferência entre duas contas do total não
     altera o saldo total; entre uma conta do total e uma de fora, altera, sem virar receita/despesa.
   - **Reembolso nunca é receita.** Reduz o resultado como devolução de despesa e é mostrado em linha própria
     (`refunds`). O `expense` é mantido bruto: reembolso de compra de mês anterior não torna a despesa do mês
     negativa. A UI pode exibir "gasto líquido" como `expense − refunds` sem regra nova (ambos vêm da API). O
     reembolso sem vínculo (`refundOfTransactionId` nulo) é tratado igual: é devolução, não renda.
   - **Ajuste** (`ADJUSTMENT`, ex. saldo inicial/correção) move o saldo mas não entra no fluxo: não é receita nem
     despesa. Por isso `net` não é necessariamente a variação do saldo no período.
10. **Período:** datas de negócio (`occurredOn`), inclusivas.
    - `CURRENT_MONTH` = dia 1º até hoje (month-to-date); `PREVIOUS_MONTH` = mês civil anterior completo.
    - "Hoje" é calculado no fuso de negócio `shf.overview.zone` (padrão `America/Sao_Paulo`), não em UTC: entre
      21h e 24h de Brasília, UTC já está no dia seguinte. O Workspace ainda não tem fuso; quando tiver (futuro), a
      propriedade é substituída por ele.
    - Transações com data posterior à data-base (lançamentos futuros) não entram no saldo.
11. **Empty state:** `state` ∈ `NO_ACCOUNTS`, `NO_TRANSACTIONS` (há contas, nenhuma transação `POSTED` em
    nenhuma data), `NO_ACTIVITY_IN_PERIOD` (há histórico, nada no período), `READY`.
    - Em `NO_ACCOUNTS` e `NO_TRANSACTIONS` os valores monetários (`totalBalance`, `accounts[].balance`, `cashFlow`)
      são **`null`**, nunca `0.00`, e as consultas de saldo/fluxo nem executam.
    - Em `NO_ACTIVITY_IN_PERIOD` e `READY`, zero é um fato real (`"0.00"`).
12. **Atividade recente:** `POSTED` e `PENDING` do período, de qualquer conta do Workspace (inclusive fora do total
    e arquivadas), mais recentes primeiro (mesma ordem de `/transactions`). Cada item traz `flow`
    (`INFLOW`/`OUTFLOW`/`TRANSFER`, derivado do mesmo contrato do saldo), contas de origem/destino com nome,
    status, valor, data, descrição e vínculo de reembolso. Sem paginação: é um resumo (limite 1–20).

### Persistência e desempenho

13. **PostgreSQL puro, sem cache e sem tabela materializada.** Cinco consultas agregadas por request, nenhuma por
    linha (sem N+1): contas (porta de Accounts), atividade, saldo/movimentações por conta (uma varredura com
    "pernas" origem/destino), fluxo de caixa e recentes. Os últimos três só executam se houver dados.
14. **Migration `V6__index_transactions_for_overview.sql`:** índice parcial e cobrindo
    `(workspace_id, occurred_on) INCLUDE (account_id, destination_account_id, type, adjustment_direction, amount)
    WHERE status = 'POSTED'`. Permite index-only scan nas agregações e não paga o custo de PENDING/anuladas.
    Teste de integração valida a existência e que o planner o usa em tabela grande.
15. **Observabilidade:** log `Overview served workspaceId period state` (sem valores, descrições ou nomes — há
    teste) e `Timer shf.overview.get` com tag `state`. Traces HTTP/JDBC continuam via OpenTelemetry.
16. **Sem eventos:** o Overview é consultado, não publica nem consome eventos neste momento. Quando a outbox
    existir (ADR-0003/0004/0005), os read models de Analytics (M5) poderão ser alimentados por eventos
    `transaction.*`; o Overview só migra para read model persistido se a medição exigir.

## Alternatives

- **Somar em memória (carregar todas as transações):** rejeitado — O(n) em memória por request na tela mais
  acessada. Mantido apenas como implementação de referência de teste.
- **JPA/Criteria para agregar:** rejeitado. A agregação com `CASE`, `FILTER` e pernas de transferência é mais clara e
  eficiente em SQL; o domínio de leitura não precisa de entidades.
- **Saldo materializado/snapshot por conta:** rejeitado agora. Cria segunda fonte de verdade e exige invalidação em
  cada transação. Revisar se a medição mostrar que a varredura por Workspace é lenta (ver Riscos).
- **Redis para cachear a resposta:** rejeitado. PostgreSQL com índice cobrindo atende; cache traria problema de
  invalidação em dado financeiro. Se for preciso: TTL curto (≈30 s), chave `overview:{workspaceId}:{period}`,
  invalidação por evento `transaction.*` e consistência eventual declarada.
- **Reembolso como redução da despesa (expense líquida):** rejeitado como valor principal — despesa do mês ficaria
  negativa quando o reembolso cai num mês diferente do da compra. O valor bruto + `refunds` evita isso e é aditivo.
- **Reembolso como receita:** rejeitado explicitamente (infla receitas e distorce o resultado).
- **Contar transferência como saída e entrada:** rejeitado (spec 03-screens: "transferências entre contas próprias
  não são gasto").
- **Incluir `PENDING` no saldo "projetado":** rejeitado. Estimativas/pendências não são fato (05.13 §5); entram em
  Forecast (M9). O Overview só informa a contagem.
- **Endpoints separados (`/summary`, `/cash-flow`, `/accounts`, `/recent`):** rejeitado por ora — quatro round-trips
  e quatro autorizações para uma tela que sempre usa tudo.
- **`GET /api/v1/home` agora:** adiado; só faz sentido quando houver outros blocos (Health, Insights).
- **Comparação com o período anterior (variação %):** fora de escopo (análise, M5). O contrato é aditivo e aceita
  o campo depois.

## Consequences

- Positivas:
  - saldo e fluxo determinísticos, derivados só de fatos `POSTED`, com contrato único no domínio e paridade testada;
  - a UI distingue "sem dados" de "zero real" sem lógica financeira no frontend;
  - nenhuma tabela, evento ou infraestrutura nova.
- Negativas:
  - o SQL do Overview duplica (de propósito) a semântica de saldo; o teste de paridade é obrigatório;
  - o saldo é recalculado a cada request (varredura do histórico `POSTED` do Workspace até a data-base);
  - conta arquivada com saldo diferente de zero some do total (ver Riscos);
  - `net` não equivale à variação do saldo (ajustes e transferências de/para fora do total).

## Security / Privacy

- Autorização no Workspace antes de qualquer leitura (ADR-0003); toda consulta SQL tem `workspace_id` no predicado;
  `accounts` é unida por `(id, workspace_id)`. Testes de isolamento cobrem o read model (PostgreSQL), a Application e
  a API (inclusive ausência dos dados do outro Workspace no corpo e 404 idêntico para alheio e inexistente).
- Logs sem valores, descrições ou nomes de conta (há teste).
- Descrições aparecem no corpo da resposta (dado do próprio usuário) e nunca em log.

## Financial Integrity

- `NUMERIC(19,4)` + `Money`; sem float/double. Somas exatas (teste com 600 parcelas de centavos).
- `CASE` do saldo sem `ELSE`: tipo/direção novo viraria NULL e o teste de paridade falharia, em vez de somar
  errado em silêncio.
- O Overview nunca escreve.

## Operational Impact

- Migration `V6` somente aditiva (índice parcial). Sem nova dependência.
- Nova propriedade opcional `shf.overview.zone` (padrão `America/Sao_Paulo`).
- Métrica `shf.overview.get` (Micrometer).

## Related Specs

- `specs/05-system-design/05.3-application-architecture.md` (GetFinancialOverview)
- `specs/05-system-design/05.6-api-integration.md` (Home)
- `specs/05-system-design/05.13-consolidated-system-design.md`
- `design/specs/03-screens.md` (D-Home, D-Accounts, D-Transactions)
- `docs/adr/ADR-0001-modular-monolith-clean-architecture.md`
- `docs/adr/ADR-0003-personal-workspace-and-membership-authorization.md`
- `docs/adr/ADR-0004-accounts-module.md`
- `docs/adr/ADR-0005-transactions-module.md`

## Date

2026-10-07
