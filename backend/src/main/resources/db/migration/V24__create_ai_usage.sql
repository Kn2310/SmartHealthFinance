-- Usage ledger do Copilot (spec 05.8 "AI Usage Ledger", ADR-0039 §10): uma linha por chamada ao provedor, só com
-- metadados (nunca pergunta, contexto ou resposta). Fonte das quotas provisórias por Workspace; mantido mesmo sem
-- histórico de conversa. Custo em USD com 6 casas (frações de centavo por chamada), pela tabela de preços versionada.
create table ai_usage (
  id                  uuid           primary key,
  workspace_id        uuid           not null,
  user_id             uuid           not null,
  -- Agrupa as chamadas de uma mesma pergunta (intenção, resposta, regeneração).
  request_id          uuid           not null,
  request_type        varchar(20)    not null,
  provider            varchar(40)    not null,
  model               varchar(80)    not null,
  input_tokens        bigint         not null,
  output_tokens       bigint         not null,
  cache_read_tokens   bigint         not null,
  cache_write_tokens  bigint         not null,
  cost                numeric(19,6)  not null,
  pricing_version     varchar(40)    not null,
  latency_ms          bigint         not null,
  outcome             varchar(20)    not null,
  created_at          timestamptz    not null,
-- Sem cascade: o ledger é registro de custo; a exclusão de conta (M13) decide o destino.
  constraint fk_ai_usage_workspace foreign key (workspace_id) references workspaces (id),
  constraint fk_ai_usage_user foreign key (user_id) references users (id),
  constraint ck_ai_usage_request_type check (request_type in ('INTENT', 'ANSWER', 'REGENERATE')),
  constraint ck_ai_usage_outcome check (outcome in ('OK', 'REFUSED', 'TIMEOUT', 'UNAVAILABLE', 'RATE_LIMITED',
                                                    'INVALID_OUTPUT')),
  constraint ck_ai_usage_tokens check (input_tokens >= 0 and output_tokens >= 0 and cache_read_tokens >= 0
                                       and cache_write_tokens >= 0),
  constraint ck_ai_usage_cost check (cost >= 0),
  constraint ck_ai_usage_latency check (latency_ms >= 0)
);

-- Quotas: perguntas do dia/mês (request_type = INTENT) e tokens de entrada do mês, sempre por Workspace.
create index ix_ai_usage_workspace_type_created on ai_usage (workspace_id, request_type, created_at);
create index ix_ai_usage_workspace_created on ai_usage (workspace_id, created_at);
