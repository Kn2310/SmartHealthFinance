-- Cartões de crédito (ADR-0025, ADR-0026): cartão, datas das faturas e parcelas. Nenhuma tabela guarda valor de
-- fatura, saldo ou utilização: tudo é derivado das parcelas, créditos e pagamentos.

-- Cartão: só os 4 últimos dígitos. Não existe coluna para número completo, CVV, validade ou senha (ADR-0025 §1).
create table credit_cards (
  id                           uuid          primary key,
  workspace_id                 uuid          not null,
  nickname                     varchar(100)  not null,
  institution_name             varchar(100),
  brand                        varchar(20)   not null,
  last4                        varchar(4)    not null,
  credit_limit                 numeric(19, 4) not null,
  currency                     varchar(3)    not null,
  closing_day                  smallint      not null,
  due_day                      smallint      not null,
  default_payment_account_id   uuid,
  status                       varchar(20)   not null,
  created_at                   timestamptz   not null,
  updated_at                   timestamptz   not null,
  version                      bigint        not null default 0,
  constraint uk_credit_cards_id_workspace unique (id, workspace_id),
-- Sem cascade: dado financeiro nunca some junto com o Workspace ou a conta por acidente.
  constraint fk_credit_cards_workspace foreign key (workspace_id) references workspaces (id),
  constraint fk_credit_cards_default_payment_account foreign key (default_payment_account_id, workspace_id)
    references accounts (id, workspace_id),
  constraint ck_credit_cards_nickname_not_blank check (btrim(nickname) <> ''),
  constraint ck_credit_cards_brand check (brand in ('VISA', 'MASTERCARD', 'ELO', 'AMEX', 'HIPERCARD', 'OTHER')),
  constraint ck_credit_cards_last4 check (last4 ~ '^[0-9]{4}$'),
  constraint ck_credit_cards_limit_positive check (credit_limit > 0),
-- MVP: moeda = moeda base do Workspace (BRL fixa, ADR-0003).
  constraint ck_credit_cards_currency check (currency = 'BRL'),
  constraint ck_credit_cards_closing_day check (closing_day between 1 and 31),
  constraint ck_credit_cards_due_day check (due_day between 1 and 31),
  constraint ck_credit_cards_due_not_closing check (due_day <> closing_day),
-- Ciclo de mesmo mês: fechamento até o dia 27, para o vencimento cair depois do fechamento também em fevereiro.
  constraint ck_credit_cards_same_month_cycle check (due_day < closing_day or closing_day <= 27),
  constraint ck_credit_cards_status check (status in ('ACTIVE', 'ARCHIVED'))
);

-- Toda consulta é por Workspace (isolamento), em ordem de criação.
create index ix_credit_cards_workspace on credit_cards (workspace_id, created_at, id);

-- Datas de uma fatura (ADR-0026 §5). A linha nasce com a primeira parcela atribuída; meses são o 1º dia do mês.
-- Fechada (closing_date ≤ hoje) = congelada; as não fechadas acompanham mudanças do ciclo do cartão.
create table card_invoices (
  id                  uuid          primary key,
  workspace_id        uuid          not null,
  card_id             uuid          not null,
  closing_month       date          not null,
  due_month           date          not null,
  period_start        date          not null,
  closing_date        date          not null,
  due_date            date          not null,
  dates_source        varchar(20)   not null,
-- Só "Informado no arquivo" (Sessão 6.2); nunca estimado.
  minimum_payment     numeric(19, 4),
  currency            varchar(3)    not null,
  closed_notified_at  timestamptz,
  created_at          timestamptz   not null,
  updated_at          timestamptz   not null,
  constraint uk_card_invoices_id_workspace unique (id, workspace_id),
  constraint uk_card_invoices_id_card_workspace unique (id, card_id, workspace_id),
  constraint uk_card_invoices_closing_month unique (card_id, closing_month),
-- A API identifica a fatura pelo mês de vencimento ("Fatura de outubro").
  constraint uk_card_invoices_due_month unique (card_id, due_month),
  constraint fk_card_invoices_workspace foreign key (workspace_id) references workspaces (id),
  constraint fk_card_invoices_card foreign key (card_id, workspace_id) references credit_cards (id, workspace_id),
  constraint ck_card_invoices_months check (
    extract(day from closing_month) = 1 and extract(day from due_month) = 1 and due_month >= closing_month),
  constraint ck_card_invoices_dates check (period_start < closing_date and closing_date < due_date),
  constraint ck_card_invoices_dates_source check (dates_source in ('CALCULATED', 'IMPORTED')),
  constraint ck_card_invoices_minimum_payment check (minimum_payment is null or minimum_payment >= 0),
  constraint ck_card_invoices_currency check (currency = 'BRL')
);

create index ix_card_invoices_workspace_card on card_invoices (workspace_id, card_id, closing_month);
-- Job do invoice.closed: só as que ainda não foram notificadas.
create index ix_card_invoices_closing_pending on card_invoices (closing_date) where closed_notified_at is null;

-- Parcelas (ADR-0025 §6), geradas com a compra (Sessão 6.1 parte C). À vista = 1 parcela.
create table card_installments (
  transaction_id      uuid          not null,
  number              smallint      not null,
  workspace_id        uuid          not null,
  card_id             uuid          not null,
  installment_count   smallint      not null,
-- Cópia imutável da data da compra: a reatribuição por mudança de ciclo não precisa ler transactions.
  purchased_on        date          not null,
  amount              numeric(19, 4) not null,
-- Parte consumida por estorno (ADR-0025 §8).
  cancelled_amount    numeric(19, 4) not null default 0,
  currency            varchar(3)    not null,
  invoice_id          uuid          not null,
  rules_version       varchar(20)   not null,
  created_at          timestamptz   not null,
  updated_at          timestamptz   not null,
  constraint pk_card_installments primary key (transaction_id, number),
  constraint fk_card_installments_workspace foreign key (workspace_id) references workspaces (id),
  constraint fk_card_installments_transaction foreign key (transaction_id, workspace_id)
    references transactions (id, workspace_id),
  constraint fk_card_installments_card foreign key (card_id, workspace_id) references credit_cards (id, workspace_id),
-- A fatura é sempre do mesmo cartão e do mesmo Workspace da parcela.
  constraint fk_card_installments_invoice foreign key (invoice_id, card_id, workspace_id)
    references card_invoices (id, card_id, workspace_id),
  constraint ck_card_installments_number check (number between 1 and installment_count),
  constraint ck_card_installments_count check (installment_count between 1 and 48),
  constraint ck_card_installments_amount_positive check (amount > 0),
  constraint ck_card_installments_cancelled check (cancelled_amount >= 0 and cancelled_amount <= amount),
  constraint ck_card_installments_currency check (currency = 'BRL')
);

create index ix_card_installments_invoice on card_installments (invoice_id);
create index ix_card_installments_workspace_card on card_installments (workspace_id, card_id);
