# Runbook — Health: DLQ, freshness, recálculo e rebuild do snapshot

Referência: ADR-0017 (modelo `health-v1`), ADR-0018 (snapshot por eventos), spec 05.7 (DLQ, replay controlado e
auditado), 05.9 (Health freshness ≤ 5 min).

O snapshot (`health_snapshots`, `health_workspaces`) é **derivado** da projeção de Analytics e dos saldos do
Overview. Apagar e reconstruir nunca perde fato financeiro.

## Como o snapshot se atualiza

1. Um fato muda (`transaction.*`, `import.completed`) → o Analytics recalcula a projeção e grava
   `analytics.projection-updated` na outbox. Editar, criar, arquivar ou reativar conta grava `account.changed`.
2. O consumidor `health.snapshot` (fila `shf.health.snapshot`) só marca o Workspace em `health_workspaces`
   (`dirty_since`, `due_at`): debounce de 10 s, teto de 60 s desde o primeiro evento pendente.
3. O job (a cada 5 s) pega os vencidos com `FOR UPDATE SKIP LOCKED` e recalcula o mês corrente + os 12 fechados na
   versão atual do algoritmo, e grava `health.snapshot-updated` na mesma transação: é o gatilho dos Insights
   (`insights-rebuild.md`, ADR-0022).
4. Às 00:05 (fuso de `shf.overview.zone`) o job diário marca todos os Workspaces com snapshot: o calendário muda o
   resultado sem nenhum evento. Uma leitura de status com snapshot de um dia anterior também marca.

## Sinais

- `GET /api/v1/workspaces/{workspaceId}/health/status` com `state = RECALCULATING` por mais de alguns minutos, ou
  `NOT_BUILT` num Workspace com transações.
- Mensagens em `shf.health.snapshot.dlq` (RabbitMQ Management → Queues).
- Logs `Health event sent to DLQ messageId=... eventType=... error=...` e `Health snapshot failed workspaceId=...
  error=...`.
- Métricas: `shf.health.snapshot.freshness` (segundos desde a marca mais antiga; alerta acima de 300),
  `shf.health.snapshot.compute` (timer por `result`), `shf.health.snapshot.marked` (contador) e as do Analytics
  (`analytics-rebuild.md`): o Health nunca fica mais atual que a projeção que lê.

## Diagnóstico

1. Logs do Health só têm ids e contagens — nunca score, valor, categoria ou texto de explicação.
2. Analytics `UPDATING`/`NOT_BUILT` → resolva primeiro pelo `analytics-rebuild.md`; o Health se atualiza depois.
3. Mensagem ilegível, de tipo inesperado ou de versão desconhecida vai direto para a DLQ: bug de produtor; não
   reprocessar, corrigir o produtor e rodar o rebuild.
4. Recálculo que falha (ex.: dado inesperado) é adiado 60 s (`shf.health.job.failure-backoff`) e tentado de novo;
   a marca não se perde. Falha repetida no mesmo Workspace → abrir incidente com o `workspaceId` do log.

## Replay da DLQ

O consumidor é idempotente (inbox `processed_events`, consumer `health.snapshot`) e só marca o Workspace; o
recálculo relê tudo. Reentregar nunca duplica.

1. Registrar o replay (quem, quando, quantas mensagens, motivo) no canal de incidentes.
2. Mover as mensagens de `shf.health.snapshot.dlq` para `shf.health.snapshot`.
3. Conferir `GET .../health/status` = `UP_TO_DATE`.

Se as mensagens foram descartadas, o rebuild abaixo substitui qualquer replay.

## Rebuild

Propriedade `shf.health.rebuild` (variável `HEALTH_REBUILD`), lida na inicialização da API, **depois** do rebuild
do Analytics:

| Valor | Efeito |
|---|---|
| `missing` (padrão) | Reconstrói os Workspaces com transações e sem snapshot na versão atual do algoritmo. Faz o backfill do primeiro deploy e de cada nova versão (`HealthCalculator.ALGORITHM_VERSION`). |
| `all` | Reconstrói todos os Workspaces com transações. Usar depois de incidente, replay incompleto ou suspeita de divergência. |
| `off` | Não faz nada. |

Passos para `all`:

1. Registrar o motivo no canal de incidentes.
2. Subir uma instância com `HEALTH_REBUILD=all` (as demais podem continuar no ar: o rebuild de cada Workspace
   bloqueia a linha dele em `health_workspaces`, o mesmo lock do job).
3. Acompanhar o log `Health snapshot rebuild finished scope=ALL workspaces=...`.
4. Voltar a variável para `missing`.

Para forçar só o recálculo (sem reiniciar), basta qualquer evento do Workspace ou esperar o job diário.

## Nova versão do algoritmo

Mudou curva, peso, limiar, entrada ou regra de status → nova `ALGORITHM_VERSION` (ADR-0017 §5) e ADR. No deploy, o
modo `missing` grava as linhas da nova versão; as da versão anterior **ficam** em `health_snapshots` e só podem ser
apagadas com decisão registrada.

Nunca editar `health_*` à mão para "corrigir" um score: corrija a causa e reconstrua.
