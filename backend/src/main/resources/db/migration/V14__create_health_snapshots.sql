-- Snapshot do Financial Health (ADR-0017/0018). Derivado das projeções de Analytics e dos saldos do Overview: pode ser
-- apagado e reconstruído a qualquer momento (RebuildHealthSnapshots) sem perda de fato financeiro.

-- Marcação para recálculo com debounce, uma linha por Workspace. Também é o lock que serializa os recálculos do
-- Workspace (o job pega as vencidas com FOR UPDATE SKIP LOCKED).
-- dirty_since: primeiro evento ainda não refletido; due_at: quando recalcular (adiado a cada evento, com teto).
create table health_workspaces (
  workspace_id  uuid         primary key,
  dirty_since   timestamptz,
  due_at        timestamptz,
  computed_at   timestamptz,
  constraint fk_health_workspaces_workspace foreign key (workspace_id) references workspaces (id),
  constraint ck_health_workspaces_dirty check ((dirty_since is null) = (due_at is null))
);

create index ix_health_workspaces_due on health_workspaces (due_at) where due_at is not null;

-- Resultado de cada mês de referência por versão do algoritmo: o mês corrente e os 12 fechados anteriores.
-- Linhas de outras versões nunca são reescritas (ADR-0017 §5). result guarda dimensões, evidências, fatores e
-- próximos passos; dinheiro e razões como string decimal, nunca número JSON.
create table health_snapshots (
  workspace_id       uuid         not null,
  algorithm_version  varchar(20)  not null,
  month              date         not null,
  state              varchar(20)  not null,
  score              smallint,
  partial            boolean      not null,
  estimated          boolean      not null,
  as_of              date         not null,
  computed_at        timestamptz  not null,
  result             jsonb        not null,
  constraint pk_health_snapshots primary key (workspace_id, algorithm_version, month),
  constraint fk_health_snapshots_workspace foreign key (workspace_id) references workspaces (id),
  constraint ck_health_snapshots_first_day check (extract(day from month) = 1),
  constraint ck_health_snapshots_state check (state in ('READY', 'INSUFFICIENT_DATA')),
  constraint ck_health_snapshots_score check (score between 0 and 100),
  constraint ck_health_snapshots_ready check ((state = 'READY') = (score is not null))
);
