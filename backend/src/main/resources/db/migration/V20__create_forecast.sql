-- Forecast forecast-v1 (ADR-0030, ADR-0031 §4). O snapshot é derivado (Analytics, Overview, cartões e contas) e pode ser
-- recalculado a qualquer momento. As séries recorrentes e o previsto x realizado NÃO são deriváveis: guardam estado do
-- usuário e a projeção congelada na virada do mês. Por isso nenhum recálculo ou rebuild apaga linhas de
-- forecast_recurring_series nem de forecast_month_outcomes. Migration somente aditiva.

-- Marcação para recálculo com debounce, uma linha por Workspace. Também é o lock que serializa os recálculos e as
-- ações do usuário (o job pega as vencidas com FOR UPDATE SKIP LOCKED).
-- dirty_since: primeiro evento ainda não refletido; due_at: quando recalcular (adiado a cada evento, com teto).
create table forecast_workspaces (
  workspace_id  uuid         primary key,
  dirty_since   timestamptz,
  due_at        timestamptz,
  computed_at   timestamptz,
  constraint fk_forecast_workspaces_workspace foreign key (workspace_id) references workspaces (id),
  constraint ck_forecast_workspaces_dirty check ((dirty_since is null) = (due_at is null))
);

create index ix_forecast_workspaces_due on forecast_workspaces (due_at) where due_at is not null;

-- Séries recorrentes (ADR-0030 §2), identidade (workspace, direction, series_key). As colunas DETECTADAS são as únicas
-- que a redetecção escreve; o ESTADO DO USUÁRIO (status, origin, user_expected_*, status_changed_at) nunca é escrito
-- por ela. Uma chave que deixa de ser candidata continua na tabela (DETECTED sai das projeções; CONFIRMED vira STALE
-- pela regra dos 3 meses, derivada de "hoje"). Dinheiro em numeric(19,4); a série guarda o texto de exibição
-- (comerciante ou categoria) só como rótulo. channel_id é a conta ou o cartão (sem FK: aponta para duas tabelas).
create table forecast_recurring_series (
  workspace_id          uuid          not null,
  direction             varchar(10)   not null,
  series_key            varchar(160)  not null,
  -- detectado
  label                 varchar(160),
  channel_kind          varchar(10)   not null,
  channel_id            uuid          not null,
  periodicity           varchar(10)   not null,
  expected_day          smallint      not null,
  expected_amount       numeric(19,4) not null,
  amount_min            numeric(19,4) not null,
  amount_max            numeric(19,4) not null,
  currency              varchar(3)    not null,
  confidence            varchar(10)   not null,
  occurrences           uuid[]        not null,
  algorithm_version     varchar(20)   not null,
  -- estado do usuário
  status                varchar(10)   not null,
  origin                varchar(10)   not null,
  user_expected_amount  numeric(19,4),
  user_expected_day     smallint,
  status_changed_at     timestamptz,
  created_at            timestamptz   not null,
  updated_at            timestamptz   not null,
  constraint pk_forecast_recurring_series primary key (workspace_id, direction, series_key),
  constraint fk_forecast_recurring_series_workspace foreign key (workspace_id) references workspaces (id),
  constraint ck_forecast_recurring_series_direction check (direction in ('INCOME', 'EXPENSE')),
  constraint ck_forecast_recurring_series_key check (btrim(series_key) <> ''),
  constraint ck_forecast_recurring_series_channel check (channel_kind in ('ACCOUNT', 'CARD')),
  constraint ck_forecast_recurring_series_periodicity check (periodicity in ('MONTHLY')),
  constraint ck_forecast_recurring_series_day check (expected_day between 1 and 31),
  constraint ck_forecast_recurring_series_amount check (expected_amount > 0 and amount_min > 0 and amount_min <= amount_max),
  constraint ck_forecast_recurring_series_currency check (currency = 'BRL'),
  constraint ck_forecast_recurring_series_confidence check (confidence in ('HIGH', 'MEDIUM', 'LOW')),
  constraint ck_forecast_recurring_series_status check (status in ('DETECTED', 'CONFIRMED', 'IGNORED')),
  constraint ck_forecast_recurring_series_origin check (origin in ('DETECTED', 'USER')),
  constraint ck_forecast_recurring_series_user_amount check (user_expected_amount is null or user_expected_amount > 0),
  constraint ck_forecast_recurring_series_user_day check (user_expected_day is null or user_expected_day between 1 and 31)
);

-- Snapshot do Forecast: uma linha por Workspace e versão do algoritmo; uma forecast-v2 grava ao lado e linhas de
-- outras versões nunca são reescritas (ADR-0030 §6). result guarda as projeções (com basis, partes, faixas, confiança,
-- limitações e evidências) e a situação das séries no cálculo; dinheiro e razões como string decimal, nunca número JSON.
create table forecast_snapshots (
  workspace_id       uuid         not null,
  algorithm_version  varchar(20)  not null,
  as_of              date         not null,
  computed_at        timestamptz  not null,
  result             jsonb        not null,
  constraint pk_forecast_snapshots primary key (workspace_id, algorithm_version),
  constraint fk_forecast_snapshots_workspace foreign key (workspace_id) references workspaces (id)
);

-- Previsto x realizado (ADR-0030 §6, ADR-0031 §6): a projeção do mês que fechou, congelada na virada (ON CONFLICT DO
-- NOTHING: a primeira gravação vence e nunca é reescrita), com o realizado e o erro regravados até o dia 5 do mês
-- seguinte. settled_at marca o fim da reconciliação; depois dele a linha é imutável. Só backend no v1.
create table forecast_month_outcomes (
  workspace_id            uuid          not null,
  month                   date          not null,
  algorithm_version       varchar(20)   not null,
  currency                varchar(3)    not null,
  confidence              varchar(10)   not null,
  -- projeção congelada: valor central e faixa
  projected_expense       numeric(19,4) not null,
  projected_income        numeric(19,4) not null,
  projected_net           numeric(19,4) not null,
  projected_expense_low   numeric(19,4) not null,
  projected_income_low    numeric(19,4) not null,
  projected_net_low       numeric(19,4) not null,
  projected_expense_high  numeric(19,4) not null,
  projected_income_high   numeric(19,4) not null,
  projected_net_high      numeric(19,4) not null,
  -- realizado e erro (realizado - previsto); nulos até a primeira reconciliação
  realized_expense        numeric(19,4),
  realized_income         numeric(19,4),
  realized_net            numeric(19,4),
  error_expense           numeric(19,4),
  error_income            numeric(19,4),
  error_net               numeric(19,4),
  error_expense_percent   numeric(24,2),
  error_income_percent    numeric(24,2),
  expense_within_range    boolean,
  income_within_range     boolean,
  frozen_at               timestamptz   not null,
  settled_at              timestamptz,
  constraint pk_forecast_month_outcomes primary key (workspace_id, month, algorithm_version),
  constraint fk_forecast_month_outcomes_workspace foreign key (workspace_id) references workspaces (id),
  constraint ck_forecast_month_outcomes_first_day check (extract(day from month) = 1),
  constraint ck_forecast_month_outcomes_currency check (currency = 'BRL'),
  constraint ck_forecast_month_outcomes_confidence check (confidence in ('HIGH', 'MEDIUM', 'LOW')),
  constraint ck_forecast_month_outcomes_range check (
    projected_expense_low <= projected_expense and projected_expense <= projected_expense_high
    and projected_income_low <= projected_income and projected_income <= projected_income_high
    and projected_net_low <= projected_net and projected_net <= projected_net_high),
  -- realizado e erro andam juntos
  constraint ck_forecast_month_outcomes_realized check (
    (realized_expense is null) = (realized_income is null) and (realized_expense is null) = (realized_net is null)
    and (realized_expense is null) = (error_expense is null) and (realized_expense is null) = (error_income is null)
    and (realized_expense is null) = (error_net is null)
    and (realized_expense is null) = (expense_within_range is null)
    and (realized_expense is null) = (income_within_range is null)),
  constraint ck_forecast_month_outcomes_settled check (settled_at is null or settled_at >= frozen_at)
);

-- Reconciliação: só os meses ainda não liquidados de um Workspace.
create index ix_forecast_month_outcomes_unsettled on forecast_month_outcomes (workspace_id, month)
  where settled_at is null;
