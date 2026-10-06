-- Accounts: contas financeiras de um Workspace (specs 05.2/05.4, ADR-0004).
-- Sem saldo armazenado: o saldo é derivado das transações (M2/M3).
create table accounts (
  id                 uuid         primary key,
  workspace_id       uuid         not null,
  name               varchar(100) not null,
  type               varchar(20)  not null,
  institution_name   varchar(100),
  currency           varchar(3)   not null,
  included_in_total  boolean      not null default true,
  status             varchar(20)  not null,
  created_at         timestamptz  not null,
  updated_at         timestamptz  not null,
  version            bigint       not null default 0,
-- Sem cascade: dado financeiro nunca some junto com o Workspace por acidente.
  constraint fk_accounts_workspace foreign key (workspace_id) references workspaces (id),
  constraint ck_accounts_name_not_blank check (btrim(name) <> ''),
  constraint ck_accounts_type check (type in ('CHECKING', 'SAVINGS', 'PAYMENT', 'OTHER')),
  constraint ck_accounts_status check (status in ('ACTIVE', 'ARCHIVED')),
-- MVP: moeda da conta = moeda base do Workspace (BRL fixa, ADR-0003).
  constraint ck_accounts_currency check (currency = 'BRL')
);

-- Toda consulta é por Workspace (isolamento), em ordem de criação.
create index ix_accounts_workspace on accounts (workspace_id, created_at, id);