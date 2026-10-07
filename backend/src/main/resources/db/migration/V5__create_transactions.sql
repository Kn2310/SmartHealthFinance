-- Transactions: fatos financeiros de um Workspace (specs 05.2/05.4, ADR-0005).
-- Valor sempre positivo + type; dinheiro em NUMERIC(19,4) + currency; data de negócio em DATE.

-- Alvo das FKs compostas: garante no banco que conta e transação pertencem ao mesmo Workspace.
alter table accounts add constraint uk_accounts_id_workspace unique (id, workspace_id);

create table transactions (
  id                        uuid           primary key,
  workspace_id              uuid           not null,
  account_id                uuid           not null,
  destination_account_id    uuid,
  type                      varchar(20)    not null,
  adjustment_direction      varchar(20),
  amount                    numeric(19, 4) not null,
  currency                  varchar(3)     not null,
  occurred_on               date           not null,
  description               varchar(200)   not null,
  status                    varchar(20)    not null,
  source                    varchar(20)    not null,
  refund_of_transaction_id  uuid,
  created_at                timestamptz    not null,
  updated_at                timestamptz    not null,
  version                   bigint         not null default 0,
  constraint uk_transactions_id_workspace unique (id, workspace_id),
-- Sem cascade: fato financeiro nunca some junto com a conta ou o Workspace.
  constraint fk_transactions_workspace foreign key (workspace_id) references workspaces (id),
  constraint fk_transactions_account foreign key (account_id, workspace_id)
    references accounts (id, workspace_id),
  constraint fk_transactions_destination_account foreign key (destination_account_id, workspace_id)
    references accounts (id, workspace_id),
  constraint fk_transactions_refund_of foreign key (refund_of_transaction_id, workspace_id)
    references transactions (id, workspace_id),
  constraint ck_transactions_type check (type in ('INCOME', 'EXPENSE', 'TRANSFER', 'ADJUSTMENT', 'REFUND')),
  constraint ck_transactions_status check (status in ('PENDING', 'POSTED', 'CANCELLED', 'REVERSED')),
-- M2 só tem lançamento manual; IMPORT (M4) amplia este check via nova migration.
  constraint ck_transactions_source check (source in ('MANUAL')),
  constraint ck_transactions_amount_positive check (amount > 0),
-- MVP: moeda = moeda base do Workspace (BRL fixa, ADR-0003).
  constraint ck_transactions_currency check (currency = 'BRL'),
  constraint ck_transactions_description_not_blank check (btrim(description) <> ''),
-- Transferência = uma transação com origem e destino distintos; destino só existe em transferência.
  constraint ck_transactions_transfer_destination check ((type = 'TRANSFER') = (destination_account_id is not null)),
  constraint ck_transactions_transfer_distinct_accounts check (destination_account_id <> account_id),
-- Ajuste carrega a direção (o valor é sempre positivo).
  constraint ck_transactions_adjustment_direction check ((type = 'ADJUSTMENT') = (adjustment_direction is not null)),
  constraint ck_transactions_adjustment_direction_values check (adjustment_direction in ('INCREASE', 'DECREASE')),
  constraint ck_transactions_refund_link check (refund_of_transaction_id is null or type = 'REFUND')
);

-- Listagem: sempre por Workspace, mais recentes primeiro (mesma ordem do ORDER BY da aplicação).
create index ix_transactions_workspace_occurred on transactions (workspace_id, occurred_on desc, created_at desc, id desc);
-- Filtro por conta (origem ou destino) e, no M3, cálculo de saldo por conta.
create index ix_transactions_account on transactions (account_id, workspace_id);
create index ix_transactions_destination_account on transactions (destination_account_id, workspace_id)
  where destination_account_id is not null;
create index ix_transactions_refund_of on transactions (refund_of_transaction_id)
  where refund_of_transaction_id is not null;

-- Idempotency-Key por Workspace + request hash (spec 05.6). O claim é feito antes do insert da transação,
-- na mesma transação de banco: por isso a FK é adiada até o commit.
create table transaction_idempotency_keys (
  workspace_id     uuid         not null,
  idempotency_key  varchar(100) not null,
  request_hash     char(64)     not null,
  transaction_id   uuid         not null,
  created_at       timestamptz  not null,
  constraint pk_transaction_idempotency_keys primary key (workspace_id, idempotency_key),
  constraint fk_transaction_idempotency_keys_transaction foreign key (transaction_id, workspace_id)
    references transactions (id, workspace_id) deferrable initially deferred
);
