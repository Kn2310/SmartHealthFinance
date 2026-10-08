# Runbook — Importação: DLQ e batches FAILED

Referência: ADR-0009 §14–§16, spec 05.7 (DLQ, replay controlado e auditado).

## Sinais

- Mensagens em `shf.ingestion.import.dlq` (RabbitMQ Management → Queues).
- Batches com `status = 'FAILED'` e `failure_reason = 'PROCESSING_ERROR'`.
- `shf.outbox.pending` / `shf.outbox.oldest.pending.age` subindo: o relay não está publicando (broker fora ou
  sem confirms). Nada se perde: os eventos ficam na outbox até o broker voltar.

## Diagnóstico

1. Logs do worker pelo `importId` (os logs só têm IDs, contagens e códigos — nunca linhas do arquivo):
   `Import failed importId=... reason=...` e `Execution of Rabbit message listener failed`.
2. Motivos:
   - `ACCOUNT_ARCHIVED` / `ACCOUNT_NOT_FOUND`: não vai para a DLQ; o usuário precisa reativar a conta e reenviar
     o arquivo. Não há replay.
   - `PROCESSING_ERROR`: tentativas esgotadas ou erro não retentável. Ver a exceção no log.
   - Mensagem ilegível (`Unreadable import message sent to DLQ`): bug de produtor; não reprocessar.

## Replay (só `PROCESSING_ERROR`, depois de corrigida a causa)

O processamento é tudo-ou-nada e idempotente (inbox + chaves de dedupe), então reprocessar não duplica.

1. Registrar o replay (quem, quando, `importId`, motivo) no canal de incidentes.
2. Voltar o batch para `CONFIRMED`, sem tocar em mais nada:

   ```sql
   update import_batches
   set status = 'CONFIRMED', failure_reason = null, completed_at = null, updated_at = now(), version = version + 1
   where id = :importId and status = 'FAILED' and failure_reason = 'PROCESSING_ERROR';
   ```

3. Mover a mensagem da DLQ de volta para `shf.ingestion.import` (Management → Move messages, ou shovel).
4. Acompanhar `GET /api/v1/workspaces/{workspaceId}/imports/{importId}` até `COMPLETED`.

Nunca editar `import_records`, `imported_transaction_keys` ou `transactions` à mão.
