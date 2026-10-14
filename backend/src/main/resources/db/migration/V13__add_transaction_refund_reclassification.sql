-- "Marcar como reembolso" (adendo de 2026-10-14 aos ADR-0005 §6/§8 e ADR-0009 §9): a receita importada é estornada
-- e substituída por um REFUND. O vínculo fica nos dois lados para a auditoria e a exibição; o banco garante que cada
-- receita tem no máximo um substituto e que os vínculos nunca cruzam Workspaces.
alter table transactions
  add column reclassified_from_transaction_id uuid,
  add column reclassified_as_transaction_id   uuid,
  add constraint fk_transactions_reclassified_from foreign key (reclassified_from_transaction_id, workspace_id)
    references transactions (id, workspace_id),
  add constraint fk_transactions_reclassified_as foreign key (reclassified_as_transaction_id, workspace_id)
    references transactions (id, workspace_id),
  add constraint ck_transactions_reclassified_from check (reclassified_from_transaction_id is null or type = 'REFUND'),
  add constraint ck_transactions_reclassified_as check (
    reclassified_as_transaction_id is null or (type = 'INCOME' and status = 'REVERSED'));

create unique index uk_transactions_reclassified_from on transactions (reclassified_from_transaction_id)
  where reclassified_from_transaction_id is not null;

create unique index uk_transactions_reclassified_as on transactions (reclassified_as_transaction_id)
  where reclassified_as_transaction_id is not null;
