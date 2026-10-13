-- Categorias de transação (ADR-0012): catálogo do sistema, sem hierarquia, igual para todos os Workspaces.
-- Categoria nunca é apagada: sai do catálogo com active = false e continua visível nas transações antigas.

create table categories (
  code           varchar(40)  primary key,
  nature         varchar(10)  not null,
  name           varchar(60)  not null,
  display_order  smallint     not null,
  active         boolean      not null default true,
-- Alvo da FK composta de transactions: a natureza viaja com o código.
  constraint uk_categories_code_nature unique (code, nature),
  constraint uk_categories_display_order unique (display_order),
  constraint ck_categories_nature check (nature in ('EXPENSE', 'INCOME')),
  constraint ck_categories_code_format check (code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
  constraint ck_categories_name_not_blank check (btrim(name) <> '')
);

insert into categories (code, nature, name, display_order) values
  ('HOUSING',           'EXPENSE', 'Moradia',              10),
  ('HOME',              'EXPENSE', 'Casa e manutenção',    20),
  ('GROCERIES',         'EXPENSE', 'Mercado',              30),
  ('DINING_OUT',        'EXPENSE', 'Alimentação fora',     40),
  ('TRANSPORT',         'EXPENSE', 'Transporte',           50),
  ('HEALTH',            'EXPENSE', 'Saúde',                60),
  ('FITNESS',           'EXPENSE', 'Bem-estar e esportes', 70),
  ('PERSONAL_CARE',     'EXPENSE', 'Cuidados pessoais',    80),
  ('EDUCATION',         'EXPENSE', 'Educação',             90),
  ('CHILDREN',          'EXPENSE', 'Filhos',              100),
  ('PETS',              'EXPENSE', 'Pets',                110),
  ('LEISURE',           'EXPENSE', 'Lazer',               120),
  ('SUBSCRIPTIONS',     'EXPENSE', 'Assinaturas',         130),
  ('SHOPPING',          'EXPENSE', 'Compras',             140),
  ('CLOTHING',          'EXPENSE', 'Vestuário',           150),
  ('TRAVEL',            'EXPENSE', 'Viagem',              160),
  ('INSURANCE',         'EXPENSE', 'Seguros',             170),
  ('GIFTS_DONATIONS',   'EXPENSE', 'Presentes e doações', 180),
  ('BANK_FEES',         'EXPENSE', 'Tarifas bancárias',   190),
  ('SERVICES',          'EXPENSE', 'Serviços',            200),
  ('TAXES',             'EXPENSE', 'Impostos e taxas',    210),
  ('OTHER_EXPENSE',     'EXPENSE', 'Outros',              220),
  ('SALARY',            'INCOME',  'Salário',             310),
  ('SELF_EMPLOYED',     'INCOME',  'Trabalho autônomo',   320),
  ('INVESTMENT_INCOME', 'INCOME',  'Rendimentos',         330),
  ('RENTAL_INCOME',     'INCOME',  'Aluguel recebido',    340),
  ('BENEFITS',          'INCOME',  'Benefícios',          350),
  ('OTHER_INCOME',      'INCOME',  'Outras receitas',     360);

-- Nulo = "Sem categoria" (ADR-0012 §4): nenhuma transação existente é alterada.
alter table transactions
  add column category_code   varchar(40),
  add column category_nature varchar(10),
  add constraint fk_transactions_category foreign key (category_code, category_nature)
    references categories (code, nature),
  add constraint ck_transactions_category_pair check ((category_code is null) = (category_nature is null)),
-- Coerência tipo × natureza também no banco: receita só com categoria de receita, despesa e reembolso só com
-- categoria de despesa, transferência e ajuste nunca têm categoria (ADR-0012 §2).
  add constraint ck_transactions_category_nature check (
    category_nature is null
    or (type = 'INCOME' and category_nature = 'INCOME')
    or (type in ('EXPENSE', 'REFUND') and category_nature = 'EXPENSE'));

-- Filtro da listagem por categoria e agregações por categoria do M5.
create index ix_transactions_workspace_category on transactions (workspace_id, category_code, occurred_on desc)
  where category_code is not null;
