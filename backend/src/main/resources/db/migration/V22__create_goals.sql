-- Goals goals-v1 (spec 05.2/05.3/05.4, ADR-0034, ADR-0035): o objetivo guarda só o plano e o estado. O progresso e os
-- aportes NÃO são armazenados: o progresso é o saldo do destino e os aportes são as transferências POSTED dele,
-- derivados das transações (ADR-0034 §2). Também não há tabela de simulações: são efêmeras (§5).
create table goals (
  id                       uuid          primary key,
  workspace_id             uuid          not null,
  name                     varchar(60)   not null,
  type                     varchar(20)   not null,
  target_amount            numeric(19,4) not null,
  planned_monthly          numeric(19,4) not null,
  currency                 varchar(3)    not null,
  -- Mês do prazo, guardado como o dia 1 (YearMonth); nulo = sem prazo.
  deadline                 date,
  source_account_id        uuid,
  destination_kind         varchar(20)   not null,
  destination_account_id   uuid,
  origin_kind              varchar(20)   not null,
  -- Sem FK: o insight pode ser expurgado pela retenção dos Insights (ADR-0022) e o objetivo continua.
  origin_insight_id        uuid,
  status                   varchar(20)   not null,
  tracking_start           date          not null,
  completed_at             timestamptz,
  completed_amount         numeric(19,4),
  created_at               timestamptz   not null,
  updated_at               timestamptz   not null,
  version                  bigint        not null default 0,
-- Sem cascade: dado financeiro nunca some junto com o Workspace por acidente. As contas usam FK composta para nunca
-- apontar para conta de outro Workspace.
  constraint fk_goals_workspace foreign key (workspace_id) references workspaces (id),
  constraint fk_goals_source_account foreign key (source_account_id, workspace_id)
    references accounts (id, workspace_id),
  constraint fk_goals_destination_account foreign key (destination_account_id, workspace_id)
    references accounts (id, workspace_id),
  constraint ck_goals_name_not_blank check (btrim(name) <> ''),
  constraint ck_goals_type check (type in ('EMERGENCY_RESERVE', 'PURCHASE', 'CUSTOM')),
  constraint ck_goals_status check (status in ('ACTIVE', 'PAUSED', 'COMPLETED', 'ARCHIVED')),
  constraint ck_goals_origin_kind check (origin_kind in ('MANUAL', 'INSIGHT', 'HEALTH')),
  constraint ck_goals_origin_insight check ((origin_kind = 'INSIGHT') = (origin_insight_id is not null)),
  constraint ck_goals_destination_kind check (destination_kind in ('ACCOUNT', 'RESERVE')),
  -- A reserva usa as contas SAVINGS ativas (RESERVE, sem conta); os demais, uma conta dedicada (ADR-0034 §2.1, §3).
  constraint ck_goals_destination check (
    (type = 'EMERGENCY_RESERVE' and destination_kind = 'RESERVE' and destination_account_id is null)
    or (type <> 'EMERGENCY_RESERVE' and destination_kind = 'ACCOUNT' and destination_account_id is not null)),
  constraint ck_goals_source_not_destination check (source_account_id is null or destination_account_id is null
    or source_account_id <> destination_account_id),
  constraint ck_goals_target_positive check (target_amount > 0),
  constraint ck_goals_planned_not_negative check (planned_monthly >= 0),
  -- MVP: moeda do Workspace (BRL fixa, ADR-0003).
  constraint ck_goals_currency check (currency = 'BRL'),
  constraint ck_goals_deadline_first_day check (deadline is null or extract(day from deadline) = 1),
  constraint ck_goals_completed check ((completed_at is null) = (completed_amount is null)),
  constraint ck_goals_completed_status check (completed_at is null or status in ('COMPLETED', 'ARCHIVED'))
);

-- Lista do Workspace, mais recentes primeiro.
create index ix_goals_workspace on goals (workspace_id, created_at desc, id desc);

-- Exclusividade do destino entre objetivos ativos e pausados (ADR-0034 §2.1).
create unique index uq_goals_open_destination on goals (workspace_id, destination_account_id)
  where destination_account_id is not null and status in ('ACTIVE', 'PAUSED');

-- No máximo uma reserva de emergência ativa ou pausada por Workspace (ADR-0034 §3).
create unique index uq_goals_open_reserve on goals (workspace_id)
  where type = 'EMERGENCY_RESERVE' and status in ('ACTIVE', 'PAUSED');
