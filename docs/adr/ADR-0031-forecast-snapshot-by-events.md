# ADR-0031 — Cálculo, persistência e atualização do Forecast (M9, Sessão 7.1, parte B)

## Status

Accepted

> Decisões aprovadas em 2026-10-29:
> - snapshot materializado por Workspace, atualizado por eventos com debounce (no lugar do cálculo sob demanda e do
>   híbrido);
> - Forecast **em paralelo** ao Health, os dois depois do Analytics: `Analytics → {Health ‖ Forecast} → Insights`;
> - previsto × realizado: projeção congelada na virada do mês e realizado reconciliado até o dia 5 do mês seguinte;
> - ações em recorrências assíncronas: gravam a série e marcam o Workspace, sem evento de integração e sem recálculo
>   na requisição;
> - o fan-out do 05.13 §14 e do 05.7 é tratado só neste ADR, sem adendo nas specs;
> - demais pontos (módulo, ArchUnit, tabelas, job diário, rebuild, status e métricas) como propostos.

## Context

O ADR-0030 fixou o modelo `forecast-v1` e deixou para esta parte onde e quando o cálculo roda, a persistência e a
freshness. O 05.9 e o 05.13 §15 pedem freshness do Forecast de no máximo 15 min; o 05.14 pede API com p95 ≤ 500 ms; o
05.13 §14 e o 05.7 pedem fan-out e proíbem dependência serial **desnecessária**.

Fatos do código e do modelo que condicionam a decisão:

- o Forecast lê a projeção do Analytics (ADR-0030, "uma única fonte de semântica financeira"), que é atualizada de
  forma assíncrona e publica `analytics.projection-updated` (ADR-0018 §2); consumir `transaction.*` em paralelo leria
  uma projeção velha, o mesmo argumento do ADR-0018;
- os demais insumos já têm eventos: `account.changed` (contas), `card.*` e `invoice.closed` (cartões). Compras,
  pagamentos e créditos manuais no cartão emitem `transaction.*`, e a importação de cartão emite `import.completed`;
  os dois chegam ao Analytics e dali ao `analytics.projection-updated`;
- parte do resultado muda só com o calendário: ocorrência no ciclo, pendentes, restante do mês, `STALE`, janela de
  30 dias e a virada do mês (ADR-0030 §2, §3);
- as recorrências guardam estado do usuário que nenhum rebuild pode apagar (ADR-0030, Consequences);
- o previsto × realizado exige congelar a projeção do mês que fechou (ADR-0030 §6);
- o D15 (`insights-v3`) depende do menor saldo projetado (ADR-0030 §8) e os Insights só são disparados por evento
  (ADR-0022 §2);
- o Forecast não lê nada do Health.

## Decision

### 1. Módulo `forecast`

- `forecast.domain`: detecção de recorrências e projeções do ADR-0030 como funções puras e determinísticas (sem I/O,
  sem Spring), `BigDecimal` em tudo. Testes de tabela.
- `forecast.application`: montagem da entrada pelas portas (`ForecastInputLoader`), casos de uso de marcação,
  recálculo, ações em recorrências, rebuild e status; portas próprias `ForecastSnapshotStore` e
  `RecurringSeriesRepository`; a porta só de leitura **`ForecastReader`** (ADR-0030 §7), que o
  `ForecastSnapshotStore` estende.
- `forecast.infrastructure`: JDBC, mensageria, jobs e runner. `forecast.presentation`: só o `status` nesta parte.
- **Fronteiras (ArchUnit):**
  - nenhum módulo depende de `forecast`, exceto `insights`, que lê só `forecast.application.port.ForecastReader` e
    `forecast.domain` (D15) e, na Sessão 7.2, Goals e Simulation, pela mesma porta;
  - `forecast` lê apenas portas e modelos de domínio: `analytics.application.port` e `analytics.domain`,
    `overview.application.port` e `overview.domain`, `cards.application.port.CardPortfolio` e `cards.domain`,
    `accounts.domain` e `identity` para Workspace e autorização; de Transactions, só os tipos que essas portas expõem;
  - `forecast` não depende de `health` nem de `insights`, nem usa casos de uso ou adapters de outros módulos.

### 2. Topologia e gatilhos

```text
transaction.* / import.completed → Analytics ─ analytics.projection-updated ─┬→ Health   ─ health.snapshot-updated ───┐
account.changed, card.*, invoice.closed ─────────────────────────────────────┴→ Forecast ─ forecast.snapshot-updated ─┴→ Insights
```

- **Fila `shf.forecast.snapshot`**, consumidor `forecast.snapshot`, com os mesmos bindings da fila do Health:
  `analytics.projection-updated`, `account.changed`, `card.created`, `card.updated`, `card.archived`,
  `card.reactivated` e `invoice.closed`. Padrão do ADR-0014 §4: inbox `processed_events` na mesma transação do efeito,
  retry com backoff exponencial + jitter só para falhas transitórias, DLQ `shf.forecast.snapshot.dlq`.
- O Forecast **não** consome eventos de origem (`transaction.*`, `import.completed`) nem `health.snapshot-updated`.
- **`forecast.snapshot-updated` v1**: gravado na outbox na mesma transação de cada recálculo (job e rebuild). Payload:
  só `algorithmVersion`. Eventos só marcados na inbox (duplicados) não publicam nada.
- **Fan-out (05.13 §14, 05.7):** Health e Forecast são independentes entre si e rodam em paralelo. O serial Analytics →
  Forecast é **necessário**, porque o Forecast lê o read model do Analytics (mesma justificativa do ADR-0018); pôr o
  Forecast depois do Health seria o serial desnecessário que o 05.7 proíbe. Não há adendo nas specs.

### 3. Debounce e job (padrão do ADR-0018 §3)

- O consumidor **não calcula**: marca o Workspace em `forecast_workspaces` (`dirty_since`, `due_at = min(agora + 10 s,
  dirty_since + 60 s)`). Cada novo evento adia o `due_at`, nunca além do teto.
- `ForecastSnapshotJob` (a cada 5 s) pega os vencidos com `FOR UPDATE SKIP LOCKED` e recalcula cada Workspace numa
  transação: redetecta as séries (§4), calcula as projeções, congela e reconcilia o mês fechado (§6), grava o snapshot
  e publica o evento. Uma falha desfaz e adia só aquele Workspace (`failure-backoff`). A gravação só limpa a marca se
  ela não mudou desde o início.
- Parâmetros: `shf.forecast.debounce.quiet` (10 s), `.max-wait` (60 s), `shf.forecast.job.interval-ms` (5000),
  `.batch-size` (50), `.failure-backoff` (60 s).

### 4. Persistência (`V20__create_forecast.sql`)

- **`forecast_workspaces`**: marca de recálculo (`dirty_since`, `due_at`), uma linha por Workspace.
- **`forecast_recurring_series`**: as séries do ADR-0030 §2, por (Workspace, `direction`, `key`). Colunas detectadas
  (canal, dia, valor esperado, faixa, confiança, ocorrências, `algorithm_version`) separadas do estado do usuário
  (`status`, `origin`, `user_expected_amount`, `user_expected_day`, `status_changed_at`).
  - a redetecção só escreve as colunas detectadas; **nenhum** recálculo ou rebuild apaga a tabela ou escreve o estado do
    usuário;
  - uma chave que deixa de ser candidata continua na tabela: `DETECTED` sai das projeções, `CONFIRMED` vira `STALE`
    pela regra dos 3 meses;
  - constraints espelham o domínio (`status`, `origin`, `direction`, dia 1–31, valores `NUMERIC(19,4)` positivos).
- **`forecast_snapshots`**: uma linha por (Workspace, `algorithm_version`), com `as_of`, `computed_at` e `result` JSONB
  (as projeções do ADR-0030 §3, com `basis`, partes, faixas, confiança, limitações e evidências). Dinheiro no JSONB
  como **string decimal**, nunca número JSON. Linhas de outras versões nunca são apagadas nem reescritas.
- **`forecast_month_outcomes`**: uma linha por (Workspace, mês, `algorithm_version`), com a projeção congelada do mês
  (despesa, receita e resultado, com faixa e confiança), o realizado, o erro, `frozen_at` e `settled_at`.
- Tudo com `workspace_id` em chave e índice; dinheiro em `NUMERIC(19,4)`.

### 5. Ações em recorrências

- Confirmar, ignorar e editar gravam a série e marcam o Workspace com `due_at = agora` (sem debounce), **na mesma
  transação**. Não há evento de integração: é estado interno do módulo e nenhum outro módulo consome.
- A resposta devolve a série atualizada e o estado `RECALCULATING`; o snapshot fica pronto no próximo ciclo do job
  (~5–10 s) e o `forecast.snapshot-updated` leva o D15 junto.
- Sem recálculo na requisição: evita o lock do Workspace dentro do request, a colisão com o job e o risco ao p95.
- Auditoria: log só com ids, ação e status. O contrato das ações é da parte C.

### 6. Calendário, virada do mês e previsto × realizado

- **Job diário** às 00:07 do fuso de negócio (`shf.forecast.daily.cron`): marca todos os Workspaces com snapshot como
  sujos (o dia avança: ocorrências, pendentes, `STALE`, janela de 30 dias). O `invoice.closed` das 00:10 marca de novo;
  o segundo recálculo é aceito.
- **Na leitura**: snapshot com `as_of` anterior a hoje marca o Workspace e responde o snapshot existente com
  `RECALCULATING`.
- **Congelamento**: quando o recálculo encontra o snapshot atual com `as_of` num mês anterior ao corrente, ele primeiro
  grava a projeção daquele mês em `forecast_month_outcomes` (`ON CONFLICT DO NOTHING`), com o realizado do momento.
  A projeção congelada nunca é reescrita.
- **Reconciliação até D+5**: enquanto a data de negócio for até o dia 5 do mês seguinte, cada recálculo regrava só o
  realizado e o erro (absorve importações atrasadas). O primeiro recálculo depois do dia 5 grava `settled_at` e
  emite `shf.forecast.month.error`; a partir daí a linha é imutável.
- Workspace sem snapshot no mês que fechou (criado no mês corrente ou sem histórico suficiente) não grava outcome.

### 7. Insights

- A fila `shf.insights.generation` ganha o binding `forecast.snapshot-updated` (adendo ao ADR-0022). Os dois eventos só
  marcam o Workspace; o debounce dos Insights junta os dois quando chegam juntos.
- Uma geração que roda com o Forecast ainda em recálculo é corrigida pela próxima, disparada pelo
  `forecast.snapshot-updated`: cada entrada só publica quando está pronta, e o último evento faz o estado convergir.
  Os detectores D1–D14 não mudam de resultado; a reconciliação idempotente não grava nada novo.
- O status dos Insights passa a `UPDATING` também quando o Forecast está em recálculo.

### 8. Rebuild

- `RebuildForecastSnapshots` (por Workspace, mesma função do recálculo) e `ForecastRebuildRunner` na inicialização,
  **depois** do rebuild do Analytics: `shf.forecast.rebuild` = `missing` (padrão: Workspaces com transações sem
  snapshot na versão atual), `all` (runbook) ou `off`. O rebuild marca os Workspaces para o job; nunca apaga séries
  nem outcomes. Runbook: `docs/runbooks/forecast-rebuild.md`.

### 9. Freshness e observabilidade

- `GET /api/v1/workspaces/{workspaceId}/forecast/status` → `{state, computedAt, asOf, analyticsRefreshedAt}`:
  - `NOT_BUILT`: há transações e nenhum snapshot na versão atual;
  - `RECALCULATING`: Workspace marcado, eventos do Forecast pendentes, Analytics `UPDATING` ou snapshot com `as_of`
    anterior a hoje;
  - `UP_TO_DATE`: os demais casos.
- Os demais contratos (projeções, `/recurring-expenses`, ações, erros, idempotência) ficam para a parte C.
- **Freshness estimada**: Forecast ~20 s típico e ~1,5 min no pior caso com fluxo contínuo de eventos (relay da outbox
  + Analytics + até 65 s de debounce e job); D15 ~35 s típico e ~2,5 min no pior caso. Dentro dos 15 min do 05.9.
- Métricas: `shf.forecast.snapshot.freshness` (gauge, segundos desde o `dirty_since` mais antigo; alerta acima de
  10 min), timer `shf.forecast.snapshot.compute` (tag `result`), contadores `shf.forecast.snapshot.marked` e
  `shf.forecast.recurrence.action` (tag `action`), `shf.forecast.month.error` (§6). Spans do job e do consumidor via
  OpenTelemetry. Logs só com ids, contagens, meses e estados.

### 10. Testes

- Domínio: tabela por regra do ADR-0030 (detecção, confiança, ciclo de vida, `STALE`, ocorrência no ciclo, cada
  projeção, incerteza, arredondamento), incluindo o fixture de `design/specs/09-dados-de-exemplo.md` (R$ 4.664,10 e
  Aurora ≈ R$ 3.012,10).
- **Paridade:** o snapshot gravado é igual às funções do domínio rodadas direto sobre as portas (não há SQL novo com
  semântica financeira).
- Estado do usuário: redetecção e rebuild nunca mudam `status`, `origin` nem `user*`.
- Virada do mês: congelamento idempotente, reconciliação até D+5, imutável depois.
- Consumidor: idempotência (reentrega), debounce (teto), DLQ, `SKIP LOCKED` concorrente, logs sem dado financeiro.
- Integração: importação de cartão → `analytics.projection-updated` → snapshot do Forecast reflete a fatura.
- Isolamento por Workspace na marcação, no recálculo, nas ações e na leitura.

## Alternatives

- **Sob demanda na leitura:** sem tabela de snapshot, mas cada tela refaz 6 meses de Analytics, cartões e Overview
  (risco ao p95 de 500 ms), a redetecção vira escrita dentro de um GET, não há onde congelar o previsto × realizado e o
  D15 fica sem gatilho.
- **Híbrido** (snapshot do pesado + ajuste do que depende de "hoje" na leitura): duas rotas de cálculo que podem
  divergir da tela e do D15; a checagem de `as_of` na leitura já cobre a virada do dia.
- **Forecast depois do Health:** cadeia única para os Insights, mas serial desnecessário (05.7) e freshness maior.
- **Evento `forecast.recurrence-changed`:** sem consumidor fora do módulo; marcar o Workspace na mesma transação basta.
- **Recalcular na requisição da ação:** resposta já atualizada, mas com lock do Workspace no request e colisão com o
  job.
- **Realizado medido só na virada (D+0):** mais simples, mas importações atrasadas inflariam o erro.

## Consequences

- Leitura barata; o valor exibido é exatamente o gravado, com versão e evidências.
- Terceira cadeia de consistência eventual: depois de um comando, a estimativa pode levar até ~1,5 min a mais que a
  Análise; a UI mostra `RECALCULATING`.
- Os Insights passam a ter duas entradas: uma mudança pode gerar duas vezes, absorvidas pelo debounce ou pela
  reconciliação idempotente.
- Se o Forecast parar (DLQ, falha), o D15 para junto; o status dos Insights mostra `UPDATING` e o runbook cobre o
  replay.
- Mais superfície operacional: fila, DLQ, dois jobs, runner e runbook.
- Risco de escala do job diário: N Workspaces × custo do cálculo ÷ instâncias (ex.: 10 mil × 200 ms ≈ 33 min numa
  instância) pode passar dos 15 min. No MVP, só o alerta de freshness; priorização por leitura recente fica para quando
  a métrica mostrar necessidade.

## Security / Privacy

- Payloads só com `algorithmVersion` e ids; nenhum valor, nome, categoria, comerciante ou texto.
- Séries, snapshots e outcomes são dados financeiros do Workspace: sempre filtrados por `workspace_id`; o status e as
  ações autorizam o membro antes de ler ou gravar (ADR-0003).
- Logs, métricas e traces sem valores, chaves de série ou textos (05.9/05.12).

## Financial Integrity

- Uma única semântica de fluxo, saldo e fatura: o Forecast só lê `AnalyticsReadModel`, `OverviewReadModel`,
  `CardPortfolio` e contas.
- Cálculo puro, determinístico e versionado; recálculo idempotente e insensível à ordem dos eventos.
- Nada `ESTIMATED` entra em saldo, fluxo, Health ou nos Insights existentes (ADR-0030 §1); só o D15 lê o Forecast.
- Projeção congelada imutável; realizado fixo depois do D+5.

## Operational Impact

- Migration somente aditiva (`V20`).
- Nova fila e DLQ (`shf.forecast.snapshot[.dlq]`); replay da DLQ seguido de rebuild (runbook).
- Jobs: recálculo (5 s) e diário (00:07); runner na inicialização depois do Analytics.
- Freshness esperada bem abaixo dos 15 min; alerta acima de 10 min.

## Related Specs

- `docs/adr/ADR-0030-forecast-model.md` (adendo), `ADR-0022-insights-generation-by-events.md` (adendo),
  `ADR-0014-analytics-read-model.md` (adendo), `ADR-0018`, `ADR-0027`, `ADR-0003`
- `specs/05-system-design/05.7-event-async.md`, `05.9-observability-operations.md`, `05.12-lgpd-privacy-data-governance.md`,
  `05.13-consolidated-system-design.md` §8, §14, §15, `05.14-quality-attributes.md`
- `docs/runbooks/forecast-rebuild.md` (a criar na implementação)

## Date

2026-10-29
