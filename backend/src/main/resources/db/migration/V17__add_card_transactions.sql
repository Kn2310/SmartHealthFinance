-- Lançamentos de cartão (ADR-0025 §2, ADR-0026 §10): compra e estorno são transações com card_id (sem conta);
-- pagamento de fatura é TRANSFER conta → cartão (destination_card_id). Nada aqui altera linhas existentes: toda
-- transação atual tem account_id e nenhum cartão.

alter table transactions
  alter column account_id drop not null,
  add column card_id uuid,
  add column destination_card_id uuid,
  add constraint fk_transactions_card foreign key (card_id, workspace_id)
    references credit_cards (id, workspace_id),
  add constraint fk_transactions_destination_card foreign key (destination_card_id, workspace_id)
    references credit_cards (id, workspace_id),
-- Exatamente um entre conta e cartão na origem.
  add constraint ck_transactions_account_or_card check ((account_id is null) <> (card_id is null)),
-- No cartão só existem compra (EXPENSE) e estorno (REFUND), nascidos POSTED (podem ser estornados depois).
  add constraint ck_transactions_card_types check (card_id is null or (type in ('EXPENSE', 'REFUND')
    and status in ('POSTED', 'REVERSED'))),
-- Pagamento de fatura: transferência de uma conta para um cartão; destino é conta OU cartão, nunca os dois.
  add constraint ck_transactions_destination_card check (destination_card_id is null
    or (type = 'TRANSFER' and destination_account_id is null and account_id is not null));

-- O destino de uma transferência agora pode ser um cartão.
alter table transactions drop constraint ck_transactions_transfer_destination;
alter table transactions add constraint ck_transactions_transfer_destination
  check ((type = 'TRANSFER') = (destination_account_id is not null or destination_card_id is not null));

-- Filtro por cartão, fatura e pagamentos (origem ou destino).
create index ix_transactions_card on transactions (card_id, workspace_id) where card_id is not null;
create index ix_transactions_destination_card on transactions (destination_card_id, workspace_id)
  where destination_card_id is not null;

-- O consumo das parcelas pelos estornos é calculado na leitura (adendo do ADR-0025 §8): a coluna nunca foi usada.
alter table card_installments drop column cancelled_amount;

-- A parcela pertence a uma compra do mesmo cartão (a FK por transação já garante o Workspace). A compra e as
-- parcelas nascem na mesma transação de banco, mas a compra é gravada pelo JPA (flush no commit) e as parcelas por
-- JDBC: as FKs para transactions são verificadas no commit, como a da Idempotency-Key (V5).
alter table transactions add constraint uk_transactions_id_card_workspace unique (id, card_id, workspace_id);
alter table card_installments drop constraint fk_card_installments_transaction;
alter table card_installments add constraint fk_card_installments_transaction
  foreign key (transaction_id, workspace_id) references transactions (id, workspace_id)
  deferrable initially deferred;
alter table card_installments add constraint fk_card_installments_purchase
  foreign key (transaction_id, card_id, workspace_id) references transactions (id, card_id, workspace_id)
  deferrable initially deferred;

-- Analytics (ADR-0014, ADR-0026 §10): a projeção ganha o cartão no grão. Derivada; o rebuild (versão 3) a refaz.
alter table analytics_daily_flows
  alter column account_id drop not null,
  add column card_id uuid,
  add constraint ck_analytics_daily_flows_account_or_card check ((account_id is null) <> (card_id is null));
