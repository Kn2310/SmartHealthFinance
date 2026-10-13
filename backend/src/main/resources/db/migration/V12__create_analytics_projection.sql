-- Projeção materializada de Analytics (ADR-0014). Derivada de transactions: pode ser apagada e reconstruída a
-- qualquer momento (RebuildAnalyticsProjection) sem perda de fato financeiro.

-- Uma linha por dia × conta × papel no fluxo × categoria × comerciante, só com transações POSTED que entram no fluxo
-- (INCOME, EXPENSE, REFUND) ou no informativo de transferências (TRANSFER). Ajustes ficam de fora (ADR-0006 §9).
-- A conta fica no grão para que arquivar uma conta ou tirá-la do total valha na hora, sem eventos de Accounts.
create table analytics_daily_flows (
  workspace_id       uuid           not null,
  day                date           not null,
  account_id         uuid           not null,
  flow               varchar(10)    not null,
  category_code      varchar(40),
  merchant_key       varchar(120),
  merchant_name      varchar(120),
  currency           varchar(3)     not null,
  amount             numeric(19, 4) not null,
  transaction_count  integer        not null,
  constraint fk_analytics_daily_flows_workspace foreign key (workspace_id) references workspaces (id),
  constraint ck_analytics_daily_flows_flow check (flow in ('INCOME', 'EXPENSE', 'REFUND', 'TRANSFER')),
  constraint ck_analytics_daily_flows_amount check (amount > 0),
  constraint ck_analytics_daily_flows_count check (transaction_count > 0),
  constraint ck_analytics_daily_flows_merchant check ((merchant_key is null) = (merchant_name is null))
);

-- Todas as leituras são por Workspace e intervalo de datas.
create index ix_analytics_daily_flows_workspace_day on analytics_daily_flows (workspace_id, day);

-- Estado da projeção por Workspace. A linha também é o lock que serializa rebuild e recálculos do Workspace.
-- version nula = nunca construída; diferente da versão do código = precisa de rebuild.
create table analytics_projections (
  workspace_id  uuid         primary key,
  version       integer,
  rebuilt_at    timestamptz,
  constraint fk_analytics_projections_workspace foreign key (workspace_id) references workspaces (id),
  constraint ck_analytics_projections_built check ((version is null) = (rebuilt_at is null))
);

-- Quando cada mês foi recalculado: um evento publicado antes disso já está refletido (coalescência).
create table analytics_projection_months (
  workspace_id  uuid         not null,
  month         date         not null,
  refreshed_at  timestamptz  not null,
  constraint pk_analytics_projection_months primary key (workspace_id, month),
  constraint fk_analytics_projection_months_workspace foreign key (workspace_id)
    references analytics_projections (workspace_id) on delete cascade,
  constraint ck_analytics_projection_months_first_day check (extract(day from month) = 1)
);

-- Freshness: eventos do Workspace ainda não processados pelo consumidor do Analytics.
create index ix_outbox_events_workspace_occurred on outbox_events (workspace_id, occurred_at);
