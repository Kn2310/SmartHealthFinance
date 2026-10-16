# ADR-0018 — Cálculo, persistência e atualização do Financial Health (M6, parte C)

## Status

Accepted

> Decisões aprovadas em 2026-10-15: snapshot materializado por Workspace, atualizado por eventos com debounce (no lugar
> do cálculo sob demanda e do híbrido com mês fechado); gatilho `analytics.projection-updated` publicado pelo
> Analytics; evento `account.changed` publicado por Accounts; resultado em colunas + JSONB; demais pontos (módulo,
> ArchUnit, debounce 10 s / máx. 60 s, mês corrente + 12 fechados por versão, job diário e checagem da data na
> leitura, rebuild na inicialização, status e métricas) como propostos.

## Context

O ADR-0017 fixou o modelo `health-v1`. Falta decidir onde e quando o cálculo roda. O 05.9 exige freshness do Health de
no máximo 5 min; o 05.4 diz que Health é um read model derivado; o 05.7 desenha o Health como consumidor do pipeline
financeiro.

Dois fatos do código condicionam a decisão:

- o Health lê a projeção de Analytics (ADR-0014), que é atualizada de forma assíncrona e **não publicava nada** depois
  de atualizar: consumir `transaction.*` em paralelo leria uma projeção ainda velha;
- Accounts **não publicava eventos**, e o Health depende de contas (tipo `SAVINGS`, arquivamento, inclusão no total).
  Além disso, o calendário muda o resultado sozinho: na virada do mês, o mês corrente e a janela W mudam sem nenhuma
  transação.

## Decision

### 1. Módulo `health`

- `health.domain`: `HealthCalculator` puro e determinístico (sem I/O, sem Spring), com as curvas, dimensões, score,
  confiança, frase, fatores e próximos passos do ADR-0017. Testes de tabela.
- `health.application`: montagem da entrada a partir das portas (`HealthInputLoader`), casos de uso de marcação,
  recálculo, rebuild e status; portas próprias (`HealthSnapshotStore`).
- `health.infrastructure`: JDBC do snapshot, mensageria, jobs e runner.
- **Fronteiras (ArchUnit):**
  - nenhum módulo depende de `health`;
    **Adendo (2026-10-16, ADR-0022 §1):** exceto `insights`, que lê a nova porta só de leitura `HealthSnapshotReader`
    e os modelos de `health.domain`.
  - `health` lê apenas portas e modelos de domínio: `analytics.application.port` e `analytics.domain`,
    `overview.application.port` e `overview.domain`, `accounts.domain` (o `AccountRepository`, como o Overview) e
    `identity` para Workspace e autorização;
  - `health` não usa casos de uso nem adapters de outros módulos, nem nada de Ingestion; de Transactions, só os
    tipos de domínio que as portas do Analytics expõem (`CashFlowRole`).

### 2. Gatilhos

- **`analytics.projection-updated` v1** (adendo ao ADR-0014): o Analytics grava o evento na outbox **na mesma transação**
  de cada recálculo de mês e de cada rebuild. Payload: só `months` (lista `YYYY-MM`; vazia no rebuild = todos). O Health
  sempre lê uma projeção que já reflete o fato. Eventos só marcados na inbox (duplicados) não publicam nada.
- **`account.changed` v1**: Accounts grava o evento na outbox na mesma transação de criar, editar (só quando algo mudou),
  arquivar e reativar. Payload: só `accountId`.
- **Fila `shf.health.snapshot`** (bindings `analytics.projection-updated` e `account.changed`), consumidor
  `health.snapshot` no padrão do ADR-0014 §4: inbox `processed_events` na mesma transação do efeito, retry com backoff
  exponencial + jitter só para falhas transitórias, DLQ `shf.health.snapshot.dlq` para o resto.
- **Adendo (2026-10-16, ADR-0022 §2):** cada recálculo do snapshot (job e rebuild) grava **`health.snapshot-updated` v1**
  na outbox, na mesma transação. Payload: só `algorithmVersion`. O consumidor é o módulo de Insights.

### 3. Debounce

- O consumidor **não calcula**: marca o Workspace como sujo em `health_workspaces`:
  `dirty_since` (primeiro evento ainda não refletido) e `due_at = min(agora + 10 s, dirty_since + 60 s)`.
  Cada novo evento adia o `due_at`, nunca além do teto.
- O job `HealthSnapshotJob` (a cada 5 s) pega os Workspaces vencidos com `FOR UPDATE SKIP LOCKED` (várias instâncias
  não recalculam o mesmo Workspace) e recalcula o snapshot inteiro de cada um, numa transação por Workspace.
- Um evento que chega durante um recálculo marca o Workspace de novo; o recálculo só limpa a marca se ela não mudou
  desde que começou (`dirty_since` igual).
- Freshness do Health = atraso do Analytics + no máximo 60 s (+ 5 s do job), dentro dos 5 min do 05.9.
- Parâmetros: `shf.health.debounce.quiet` (10 s), `.max-wait` (60 s), `shf.health.job.interval-ms` (5000).

### 4. Snapshot

- `health_snapshots`: uma linha por (Workspace, mês de referência, `algorithm_version`), com `score`, `state`,
  `partial`, `estimated`, `as_of`, `computed_at` e `result` JSONB (dimensões, evidências, cobertura, confiança, frase,
  fatores, próximos passos). Dinheiro e razões no JSONB como **strings decimais**, nunca número JSON.
- Cada recálculo grava o **mês corrente + os 12 meses fechados anteriores** (só a partir do mês do primeiro lançamento),
  na versão atual, e apaga as linhas da versão atual fora desse intervalo. A variação do mês mais antigo usa um 14º
  cálculo que não é gravado.
- Linhas de **outras versões nunca são apagadas nem reescritas** pelo recálculo: um `health-v2` grava linhas novas e o
  histórico v1 fica (ADR-0017 §5).
- Leitura de um mês fechado depois do 12º: sem linha (a API da parte D responde como sem dados para o mês).
- Entrada de cada mês: uma leitura de `AnalyticsReadModel.entries` cobrindo todos os meses de uma vez (mês corrente
  − 19 meses até hoje), `firstActivity`, `AccountRepository.findAllByWorkspace` (contas `SAVINGS` ativas **hoje**) e
  `OverviewReadModel.accountFigures` na data-base de cada mês (saldo no fim do mês fechado; hoje no corrente).
- Na evolução, o conjunto de contas de reserva é o atual (o sistema não guarda o histórico de tipo/arquivamento); o
  saldo de cada uma é o da data-base. Documentado no método.

### 5. Mudanças sem evento (calendário)

- **Job diário** às 00:05 do fuso de negócio (`shf.health.daily.cron`): marca todos os Workspaces com snapshot como
  sujos (o mês corrente avança um dia; na virada, o mês fecha).
- **Na leitura** (parte D): snapshot do mês corrente com `as_of` anterior a hoje → marca o Workspace como sujo e
  responde o snapshot existente com estado `RECALCULATING`.

### 6. Rebuild

- `RebuildHealthSnapshots` (por Workspace, mesma função do recálculo) e `HealthRebuildRunner` na inicialização,
  **depois** do rebuild do Analytics: `shf.health.rebuild` = `missing` (padrão: Workspaces com transações sem snapshot
  na versão atual), `all` (runbook) ou `off`. Uma falha não derruba a API: o Workspace também é marcado sujo no
  próximo evento. Runbook: `docs/runbooks/health-rebuild.md`.

### 7. Freshness e observabilidade

- `GET /api/v1/workspaces/{workspaceId}/health/status` → `{state, computedAt, analyticsRefreshedAt}`:
  - `NOT_BUILT`: há transações e nenhum snapshot na versão atual;
  - `RECALCULATING`: Workspace sujo, eventos do Health pendentes, Analytics `UPDATING` ou snapshot com `as_of` anterior
    a hoje;
  - `UP_TO_DATE`: os demais casos.
- Métricas: `shf.health.snapshot.freshness` (gauge, segundos desde o `dirty_since` mais antigo), timer
  `shf.health.snapshot.compute` (por resultado), contador `shf.health.snapshot.marked`. Spans do job e do consumidor via
  OpenTelemetry. Logs só com ids, contagens e meses: nunca score, valor, categoria ou texto.

### 8. Testes

- Domínio: casos de tabela de cada curva, dimensão, status, score, confiança, frase, fatores e próximos passos,
  incluindo o fixture de `design/specs/09-dados-de-exemplo.md`.
- **Paridade:** o snapshot gravado é igual ao `HealthCalculator` rodado direto sobre as portas para o mesmo fixture
  (não há SQL novo com semântica financeira: o Health só lê as portas existentes).
- Consumidor: idempotência (reentrega), debounce (teto), DLQ, `SKIP LOCKED` concorrente, logs sem dado financeiro.
- Isolamento por Workspace na marcação, no recálculo e na leitura.

## Alternatives

- **Cálculo sob demanda** (recomendado na proposta): mais simples, sem tabela nem consumidor; preterido porque não
  persiste o resultado e o histórico por versão.
- **Híbrido** (mês corrente sob demanda + meses fechados persistidos): guarda o histórico, mas exige invalidação dos
  meses fechados por marca d'água da projeção.
- **Consumir `transaction.*` em paralelo** e esperar a freshness do Analytics: corrida e re-enfileiramento com atraso.
- **Checar contas na leitura** ou **só no job diário**: consulta extra a cada leitura, ou freshness acima de 5 min
  depois de editar uma conta.
- **Tabelas normalizadas** por dimensão e evidência: tipadas no banco, mas 3–4 tabelas para um read model reconstruível.

## Consequences

- Leitura barata e histórico preservado por versão; o score exibido é exatamente o gravado.
- Segunda cadeia de consistência eventual (Analytics → Health): depois de um comando, o score pode levar até ~1 min a
  mais que a Análise; a UI mostra `RECALCULATING`.
- Analytics → Health fica serial; justificado porque o Health lê o Analytics (05.7 pede para evitar só a dependência
  serial **desnecessária**).
- Accounts passa a publicar eventos (primeiro produtor no módulo); nenhum consumidor existente muda.
- Mais superfície operacional: fila, DLQ, job, runner e runbook.

## Security / Privacy

- Payloads só com ids e meses; nenhum valor, nome, categoria ou texto.
- Snapshot é dado financeiro derivado do Workspace: sempre filtrado por `workspace_id`; apagado e reconstruível.
- Logs, métricas e traces sem score, valores ou textos (05.9/05.12).

## Financial Integrity

- Uma única semântica de fluxo e saldo: o Health só lê `AnalyticsReadModel` e `OverviewReadModel`.
- Cálculo puro, determinístico e versionado; `BigDecimal` em tudo; arredondamentos do ADR-0017.
- Recálculo idempotente (substitui o snapshot da versão atual), insensível à ordem dos eventos.

## Operational Impact

- Migration somente aditiva (`health_workspaces`, `health_snapshots`).
- Nova fila e DLQ; replay da DLQ seguido de rebuild (runbook).
- O recálculo de um Workspace lê uma vez as linhas do fluxo de ~19 meses e 13 saldos; custo baixo no MVP.

## Related Specs

- `docs/adr/ADR-0017-financial-health-model.md`, `ADR-0014-analytics-read-model.md` (adendo), `ADR-0004`, `ADR-0006`
- `specs/05-system-design/05.4-data-architecture.md`, `05.7-event-async.md`, `05.9-observability-operations.md`,
  `05.12-lgpd-privacy-data-governance.md`, `05.13-consolidated-system-design.md`
- `docs/runbooks/health-rebuild.md`

## Date

2026-10-15
