-- Importação de extratos CSV/OFX (specs 05.4/05.6, ADR-0009).

-- Transações importadas (ADR-0005 §12 previa a ampliação deste check).
alter table transactions drop constraint ck_transactions_source;
alter table transactions add constraint ck_transactions_source check (source in ('MANUAL', 'IMPORT'));

-- Um arquivo enviado. O arquivo bruto nunca é guardado: só o SHA-256 e metadados (ADR-0009 §3).
create table import_batches (
  id                uuid          primary key,
  workspace_id      uuid          not null,
  account_id        uuid          not null,
  created_by        uuid          not null,
  format            varchar(10)   not null,
  status            varchar(20)   not null,
  file_name         varchar(255)  not null,
  file_size         bigint        not null,
  file_sha256       char(64)      not null,
  idempotency_key   varchar(100)  not null,
  request_hash      char(64)      not null,
  total_lines       integer       not null,
  valid_lines       integer       not null,
  invalid_lines     integer       not null,
  duplicate_lines   integer       not null,
  imported_lines    integer       not null default 0,
  first_date        date,
  last_date         date,
  failure_reason    varchar(50),
  created_at        timestamptz   not null,
  updated_at        timestamptz   not null,
  confirmed_at      timestamptz,
  completed_at      timestamptz,
  version           bigint        not null default 0,
  constraint uk_import_batches_id_workspace unique (id, workspace_id),
  -- Idempotency-Key por Workspace (ADR-0009 §13): o claim é o próprio insert do batch.
  constraint uk_import_batches_idempotency unique (workspace_id, idempotency_key),
  constraint fk_import_batches_workspace foreign key (workspace_id) references workspaces (id),
  constraint fk_import_batches_account foreign key (account_id, workspace_id) references accounts (id, workspace_id),
  constraint fk_import_batches_created_by foreign key (created_by) references users (id),
  constraint ck_import_batches_format check (format in ('CSV', 'OFX')),
  constraint ck_import_batches_status check (status in
    ('PREVIEW', 'CONFIRMED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED')),
  constraint ck_import_batches_file_size check (file_size > 0),
  constraint ck_import_batches_counts check (
    total_lines = valid_lines + invalid_lines + duplicate_lines
    and valid_lines >= 0 and invalid_lines >= 0 and duplicate_lines >= 0
    and imported_lines between 0 and valid_lines),
  constraint ck_import_batches_period check (first_date is null or first_date <= last_date),
  constraint ck_import_batches_failure check ((status = 'FAILED') = (failure_reason is not null))
);

-- Histórico do Workspace, mais recentes primeiro.
create index ix_import_batches_workspace_created on import_batches (workspace_id, created_at desc, id desc);
-- Expiração de previews não confirmados (ADR-0009 §16).
create index ix_import_batches_preview on import_batches (created_at) where status = 'PREVIEW';
-- "Este arquivo já foi importado nesta conta".
create index ix_import_batches_account_file on import_batches (account_id, file_sha256);

-- Uma linha do arquivo, válida ou não: lineage da transação até o arquivo de origem (05.4).
create table import_records (
  batch_id        uuid           not null,
  workspace_id    uuid           not null,
  line_number     integer        not null,
  status          varchar(20)    not null,
  occurred_on     date,
  amount          numeric(19, 4),               -- com o sinal do extrato
  currency        varchar(3),
  description     varchar(200),
  external_id     varchar(255),
  dedupe_key      char(64),
  issue_field     varchar(30),
  issue_code      varchar(50),
  transaction_id  uuid,
  constraint pk_import_records primary key (batch_id, line_number),
  constraint fk_import_records_batch foreign key (batch_id, workspace_id)
    references import_batches (id, workspace_id),
  constraint fk_import_records_transaction foreign key (transaction_id, workspace_id)
    references transactions (id, workspace_id),
  constraint ck_import_records_status check (status in ('VALID', 'INVALID', 'DUPLICATE', 'IMPORTED')),
  -- Linha recusada guarda só o código; as demais guardam o lançamento completo.
  constraint ck_import_records_invalid check ((status = 'INVALID') = (issue_code is not null)),
  constraint ck_import_records_line check (status = 'INVALID' or (occurred_on is not null and amount is not null
    and amount <> 0 and currency is not null and description is not null and dedupe_key is not null)),
  constraint ck_import_records_imported check ((status = 'IMPORTED') <= (transaction_id is not null))
);

-- Deduplicação por conta (ADR-0009 §11). A chave é reservada antes do insert da transação, na mesma transação
-- de banco: por isso a FK para a transação é adiada até o commit (mesmo padrão do ADR-0005).
create table imported_transaction_keys (
  workspace_id    uuid         not null,
  account_id      uuid         not null,
  dedupe_key      char(64)     not null,
  transaction_id  uuid         not null,
  batch_id        uuid         not null,
  created_at      timestamptz  not null,
  constraint pk_imported_transaction_keys primary key (workspace_id, account_id, dedupe_key),
  constraint fk_imported_transaction_keys_account foreign key (account_id, workspace_id)
    references accounts (id, workspace_id),
  constraint fk_imported_transaction_keys_transaction foreign key (transaction_id, workspace_id)
    references transactions (id, workspace_id) deferrable initially deferred,
  constraint fk_imported_transaction_keys_batch foreign key (batch_id, workspace_id)
    references import_batches (id, workspace_id)
);
