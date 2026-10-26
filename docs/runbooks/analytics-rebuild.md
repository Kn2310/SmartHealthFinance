# Runbook — Analytics: DLQ, freshness e rebuild da projeção

Referência: ADR-0014, spec 05.7 (DLQ, replay controlado e auditado), 05.9 (métricas).

A projeção (`analytics_daily_flows`, `analytics_projections`, `analytics_projection_months`) é **derivada** de
`transactions`. Apagar e reconstruir nunca perde fato financeiro.

## Sinais

- `GET /api/v1/workspaces/{workspaceId}/analytics/status` com `state = UPDATING` por muito tempo, ou
  `pendingEvents` crescendo.
- Mensagens em `shf.analytics.projection.dlq` (RabbitMQ Management → Queues).
- Log `Analytics event sent to DLQ messageId=... eventType=... error=...`.
- Métricas: `shf.analytics.projection.refresh` (timer por `outcome`: `REFRESHED`, `REBUILT`, `DUPLICATE`),
  `shf.analytics.projection.months` (`result`: `recomputed`, `coalesced`), e `shf.outbox.pending` /
  `shf.outbox.oldest.pending.age` (o relay não está publicando).

## Diagnóstico

1. Logs do consumidor só têm IDs, meses e contagens (`Analytics projection refreshed workspaceId=... eventId=...
   recomputed=... coalesced=...`) — nunca valor, descrição, categoria ou comerciante.
2. Mensagem ilegível ou de versão desconhecida vai direto para a DLQ: bug de produtor; não reprocessar, corrigir o
   produtor e rodar o rebuild do Workspace afetado.
3. Falha de banco persistente: tentativas esgotadas (backoff exponencial + jitter) e DLQ. Corrigir a causa antes do
   replay.

## Replay da DLQ

O consumidor é idempotente (inbox `processed_events`, consumer `analytics.projection`) e recalcula a partir da fonte
da verdade, então reentregar não duplica nem soma errado.

1. Registrar o replay (quem, quando, quantas mensagens, motivo) no canal de incidentes.
2. Mover as mensagens de `shf.analytics.projection.dlq` para `shf.analytics.projection`.
3. Conferir `GET .../analytics/status` = `UP_TO_DATE`.

Se as mensagens foram descartadas (ou não dá para confiar nelas), use o rebuild abaixo: ele substitui qualquer
replay.

## Rebuild

Propriedade `shf.analytics.rebuild` (variável `ANALYTICS_REBUILD`), lida na inicialização da API:

| Valor | Efeito |
|---|---|
| `missing` (padrão) | Reconstrói só os Workspaces com transações e sem projeção na versão atual. Faz o backfill do primeiro deploy e de cada nova `RebuildAnalyticsProjection.VERSION`. |
| `all` | Reconstrói todos os Workspaces com transações. Usar depois de incidente, replay incompleto ou suspeita de divergência. |
| `off` | Não faz nada. |

Passos para `all`:

1. Registrar o motivo no canal de incidentes.
2. Subir uma instância com `ANALYTICS_REBUILD=all` (as demais podem continuar no ar: o rebuild de cada Workspace
   bloqueia a linha dele em `analytics_projections`, o mesmo lock do consumidor).
3. Acompanhar o log `Analytics projection rebuild finished scope=ALL workspaces=...`.
4. Voltar a variável para `missing`.

Um Workspace também se reconstrói sozinho no primeiro evento que chega sem projeção na versão atual.

Nunca editar `analytics_*` à mão para "corrigir" números: corrija a causa e reconstrua.

## Cadeia de rebuilds com cartões (Sessão 6.2 parte E)

A integração dos cartões (ADR-0006 §18, ADR-0027, ADR-0028) **não muda** o que a projeção grava:
`RebuildAnalyticsProjection.VERSION` continua 3 (compras de cartão em `expense`, pagamento de fatura só em
`transfers`, cartão arquivado fora na leitura). O deploy dispara, em ordem, só os rebuilds que mudaram de versão:
Health `health-v2` (`health-rebuild.md`) e Insights `insights-v2` (`insights-rebuild.md`). Nenhum deles apaga estado do
usuário (categorias manuais, dispensas, visto, feedback).
