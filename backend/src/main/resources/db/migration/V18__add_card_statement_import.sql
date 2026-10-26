-- Importação de faturas de cartão (adendo de 2026-10-23 ao ADR-0009 §19–§30) e início do acompanhamento do cartão
-- (ADR-0025 §15). Conciliação do pagamento importado (ADR-0025 §7, adendo). Nada aqui muda resultados existentes.

-- Início do acompanhamento: mês de fechamento (1º dia) da primeira fatura acompanhada. Cartões existentes: o mais
-- antigo entre a fatura atual na data de criação (fuso padrão de negócio, ADR-0006 §10) e a fatura persistida mais
-- antiga; assim nenhuma parcela existente fica antes dele.
alter table credit_cards add column tracking_start_month date;

update credit_cards c set tracking_start_month = least(
  (select min(i.closing_month) from card_invoices i where i.card_id = c.id and i.workspace_id = c.workspace_id),
  (select case
            when created_on < (month_start + (least(c.closing_day,
                extract(day from (month_start + interval '1 month' - interval '1 day'))::int) - 1))
              then month_start
            else (month_start + interval '1 month')::date
          end
     from (select (c.created_at at time zone 'America/Sao_Paulo')::date as created_on,
                  date_trunc('month', (c.created_at at time zone 'America/Sao_Paulo'))::date as month_start) d));

alter table credit_cards
  alter column tracking_start_month set not null,
  add constraint ck_credit_cards_tracking_start check (extract(day from tracking_start_month) = 1);

-- Destino do batch: uma conta OU um cartão.
alter table import_batches
  alter column account_id drop not null,
  add column card_id uuid,
  add column skipped_lines integer not null default 0,
  add column reconciled_lines integer not null default 0,
-- Dados da fatura só quando o OFX os traz explícitos (CCCLOSING, ADR-0009 §21); o arquivo não é guardado.
  add column statement_due_date date,
  add column statement_minimum_payment numeric(19, 4),
  add constraint ck_import_batches_statement_closing check (
    (statement_due_date is null and statement_minimum_payment is null) or card_id is not null),
  add constraint ck_import_batches_statement_minimum check (
    statement_minimum_payment is null or statement_minimum_payment >= 0),
  add constraint fk_import_batches_card foreign key (card_id, workspace_id) references credit_cards (id, workspace_id),
  add constraint ck_import_batches_destination check ((account_id is null) <> (card_id is null));

alter table import_batches drop constraint ck_import_batches_counts;
alter table import_batches add constraint ck_import_batches_counts check (
  total_lines = valid_lines + invalid_lines + duplicate_lines + skipped_lines
  and valid_lines >= 0 and invalid_lines >= 0 and duplicate_lines >= 0 and skipped_lines >= 0
  and imported_lines >= 0 and reconciled_lines >= 0 and imported_lines + reconciled_lines <= valid_lines);

-- "Este arquivo já foi importado neste cartão".
create index ix_import_batches_card_file on import_batches (card_id, file_sha256) where card_id is not null;

-- Linhas: pagamento ignorado (SKIPPED, com motivo) e parcela conciliada com uma compra existente (RECONCILED).
alter table import_records
  add column skip_reason varchar(30),
  add column installment_number smallint,
  add column installment_count smallint,
  add constraint ck_import_records_skipped check ((status = 'SKIPPED') = (skip_reason is not null)),
  add constraint ck_import_records_installment check (
    (installment_number is null) = (installment_count is null)
    and (installment_count is null or (installment_count between 2 and 48
      and installment_number between 1 and installment_count)));

alter table import_records drop constraint ck_import_records_status;
alter table import_records add constraint ck_import_records_status check (status in
  ('VALID', 'INVALID', 'DUPLICATE', 'SKIPPED', 'IMPORTED', 'RECONCILED'));

alter table import_records drop constraint ck_import_records_imported;
alter table import_records add constraint ck_import_records_imported check (
  (status in ('IMPORTED', 'RECONCILED')) <= (transaction_id is not null));

-- Deduplicação por conta OU por cartão (mesma fórmula v1). A chave de uma parcela conciliada aponta para a compra
-- existente: várias chaves podem apontar para a mesma transação.
alter table imported_transaction_keys drop constraint pk_imported_transaction_keys;
alter table imported_transaction_keys
  alter column account_id drop not null,
  add column card_id uuid,
  add constraint fk_imported_transaction_keys_card foreign key (card_id, workspace_id)
    references credit_cards (id, workspace_id),
  add constraint ck_imported_transaction_keys_destination check ((account_id is null) <> (card_id is null));

create unique index uk_imported_transaction_keys_account
  on imported_transaction_keys (workspace_id, account_id, dedupe_key) where account_id is not null;
create unique index uk_imported_transaction_keys_card
  on imported_transaction_keys (workspace_id, card_id, dedupe_key) where card_id is not null;

-- Parcela confirmada por arquivo (ADR-0009 §26): uma linha de fatura concilia cada parcela no máximo uma vez.
-- Pertence a Cards; a lineage até o arquivo fica no import_record.
create table card_installment_confirmations (
  transaction_id  uuid         not null,
  number          smallint     not null,
  workspace_id    uuid         not null,
  card_id         uuid         not null,
  created_at      timestamptz  not null,
  constraint pk_card_installment_confirmations primary key (transaction_id, number),
  constraint fk_card_installment_confirmations_installment foreign key (transaction_id, number)
    references card_installments (transaction_id, number),
  constraint fk_card_installment_confirmations_transaction foreign key (transaction_id, workspace_id)
    references transactions (id, workspace_id) deferrable initially deferred,
  constraint fk_card_installment_confirmations_card foreign key (card_id, workspace_id)
    references credit_cards (id, workspace_id)
);

create index ix_card_installment_confirmations_card on card_installment_confirmations (workspace_id, card_id);

-- Pagamento de fatura importado na conta (ADR-0025 §7, adendo): a despesa estornada aponta para a transferência
-- (nova ou manual) e a transferência nova aponta de volta. Mesmas colunas e índices únicos do "marcar como
-- reembolso" (V13).
alter table transactions drop constraint ck_transactions_reclassified_from;
alter table transactions add constraint ck_transactions_reclassified_from check (
  reclassified_from_transaction_id is null or type in ('REFUND', 'TRANSFER'));

alter table transactions drop constraint ck_transactions_reclassified_as;
alter table transactions add constraint ck_transactions_reclassified_as check (
  reclassified_as_transaction_id is null or (type in ('INCOME', 'EXPENSE') and status = 'REVERSED'));
