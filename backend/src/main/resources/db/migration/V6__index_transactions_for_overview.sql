-- Overview (ADR-0006): saldo, fluxo de caixa e contagens leem só transações POSTED, sempre por Workspace e data.
-- Índice parcial e cobrindo: as agregações viram index-only scans, sem tocar na tabela, e o índice não paga
-- o custo de PENDING/CANCELLED/REVERSED. Somente aditivo.
create index ix_transactions_posted_overview on transactions (workspace_id, occurred_on)
  include (account_id, destination_account_id, type, adjustment_direction, amount)
  where status = 'POSTED';
