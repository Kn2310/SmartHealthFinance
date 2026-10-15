# Runbook — Comerciantes: renormalização para uma nova versão do normalizador

Referência: ADR-0013 §1 (adendo de 2026-10-14: normalizador v2), ADR-0014 §7 (rebuild da projeção).

O comerciante (`merchant_key`, `merchant_name`, `merchant_normalizer_version`) é um atributo **derivado** da descrição.
Renormalizar nunca altera valor, data, conta, status ou categoria.

## O que acontece no deploy

1. `MerchantRenormalizationRunner` roda antes dos demais runners (`shf.transactions.merchant-renormalize`, variável
   `MERCHANT_RENORMALIZE`):
   - `outdated` (padrão): Workspaces com transações cujo comerciante veio de outra versão
     (`merchant_normalizer_version <> MerchantNormalizer.VERSION`);
   - `off`: não faz nada.
2. Para cada Workspace, numa transação de banco:
   - recalcula o comerciante a partir da **descrição atual** (se o usuário editou a descrição, a chave nova reflete o
     texto editado);
   - copia a regra aprendida da chave antiga para a nova (se as duas têm regra, vence a de `updated_at` mais recente)
     e apaga a antiga quando nenhuma transação a usa mais;
   - grava `transaction.categorized` na outbox para cada transação alterada.
3. `AnalyticsRebuildRunner` (modo `missing`) reconstrói a projeção, porque `RebuildAnalyticsProjection.VERSION`
   mudou junto com o normalizador. Os eventos do passo 2 chegam depois e são coalescidos.

Idempotente: rodar de novo não encontra nada desatualizado.

## Sinais

- Log `Merchant renormalization finished version=... workspaces=... transactions=... keysChanged=...` (só contagens;
  nunca descrição, comerciante ou valor).
- Log `Merchant renormalization failed error=...`: a API sobe mesmo assim; as transações ficam na versão anterior até
  a próxima inicialização.
- Conferência no banco:
  ```sql
  select merchant_normalizer_version, count(*) from transactions
  where merchant_key is not null group by 1;
  ```

## Falha ou interrupção

1. Corrigir a causa (o log tem só o tipo da exceção; o stack trace fica no log de erro da aplicação).
2. Reiniciar a aplicação com `MERCHANT_RENORMALIZE=outdated`: cada Workspace é uma transação, então os já
   concluídos não são refeitos.
3. Se a projeção ficou com chaves antigas (renormalização concluída depois do rebuild), os eventos
   `transaction.categorized` a corrigem. Para forçar: rebuild completo (`ANALYTICS_REBUILD=all`, runbook
   `analytics-rebuild.md`).

## Nova versão do normalizador

Mudou uma regra do `MerchantNormalizer` → nova `VERSION` (ADR-0013 §1) e `RebuildAnalyticsProjection.VERSION + 1`.
O deploy seguinte renormaliza e reconstrói sozinho.
