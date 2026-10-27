# Runbook — Insights: DLQ, freshness, regeneração, rebuild e retenção

Referência: ADR-0021 (modelo `insights-v1`), ADR-0028 (`insights-v2`: detectores de Cartões), ADR-0022 (geração
encadeada no Health), spec 05.7 (DLQ, replay controlado e auditado), 05.9 ("insight generation").

Os insights (`insights`, `insights_workspaces`) são **derivados** da projeção de Analytics, do snapshot do Health e
da posição dos cartões (porta `CardPortfolio`), mas guardam **estado do usuário**: visto, dispensado e feedback. Por isso nada aqui apaga e recria: a geração e o
rebuild **reconciliam** com o que já existe. Só a retenção apaga, e só insights não visíveis.

## Como os insights se atualizam

1. O Health recalcula o snapshot (depois de `analytics.projection-updated`, `account.changed`, `card.*`,
   `invoice.closed` ou do job diário das 00:05) e grava `health.snapshot-updated` na outbox, na mesma transação.
2. O consumidor `insights.generation` (fila `shf.insights.generation`) só marca o Workspace em `insights_workspaces`
   (`dirty_since`, `due_at`): debounce de 10 s, teto de 60 s.
3. O job (a cada 5 s) pega os vencidos com `FOR UPDATE SKIP LOCKED`, roda os detectores sobre as portas, pontua e
   reconcilia: candidato novo → `ACTIVE`; o mesmo fingerprint é atualizado (revisão nova só se algo mudou); o que
   deixou de valer → `RESOLVED`; período avaliado que passou (dia 7 do mês seguinte) → `EXPIRED`; dispensado nunca
   volta. Insights de fatura (D11, D14) seguem a fatura: resolvem enquanto ela está em avaliação e expiram depois.
4. Às 00:20 (fuso de `shf.overview.zone`) a retenção apaga insights `DISMISSED`, `RESOLVED` e `EXPIRED` com
   `updated_at` há mais de 13 meses.

## Sinais

- `GET /api/v1/workspaces/{workspaceId}/insights/status` com `state = UPDATING` por mais de alguns minutos, ou
  `NOT_BUILT` num Workspace com transações.
- Mensagens em `shf.insights.generation.dlq` (RabbitMQ Management → Queues).
- Logs `Insights event sent to DLQ messageId=... eventType=... error=...` e `Insights generation failed
  workspaceId=... error=...`.
- Métricas: `shf.insights.generation.freshness` (segundos desde a marca mais antiga; alerta acima de 300),
  `shf.insights.generation` (timer por `result`), `shf.insights.marked` e `shf.insights.detected` (tags `detector`,
  `importance`), e as do Health (`health-rebuild.md`): os insights nunca ficam mais atuais que o snapshot que leem.

## Diagnóstico

1. Logs dos Insights só têm ids, contagens e estados — nunca valor, texto, categoria ou comerciante.
2. Health `RECALCULATING`/`NOT_BUILT` (ou Analytics `UPDATING`) → resolva primeiro pelo `health-rebuild.md` (e
   `analytics-rebuild.md`); os insights se atualizam depois, sozinhos.
3. Mensagem ilegível, de tipo inesperado ou de versão desconhecida vai direto para a DLQ: bug de produtor; não
   reprocessar, corrigir o produtor e rodar o rebuild.
4. Geração que falha é adiada 60 s (`shf.insights.job.failure-backoff`) e tentada de novo; a marca não se perde.
   Falha repetida no mesmo Workspace → abrir incidente com o `workspaceId` do log.

## Replay da DLQ

O consumidor é idempotente (inbox `processed_events`, consumer `insights.generation`) e só marca o Workspace; a
geração relê tudo. Reentregar nunca duplica.

1. Registrar o replay (quem, quando, quantas mensagens, motivo) no canal de incidentes.
2. Mover as mensagens de `shf.insights.generation.dlq` para `shf.insights.generation`.
3. Conferir `GET .../insights/status` = `UP_TO_DATE`.

Se as mensagens foram descartadas, o rebuild abaixo substitui qualquer replay.

## Rebuild

Propriedade `shf.insights.rebuild` (variável `INSIGHTS_REBUILD`), lida na inicialização da API. O rebuild **só marca**
os Workspaces para o job gerar logo depois; não depende da ordem dos rebuilds de Analytics e Health (se o Health ainda
estiver reconstruindo, o `health.snapshot-updated` dele gera de novo).

| Valor | Efeito |
|---|---|
| `missing` (padrão) | Marca os Workspaces com transações cuja última geração não é da versão atual do catálogo. Faz o backfill do primeiro deploy e de cada nova versão (`InsightRules.ALGORITHM_VERSION`). |
| `all` | Marca todos os Workspaces com transações. Usar depois de incidente, replay incompleto ou suspeita de divergência. |
| `off` | Não faz nada. |

Passos para `all`:

1. Registrar o motivo no canal de incidentes.
2. Subir uma instância com `INSIGHTS_REBUILD=all` (as demais podem continuar no ar: a geração de cada Workspace
   bloqueia a linha dele em `insights_workspaces`).
3. Acompanhar `Insights rebuild marked scope=ALL workspaces=...` e depois `shf.insights.generation.freshness` voltar a 0.
4. Voltar a variável para `missing`.

Visto, dispensado e feedback **sobrevivem** ao rebuild.

## Nova versão do catálogo

Mudou detector, limiar, curva, pontuação, regra de importância ou texto → nova `ALGORITHM_VERSION` (ADR-0021 §6) e ADR.
No deploy, o modo `missing` gera a nova versão: os insights `ACTIVE` da versão anterior viram `EXPIRED`
(`VERSION_SUPERSEDED`), os dispensados continuam dispensados (o fingerprint não inclui a versão) e o histórico da
versão anterior fica até a retenção. Desde a `insights-v2` (ADR-0028 §6), o insight novo também **herda o visto e o
feedback** do mesmo fingerprint, então nada volta como "Novo".

### `insights-v2` (Sessão 6.2 parte E)

- Ordem no deploy: rebuild do Analytics (nada muda na projeção) → Health `health-v2` (`health-rebuild.md`) → Insights
  `insights-v2`. O runner dos Insights só marca os Workspaces; a geração vem do job e do `health.snapshot-updated`.
- Migration `V19`: o `check` de `insights.type` aceita `CARDS`.
- Conferir depois do deploy: `select algorithm_version, status, count(*) from insights group by 1, 2` (v1 `EXPIRED`
  com `VERSION_SUPERSEDED`, v2 `ACTIVE`), `seen_at`/`feedback` preservados nos fingerprints herdados e o chip
  Cartões com `available = true`.
- Rebuild nunca apaga dispensa, visto ou feedback.

Nunca editar `insights*` à mão para "corrigir" um insight: corrija a causa e regenere. A única exceção prevista é
apagar dados de um Workspace por pedido de exclusão (05.12), junto com o resto dos dados dele.
