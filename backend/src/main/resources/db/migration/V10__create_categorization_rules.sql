-- Categorização automática e comerciantes (ADR-0013). Somente aditiva.

-- Comerciante normalizado: derivado da descrição por um algoritmo versionado (MerchantNormalizer).
-- Nulo = não identificado (ou transação anterior a esta migration, preenchida pelo comando de categorização
-- retroativa). A chave (minúsculas, sem acento) agrupa as análises; o nome é só de exibição.
-- Origem da categoria: MANUAL (usuário, nunca sobrescrita), RULE_LEARNED (regra aprendida do Workspace) ou
-- RULE_SYSTEM (palavras-chave do sistema, com a versão do conjunto). Nula = ninguém tratou ainda.
-- MANUAL sem categoria = o usuário escolheu "Sem categoria" e a transação não volta a ser categorizada por regra.
alter table transactions
  add column merchant_key                varchar(120),
  add column merchant_name               varchar(120),
  add column merchant_normalizer_version varchar(20),
  add column category_source             varchar(20),
  add column category_rule_version       varchar(20),
  add constraint ck_transactions_merchant check (
    (merchant_key is null) = (merchant_name is null)
    and (merchant_key is null) = (merchant_normalizer_version is null)
    and (merchant_key is null or btrim(merchant_key) <> '')),
  add constraint ck_transactions_category_source check (
    category_source is null or category_source in ('MANUAL', 'RULE_SYSTEM', 'RULE_LEARNED')),
  add constraint ck_transactions_rule_version_present check (
    category_source is distinct from 'RULE_SYSTEM' or category_rule_version is not null),
  add constraint ck_transactions_rule_version_only_system check (
    category_rule_version is null or category_source = 'RULE_SYSTEM');

-- As categorias já escolhidas (parte A) foram escolhas do usuário.
update transactions set category_source = 'MANUAL' where category_code is not null;

alter table transactions
  add constraint ck_transactions_category_has_source check (category_code is null or category_source is not null);

-- Agregações por comerciante (M5) e "aplicar a transações semelhantes".
create index ix_transactions_workspace_merchant on transactions (workspace_id, merchant_key, occurred_on desc)
  where merchant_key is not null;

-- Regras aprendidas das correções do usuário (ADR-0013 §2): comerciante → categoria, por Workspace e natureza.
create table category_rules (
  workspace_id     uuid         not null,
  merchant_key     varchar(120) not null,
  category_code    varchar(40)  not null,
  category_nature  varchar(10)  not null,
  created_at       timestamptz  not null,
  updated_at       timestamptz  not null,
  constraint pk_category_rules primary key (workspace_id, merchant_key, category_nature),
  constraint fk_category_rules_workspace foreign key (workspace_id) references workspaces (id),
  constraint fk_category_rules_category foreign key (category_code, category_nature)
    references categories (code, nature),
  constraint ck_category_rules_key check (btrim(merchant_key) <> '')
);
