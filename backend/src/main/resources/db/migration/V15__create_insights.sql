-- Insights (ADR-0021/0022). Derivados do Analytics e do Health, mas com estado do usuário (visto, dispensado,
-- feedback): a geração reconcilia e nunca apaga; só a retenção de 13 meses apaga insights não visíveis.

-- Marcação para geração com debounce, uma linha por Workspace. Também é o lock que serializa as gerações do
-- Workspace (o job pega as vencidas com FOR UPDATE SKIP LOCKED). Guarda a última geração (versão, momento e data
-- de negócio) para o status e o rebuild.
create table insights_workspaces (
  workspace_id       uuid         primary key,
  dirty_since        timestamptz,
  due_at             timestamptz,
  generated_at       timestamptz,
  as_of              date,
  algorithm_version  varchar(20),
  constraint fk_insights_workspaces_workspace foreign key (workspace_id) references workspaces (id),
  constraint ck_insights_workspaces_dirty check ((dirty_since is null) = (due_at is null)),
  constraint ck_insights_workspaces_generated check (
    (generated_at is null) = (as_of is null) and (generated_at is null) = (algorithm_version is null))
);

create index ix_insights_workspaces_due on insights_workspaces (due_at) where due_at is not null;

-- Um insight por (Workspace, fingerprint, versão do catálogo). fingerprint = SHA-256 hex de detector, Workspace,
-- período e sujeito. content guarda título, apoio, recomendação, evidências, ações e referências; dinheiro e razões
-- como string decimal, nunca número JSON.
create table insights (
  id                 uuid          primary key,
  workspace_id       uuid          not null,
  fingerprint        char(64)      not null,
  algorithm_version  varchar(20)   not null,
  detector           varchar(40)   not null,
  type               varchar(20)   not null,
  period_key         varchar(7),
  subject            varchar(200)  not null,
  status             varchar(20)   not null,
  status_reason      varchar(30),
  importance         varchar(20)   not null,
  impact             smallint      not null,
  relevance          smallint      not null,
  priority           smallint      not null,
  confidence         varchar(10)   not null,
  impact_amount      numeric(19,4),
  impact_points      smallint,
  partial            boolean       not null,
  seen_at            timestamptz,
  dismissed_at       timestamptz,
  feedback           varchar(12),
  feedback_at        timestamptz,
  revision           integer       not null,
  created_at         timestamptz   not null,
  updated_at         timestamptz   not null,
  status_changed_at  timestamptz   not null,
  content            jsonb         not null,
  constraint fk_insights_workspace foreign key (workspace_id) references workspaces (id),
  constraint uq_insights_fingerprint unique (workspace_id, fingerprint, algorithm_version),
  constraint ck_insights_status check (status in ('ACTIVE', 'RESOLVED', 'EXPIRED', 'DISMISSED')),
  constraint ck_insights_type check (type in ('SPENDING', 'INCOME', 'HEALTH')),
  constraint ck_insights_importance check (importance in ('IMPORTANT', 'INFORMATIVE')),
  constraint ck_insights_feedback check (feedback in ('USEFUL', 'NOT_USEFUL')),
  constraint ck_insights_scores check (impact between 0 and 100 and relevance between 0 and 100
                                       and priority between 0 and 100),
  constraint ck_insights_impact check ((impact_amount is null) <> (impact_points is null)),
  constraint ck_insights_dismissed check ((status = 'DISMISSED') = (dismissed_at is not null)),
  constraint ck_insights_feedback_at check ((feedback is null) = (feedback_at is null)),
  constraint ck_insights_revision check (revision >= 1)
);

create index ix_insights_workspace_status on insights (workspace_id, status, algorithm_version);
create index ix_insights_retention on insights (updated_at) where status <> 'ACTIVE';
