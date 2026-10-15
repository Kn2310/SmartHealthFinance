# ADR-0014 — Read model de Analytics: projeção materializada por eventos (M5, parte C)

## Status

Accepted

> Decisões aprovadas em 2026-10-13: projeção materializada alimentada por eventos (em vez de SQL sob demanda);
> módulo novo `analytics`; o consumidor recalcula o mês afetado a partir de `transactions` (sem deltas);
> `transaction.*` por fato + `import.completed` para importações; rebuild por runner na inicialização + runbook;
> estornos importados mantêm a semântica atual (INCOME, ADR-0009 §9) — a correção virá de uma decisão de produto
> futura ("marcar como reembolso").

## Context

As telas de Análise (M5, partes D e E) precisam de agregados por mês, dia, categoria e comerciante. A spec 05.7
coloca Analytics entre os dados derivados assíncronos ("Transaction Posted → fan-out → Analytics"), e a 05.13 lista
Analytics como módulo. O Overview (ADR-0006) usa SQL agregado sob demanda e registrou que os read models do M5
poderiam ser alimentados por eventos `transaction.*`. O backlog registrava a falta desses eventos (M2) e o
ADR-0009 §15 adiou o `transaction.*` por lançamento "até haver consumidor".

Requisitos fixos: uma única fonte de semântica (ADR-0006 §9), paridade testada com uma referência em memória,
consumidores idempotentes com inbox, retry e DLQ, rebuild, freshness exposta, `NUMERIC`/`BigDecimal`, isolamento por
Workspace, nenhum dado financeiro em logs e o Overview sem depender de Analytics.

## Decision

### Módulo

1. **Módulo `analytics`**, read-only e a jusante de todos (`identity → accounts → transactions → analytics`;
   Ingestion só pelo nome do evento). ArchUnit: nenhum módulo depende de `analytics`; `analytics` não usa casos de
   uso nem adapters de Transactions/Accounts, nem nada de Ingestion ou Overview.

### Eventos

2. **`transaction.*` v1 pela Transactional Outbox**, na mesma transação do fato (`TransactionEvents`), só quando o
   fato muda:
   - `transaction.created` (lançamento manual), `.posted`, `.cancelled`, `.reversed`;
   - `.categorized`: categoria ou comerciante mudou (escolha do usuário, "aplicar a semelhantes", categorização
     retroativa). Editar a descrição não emite nada (não muda Analytics).
   - Payload: só `transactionId` e `occurredOn` (data de negócio, que diz o mês). Nunca valor, descrição, categoria
     ou comerciante: o consumidor relê a fonte da verdade.
   - **Importação não emite um evento por linha**: o `import.completed` (ADR-0009 §15) cobre o lote e ganhou
     `firstDate`/`lastDate` do arquivo (aditivo).
   - A fonte única da semântica de fluxo passa a ser `CashFlowRole` / `Transaction.cashFlowRole()` no domínio de
     Transactions (INCOME, EXPENSE, REFUND, TRANSFER; ajuste e não-POSTED = NONE). O Overview continua com o próprio
     SQL e o próprio teste de paridade (não muda neste ADR).

### Projeção

3. **Recalcular o mês, não somar deltas.** A projeção `analytics_daily_flows` tem o grão
   dia × conta × papel no fluxo × categoria × comerciante, com `amount NUMERIC(19,4)` e `transaction_count`.
   - Recalcular um (Workspace, mês) = `delete` + `insert ... select` sobre `transactions` (só POSTED, sem ajuste),
     numa transação. Determinístico, idempotente e insensível à ordem dos eventos; o rebuild é a mesma função para
     todos os meses.
   - A conta fica no grão: "só contas ativas incluídas no total" (ADR-0006 §9) é aplicado na **leitura**, juntando com
     `accounts`. Arquivar uma conta ou tirá-la do total vale na hora, sem eventos de Accounts.
   - **Lock por Workspace:** a linha de `analytics_projections` é bloqueada (`FOR UPDATE`) por todo recálculo e
     rebuild do Workspace. Nunca há dois `delete + insert` concorrentes no mesmo mês (teste de concorrência).
   - **Coalescência:** `analytics_projection_months.refreshed_at` guarda quando cada mês foi recalculado. Um evento
     publicado antes de `refreshed_at − margem` já está refletido (o recálculo leu um estado que incluía o fato) e só
     é marcado na inbox. Margem `shf.analytics.coalesce-margin` (2 s) contra diferença de relógio entre instâncias.
   - **Autocorreção:** evento de um Workspace sem projeção na versão atual dispara o rebuild completo dele.

### Consumidor

4. **`analytics.projection`** na fila `shf.analytics.projection` (bindings `transaction.*` e `import.completed`),
   no padrão do Import Worker (ADR-0009 §15): inbox `processed_events` na mesma transação do efeito; retry com
   backoff exponencial + jitter só para falhas transitórias; DLQ `shf.analytics.projection.dlq` para o resto e para
   mensagens ilegíveis ou de versão desconhecida. `import.completed` sem datas só é marcado como processado.

### Leitura

5. **Porta `AnalyticsReadModel`** (JDBC): `monthly`, `byCategory`, `byMerchant`, `daily` e `freshness`. É a base das
   queries da parte D. **Teste de paridade** (`JdbcAnalyticsProjectionIT`): rebuild, recálculo por mês e coalescência
   contra `ReferenceAnalytics`, que usa `cashFlowRole()`, a categoria e o comerciante do domínio, com todos os tipos,
   status, contas fora do total e arquivadas, para vários períodos.

### Freshness

6. **`GET /api/v1/workspaces/{workspaceId}/analytics/status`** → `{state, refreshedAt, pendingEvents}`:
   - `pendingEvents` = eventos `transaction.*`/`import.completed` do Workspace posteriores ao último rebuild que o
     consumidor ainda não marcou na inbox (inclui os que estão na DLQ);
   - `state`: `UP_TO_DATE`, `UPDATING` (há pendentes) ou `NOT_BUILT` (há transações e ainda não há projeção).
   - As respostas da parte D carregam a mesma freshness.

### Rebuild

7. **`RebuildAnalyticsProjection`** (por Workspace, sob o mesmo lock) e **`AnalyticsRebuildRunner`** na inicialização:
   `shf.analytics.rebuild` = `missing` (padrão: Workspaces com transações sem projeção na versão atual — backfill do
   deploy e de nova versão), `all` (runbook) ou `off`. Nada exposto na API pública. Runbook:
   `docs/runbooks/analytics-rebuild.md`. Mudou o que a projeção grava → `RebuildAnalyticsProjection.VERSION + 1`.
   - **Adendo (2026-10-14):** versão 2, pelas chaves de comerciante do normalizador v2 (adendo ao ADR-0013 §1). A
     renormalização roda antes deste runner.

### Persistência

8. `V12__create_analytics_projection.sql`: `analytics_daily_flows` (índice `(workspace_id, day)`),
   `analytics_projections`, `analytics_projection_months` e o índice `(workspace_id, occurred_at)` em
   `outbox_events` para a freshness. Somente aditiva; derivada (pode ser apagada e reconstruída).

### Regras financeiras (sem mudança)

9. Mesma semântica do ADR-0006 §9: receita = INCOME; despesa bruta = EXPENSE; reembolso = REFUND (nunca receita);
   transferência só informativa; ajuste fora; só POSTED; resultado = receitas + reembolsos − despesas.
   - **Estorno importado** continua INCOME (ADR-0009 §9) e infla as receitas até existir "marcar como reembolso"
     (decisão de produto futura, registrada no backlog).
   - **Adendo (2026-10-14):** "marcar como reembolso" existe (ADR-0009 §9, adendo). Ele emite `transaction.reversed`
     e `transaction.created`, que este consumidor já trata; a projeção e o rebuild não mudaram (teste no
     `AnalyticsWorkerIT`).

## Alternatives

- **SQL agregado sob demanda (como o Overview):** mais simples e com consistência forte; rejeitada pelo produto em
  favor do alinhamento com 05.7 (derivados assíncronos) e da leitura proporcional a meses, não ao histórico.
- **Deltas incrementais pelo payload:** exigiria valores no payload, ordem e estado anterior corretos; erro acumulado
  só sairia no rebuild.
- **`transaction.created` por linha importada:** milhares de mensagens por arquivo (o ADR-0009 §15 evitou isso).
- **Híbrido (SQL + eventos sem uso real):** eventos e consumidor sem efeito de negócio.
- **Rebuild por endpoint público ou actuator:** operação pesada exposta ao usuário, ou exposição de gerenciamento que
  hoje não existe.
- **Analytics dentro do `overview`:** misturaria a Home com a Análise.

## Consequences

- Positivas:
  - leituras de Analytics custam proporcional ao período (linhas por dia), não ao histórico de transações;
  - semântica de fluxo única no domínio (`CashFlowRole`) e paridade testada;
  - os eventos `transaction.*` ficam prontos para Health, Forecast e Insights (M6/M7/M9) em fan-out;
  - autocorreção e rebuild baratos: a projeção é sempre derivável.
- Negativas:
  - consistência eventual: depois de um comando, a Análise pode levar alguns segundos (a freshness mostra
    `UPDATING`);
  - mais peças para operar (fila, DLQ, runner, runbook) e mais tabelas;
  - uma importação recalcula todos os meses do período do arquivo (aceitável: um mês é uma consulta agregada);
  - o lock por Workspace serializa recálculos do mesmo Workspace (sem impacto entre Workspaces).

## Security / Privacy

- Payload dos eventos e logs só com IDs, datas, meses e contagens (teste de logs no `AnalyticsWorkerIT`).
- Projeção sempre por `workspace_id`; FK para `workspaces`; testes de isolamento na leitura e o endpoint de status na
  suíte `WorkspaceIsolationIT`.
- A projeção é dado financeiro derivado: segue a retenção dos fatos de origem (05.12) e é apagada com o Workspace.

## Financial Integrity

- Nada de `float`/`double`: `NUMERIC` no banco, `BigDecimal`/`Money` na aplicação.
- O recálculo sempre relê a fonte da verdade; a projeção nunca é fonte de nada.
- Teste de paridade e teste de concorrência (sem linhas duplicadas).

## Operational Impact

- Migration `V12`; fila e DLQ novas no exchange `shf.events`; propriedades `shf.analytics.*`.
- Primeiro deploy: o runner (`missing`) faz o backfill de todos os Workspaces com transações.
- Métricas: `shf.analytics.projection.refresh` (timer por `outcome`) e `shf.analytics.projection.months`
  (`recomputed`/`coalesced`), além das métricas de outbox existentes.

## Related Specs

- `specs/05-system-design/05.7-event-async.md`, `05.4-data-architecture.md` (Read models), `05.9`, `05.12`, `05.13`
- `docs/adr/ADR-0006-overview-module.md` (§9, §16), `ADR-0009-csv-ofx-import.md` (§14, §15), `ADR-0013`

## Date

2026-10-13
