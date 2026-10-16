# ADR-0022 — Geração de Insights encadeada no Health (M7, parte B)

## Status

Accepted

> Decisões aprovadas em 2026-10-16: estratégia **encadeada no Health** (o Health publica `health.snapshot-updated` v1
> e os Insights consomem só esse evento, com o debounce e o job do ADR-0018), em vez de fan-out paralelo, geração sob
> demanda ou geração diária própria; retenção de **13 meses** para insights não visíveis.

## Context

O ADR-0021 fixou o modelo `insights-v1`: 10 detectores determinísticos sobre a projeção de Analytics (D1–D6, D10) e
sobre o snapshot do Health (D7–D9), pontuação, ciclo de vida e deduplicação por fingerprint. Falta decidir onde e
quando a geração roda, como o resultado é guardado e como se mede a freshness (05.9: "insight generation").

Restrições do código atual:
- o Health grava o snapshot de forma assíncrona (ADR-0018) e **não publicava nada** depois: reagir a
  `analytics.projection-updated` em paralelo leria um snapshot velho nos detectores D7–D9;
- o calendário muda o resultado sem nenhuma transação (o período avaliado vira no dia 7, §1 do ADR-0021);
- ao contrário do Health, os insights guardam **estado do usuário** (dispensado, visto, feedback), que não é
  derivado: a geração nunca pode apagar e recriar.

## Decision

### 1. Módulo `insights`

- `insights.domain`: detectores puros (sem I/O, sem Spring), pontuação, textos, reconciliação do ciclo de vida
  (`InsightReconciler`). Testes de tabela.
- `insights.application`: montagem da entrada pelas portas (`InsightInputLoader`), geração (`InsightGenerator`),
  casos de uso de marcação, processamento, rebuild, status e expurgo; porta própria `InsightStore`.
- `insights.infrastructure`: JDBC, mensageria, jobs e runner. `insights.presentation`: só o `status` nesta parte.
- **Fronteiras (ArchUnit):**
  - nenhum módulo depende de `insights`;
  - `insights` lê apenas portas e modelos de domínio: `analytics.application.port` e `analytics.domain`,
    `health.application.port.HealthSnapshotReader` (nova porta só de leitura, §2) e `health.domain`,
    `transactions.domain` (os tipos que as portas expõem) e `identity` para Workspace e autorização;
  - `insights` não usa casos de uso nem adapters de outros módulos, nem nada de Ingestion;
  - a regra "nenhum módulo depende de `health`" (ADR-0018 §1) passa a ter **uma exceção: `insights`**.

### 2. Gatilho: `health.snapshot-updated` v1

- O Health grava **`health.snapshot-updated` v1** na outbox **na mesma transação** de cada recálculo do snapshot (job e
  rebuild), como adendo ao ADR-0018. Payload: só `algorithmVersion`. Como todo `analytics.projection-updated` e
  `account.changed` termina num recálculo do Health, o evento cobre transações, categorização, importação, contas e o
  job diário do Health (00:05), que também carrega a virada do período avaliado.
- **Fila `shf.insights.generation`** (binding `health.snapshot-updated`), consumidor `insights.generation` no padrão do
  ADR-0014 §4: inbox `processed_events` na mesma transação do efeito, retry com backoff exponencial + jitter só para
  falhas transitórias, DLQ `shf.insights.generation.dlq`.
- **Nova porta `HealthSnapshotReader`** (`find`, `findMonths`, `state`), que o `HealthSnapshotStore` estende: os
  Insights leem o snapshot sem enxergar os métodos de escrita do Health.

### 3. Debounce e job (padrão do ADR-0018 §3)

- O consumidor **não gera**: marca o Workspace em `insights_workspaces` (`dirty_since`, `due_at = min(agora + 10 s,
  dirty_since + 60 s)`).
- `InsightsJob` (a cada 5 s) pega os vencidos com `FOR UPDATE SKIP LOCKED` e gera cada Workspace numa transação; uma
  falha desfaz e adia só aquele Workspace (`failure-backoff` 60 s). A gravação só limpa a marca se ela não mudou desde
  o início.
- Parâmetros `shf.insights.debounce.quiet` (10 s), `.max-wait` (60 s), `shf.insights.job.interval-ms` (5000),
  `.batch-size` (50), `.failure-backoff` (60 s).
- Freshness = atraso do Analytics + até 65 s do Health + até 65 s dos Insights.

### 4. Geração = detectar + reconciliar

1. **Entrada** (só portas): uma leitura de `AnalyticsReadModel.entries` cobrindo de P − 7 meses até hoje,
   `firstActivity`, `categoryNames`, `largestExpenses(P, 10)` e os snapshots do Health de P − 6 a P na versão atual.
2. **Detecção** (`InsightEngine`, puro): candidatos com fingerprint, textos, evidências, ações e impacto (ADR-0021 §2).
3. **Pontuação** (`InsightScoring`, puro): Impact, Relevance (sobre os insights já guardados do Workspace),
   Confidence, importância e prioridade (ADR-0021 §3).
4. **Reconciliação** (`InsightReconciler`, puro) contra todos os insights guardados do Workspace:
   - candidato novo → `ACTIVE`; fingerprint já `DISMISSED` em qualquer versão → não é criado (§5 do ADR-0021);
   - candidato existente `ACTIVE` ou `RESOLVED` na versão atual → atualizado (`ACTIVE`); `revision + 1` e `updatedAt`
     **só se algo mudou** (gerar duas vezes o mesmo estado não muda nada); de Informativo para Importante limpa
     `seenAt`;
   - `ACTIVE` não detectado: período diferente de P → `EXPIRED` (`PERIOD_ENDED`); do mesmo P (ou D3/D9, sem
     período) → `RESOLVED`; D3/D9 `ACTIVE` sem atualização há 90 dias → `EXPIRED` (`STALE`);
   - `ACTIVE` de outra versão → `EXPIRED` (`VERSION_SUPERSEDED`);
   - `DISMISSED`, `EXPIRED` e linhas de outras versões nunca são reescritos.
5. **Gravação**: upsert por (Workspace, fingerprint, versão); `insights_workspaces` guarda `generated_at`, `as_of`
   (hoje no fuso de negócio) e `algorithm_version` da última geração.

### 5. Persistência (`V15__create_insights.sql`)

- `insights_workspaces`: marca com debounce e lock por Workspace (como `health_workspaces`), mais `generated_at`,
  `as_of` e `algorithm_version`.
- `insights`: uma linha por (Workspace, fingerprint, versão), com colunas para filtrar e ordenar (detector, tipo,
  período, status e motivo, importância, prioridade, impacto em `NUMERIC(19,4)` e em pontos, relevância, confiança,
  `seen_at`, `dismissed_at`, `feedback`/`feedback_at`, `revision`, datas) e `document` JSONB com título, apoio,
  recomendação, evidências, ações e referências (categoria, comerciante, fonte, transação, dimensão). Dinheiro e
  razões no JSONB como **strings decimais**.
- Os textos ficam gravados (o histórico mostra o que o usuário viu); fingerprint é SHA-256 hex.

### 6. Retenção

- Job diário (`shf.insights.purge.cron`, 00:20 do fuso de negócio) apaga insights **não visíveis** (`DISMISSED`,
  `RESOLVED`, `EXPIRED`) com `updated_at` há mais de **13 meses**. Cobre a janela de 90 dias da relevância e um ano de
  auditoria (05.12: derivados apagáveis).

### 7. Rebuild

- `RebuildInsights` e `InsightsRebuildRunner` na inicialização: `shf.insights.rebuild` = `missing` (padrão:
  Workspaces com transações cuja última geração não é da versão atual), `all` (runbook) ou `off`.
- O rebuild **marca** os Workspaces para o job (devido agora) em vez de gerar na inicialização: não depende da ordem
  dos runners e, se o Health ainda estiver reconstruindo, o `health.snapshot-updated` dele gera de novo.
- O rebuild **reconcilia**, nunca apaga: dispensado, visto e feedback sobrevivem. Runbook
  `docs/runbooks/insights-rebuild.md`.

### 8. Freshness e observabilidade

- `GET /api/v1/workspaces/{workspaceId}/insights/status` → `{state, generatedAt, healthComputedAt}`:
  - `NOT_BUILT`: há transações e nenhuma geração na versão atual;
  - `UPDATING`: Workspace marcado, eventos dos Insights pendentes, Health em recálculo (marcado, eventos pendentes ou
    snapshot de outro dia), Analytics `UPDATING`, ou última geração de um dia anterior (que também marca o Workspace);
  - `UP_TO_DATE`: os demais casos.
- Métricas: `shf.insights.generation.freshness` (gauge, segundos desde o `dirty_since` mais antigo), timer
  `shf.insights.generation` (tag `result`), contadores `shf.insights.marked` e `shf.insights.detected` (tags
  `detector` e `importance`). Logs só com ids, contagens e estados; nunca valor, texto, categoria ou comerciante.

### 9. Testes

- Domínio: tabela por detector (disparo, limiares exatos, piso de impacto, mês em andamento, histórico curto, sem
  categoria), pontuação, arredondamento dos textos, reconciliação (novo, atualização idempotente, resolvido, expirado,
  dispensado não volta, troca de versão).
- **Paridade:** os insights gravados são os do `InsightGenerator` rodado direto sobre as portas (não há SQL novo com
  semântica financeira).
- Consumidor: idempotência (reentrega), debounce, DLQ, `SKIP LOCKED`, logs sem dado financeiro; status e isolamento.

## Alternatives

- **Fan-out paralelo** (`analytics.projection-updated`, `account.changed` e `health.snapshot-updated`): D1–D6 ~1 min
  antes, mas duas gerações por mudança e uma janela com D7–D9 sobre o snapshot velho.
- **Sob demanda na leitura:** sem fila, mas escreve no GET (reconciliação e estado) e pesa no p95; contraria o
  "Insight Worker" do 05.13.
- **Diária + incremental:** mais peças, e continua dependendo do Health para D7–D9.
- **Gerar na inicialização** (como o Health): exigiria ordenar os runners depois do rebuild do Health.
- **Sem expurgo no v1:** a tabela cresceria sem limite.

## Consequences

- Analytics, Health e Insights ficam consistentes entre si: os Insights sempre leem um snapshot que já reflete a
  projeção que leram.
- Se o Health parar (DLQ, falha), os Insights param junto; o status mostra `UPDATING` e o runbook cobre o replay.
- Uma geração por recálculo do Health, mesmo quando nada muda: a reconciliação idempotente não grava nada novo.

## Security / Privacy

- Leituras e escritas sempre por Workspace; o status autoriza o membro antes de ler (ADR-0003).
- Payload do evento sem dado financeiro; logs, métricas e traces só com ids, contagens, estados e códigos de detector.
- Estado do usuário (dispensa, feedback) nunca é apagado pelo rebuild; só pela retenção.

## Financial Integrity

- Nenhuma semântica financeira nova: entradas pelas portas de Analytics e Health; detectores puros, `BigDecimal` em
  tudo, versão `insights-v1` em todo insight.

## Operational Impact

- Nova fila e DLQ (`shf.insights.generation[.dlq]`), migration `V15`, jobs (5 s e diário), runner e runbook.
- Freshness esperada ≤ 5 min, como a do Health (05.9).

## Related Specs

- `specs/05-system-design/05.7-event-async.md`, `05.9-observability-operations.md`, `05.12`, `05.13` (§3, §9, §14)
- `docs/adr/ADR-0014` (§4), `ADR-0018` (§1–§7 e adendo), `ADR-0021`

## Date

2026-10-16
