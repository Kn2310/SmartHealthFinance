# Runbook — Forecast: DLQ, freshness, recálculo e rebuild do snapshot

Referência: ADR-0030 (modelo `forecast-v1`), ADR-0031 (snapshot por eventos), spec 05.7 (DLQ, replay controlado e
auditado), 05.9 (freshness do Forecast dentro de 15 min).

O snapshot (`forecast_snapshots`, `forecast_workspaces`) é **derivado** da projeção de Analytics, dos saldos do
Overview, dos cartões (`CardPortfolio`) e das contas. Apagar e reconstruir o snapshot nunca perde fato financeiro.

**Estado do usuário nunca é apagado.** `forecast_recurring_series` (status, origem, valor e dia editados) e
`forecast_month_outcomes` (a projeção congelada na virada do mês e o realizado) **não são deriváveis**: nenhum
recálculo nem rebuild apaga, reescreve o estado do usuário (`status`, `origin`, `user_expected_*`) ou altera uma
projeção congelada. Nunca rodar `delete`/`truncate` nessas duas tabelas.

## Como o snapshot se atualiza

1. Um fato muda → o Analytics recalcula a projeção e grava `analytics.projection-updated`. Conta (`account.changed`),
   cartão (`card.created/updated/archived/reactivated`) e fatura fechada (`invoice.closed`) também gravam eventos.
   Os mesmos sete eventos estão ligados à fila do Health e à do Forecast, que rodam em paralelo.
2. O consumidor `forecast.snapshot` (fila `shf.forecast.snapshot`) só marca o Workspace em `forecast_workspaces`
   (`dirty_since`, `due_at`): debounce de 10 s, teto de 60 s desde o primeiro evento pendente.
3. O job (a cada 5 s) pega os vencidos com `FOR UPDATE SKIP LOCKED` e, numa transação por Workspace, redetecta as
   recorrências, calcula as projeções, congela e reconcilia o previsto × realizado, grava o snapshot e o evento
   `forecast.snapshot-updated` (gatilho dos Insights, ao lado do `health.snapshot-updated`).
4. Às 00:07 (fuso de `shf.overview.zone`, `shf.forecast.daily.cron`) o job diário marca todos os Workspaces com
   snapshot: o calendário muda o resultado sem evento. O `invoice.closed` das 00:10 marca de novo; o segundo recálculo
   é esperado. Uma leitura de status com snapshot de um dia anterior também marca.
5. Ações do usuário em recorrências (confirmar, ignorar, editar) marcam o Workspace com `due_at = agora`; o snapshot
   fica pronto no próximo ciclo do job.

## Sinais

- `GET /api/v1/workspaces/{workspaceId}/forecast/status` com `state = RECALCULATING` por mais de alguns minutos, ou
  `NOT_BUILT` num Workspace com transações.
- Mensagens em `shf.forecast.snapshot.dlq` (RabbitMQ Management → Queues).
- Logs `Forecast event sent to DLQ messageId=... eventType=... error=...`, `Forecast snapshot failed workspaceId=...
  error=...` e `Forecast daily refresh failed`.
- Métricas: `shf.forecast.snapshot.freshness` (segundos desde a marca mais antiga; alerta acima de 600),
  `shf.forecast.snapshot.compute` (timer por `result`), `shf.forecast.snapshot.marked`,
  `shf.forecast.recurrence.action` (por `action`) e `shf.forecast.month.error` (erro percentual do mês liquidado), além
  das do Analytics (`analytics-rebuild.md`): o Forecast nunca fica mais atual que a projeção que lê.

## Diagnóstico

1. Logs do Forecast só têm ids, contagens, meses e estados — nunca valor, comerciante, descrição ou chave de série.
2. Analytics `UPDATING`/`NOT_BUILT` → resolva primeiro pelo `analytics-rebuild.md`; o Forecast se atualiza depois.
3. Mensagem ilegível, de tipo inesperado ou de versão desconhecida vai direto para a DLQ: bug de produtor; não
   reprocessar, corrigir o produtor e rodar o rebuild.
4. Recálculo que falha é adiado 60 s (`shf.forecast.job.failure-backoff`) e tentado de novo; a marca não se perde.
   Falha repetida no mesmo Workspace → abrir incidente com o `workspaceId` do log.

## Replay da DLQ

O consumidor é idempotente (inbox `processed_events`, consumer `forecast.snapshot`) e só marca o Workspace; o
recálculo relê tudo. Reentregar nunca duplica.

1. Registrar o replay (quem, quando, quantas mensagens, motivo) no canal de incidentes.
2. Mover as mensagens de `shf.forecast.snapshot.dlq` para `shf.forecast.snapshot`.
3. Rodar o rebuild (abaixo, `all`) se houve perda de mensagens ou dúvida; senão conferir `GET .../forecast/status` =
   `UP_TO_DATE`.

Se as mensagens foram descartadas, o rebuild substitui qualquer replay.

## Rebuild

Propriedade `shf.forecast.rebuild` (variável `FORECAST_REBUILD`), lida na inicialização da API, **depois** do rebuild
do Analytics:

| Valor | Efeito |
|---|---|
| `missing` (padrão) | Reconstrói os Workspaces com transações e sem snapshot na versão atual (`ForecastCalculator.ALGORITHM_VERSION`). Faz o backfill do primeiro deploy e de cada nova versão. |
| `all` | Reconstrói todos os Workspaces com transações. Usar depois de incidente, replay incompleto ou suspeita de divergência. |
| `off` | Não faz nada. |

Passos para `all`:

1. Registrar o motivo no canal de incidentes.
2. Subir uma instância com `FORECAST_REBUILD=all` (as demais podem continuar no ar: o rebuild de cada Workspace
   bloqueia a linha dele em `forecast_workspaces`, o mesmo lock do job e das ações do usuário).
3. Acompanhar o log `Forecast snapshot rebuild finished scope=ALL workspaces=...`.
4. Voltar a variável para `missing`.

O rebuild reescreve só as colunas **detectadas** das séries e o snapshot; séries, estado do usuário e previsto ×
realizado permanecem. Uma falha no rebuild não derruba a API: o Workspace é marcado no próximo evento ou no job diário.
Para forçar só o recálculo (sem reiniciar), basta qualquer evento do Workspace ou esperar o job diário.

## Nova versão do algoritmo

Mudou regra, limiar, entrada ou arredondamento → nova `ALGORITHM_VERSION` (ADR-0030) e ADR. No deploy, o modo
`missing` grava as linhas da nova versão; as da versão anterior **ficam** em `forecast_snapshots` (e em
`forecast_month_outcomes`) e só podem ser apagadas com decisão registrada. Em seguida o Forecast grava
`forecast.snapshot-updated` e os Insights regeneram. O runner do Forecast roda depois do rebuild do Analytics.

Nunca editar `forecast_*` à mão para "corrigir" uma projeção: corrija a causa e reconstrua.
