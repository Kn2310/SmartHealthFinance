-- Histórico de conversas e feedback do Copilot (ADR-0040 §6, §8, §10). Só existe com COPILOT_HISTORY ativo (conversas)
-- e guarda a pergunta e a resposta renderizada, nunca o contexto financeiro, as refs cruas nem o mapa de pseudônimos.
-- Nenhuma coluna de dinheiro: os valores ficam formatados dentro de "answer", só para exibição.
-- Retenção de 90 dias por mensagem (expires_at); hard delete na exclusão, na revogação e no job.

create table copilot_conversations (
  id               uuid         primary key,
  workspace_id     uuid         not null,
  user_id          uuid         not null,
  -- Primeira pergunta cortada em 80 caracteres sem quebrar palavra, determinístico (ADR-0040 §6).
  title            varchar(120) not null,
  created_at       timestamptz  not null,
  last_message_at  timestamptz  not null,
  constraint fk_copilot_conversations_workspace foreign key (workspace_id) references workspaces (id),
  constraint fk_copilot_conversations_user foreign key (user_id) references users (id),
  constraint ck_copilot_conversations_last check (last_message_at >= created_at)
);

-- Listagem da pessoa no Workspace, da mais recente para a mais antiga.
create index ix_copilot_conversations_owner_last
  on copilot_conversations (workspace_id, user_id, last_message_at desc);

create table copilot_messages (
  id               uuid         primary key,
  conversation_id  uuid         not null,
  workspace_id     uuid         not null,
  user_id          uuid         not null,
  -- A pergunta ao Copilot: o replay idempotente e o feedback localizam a mensagem por aqui.
  request_id       uuid         not null,
  question         varchar(500) not null,
  intent           varchar(30),
  code             varchar(20)  not null,
  -- O CopilotAnswer renderizado (segmentos, valores formatados, quadro, ações, "dados usados").
  answer           jsonb        not null,
  confidence       varchar(10),
  validation       varchar(20)  not null,
  versions         jsonb        not null,
  created_at       timestamptz  not null,
  expires_at       timestamptz  not null,
  constraint fk_copilot_messages_conversation foreign key (conversation_id)
    references copilot_conversations (id) on delete cascade,
  constraint uq_copilot_messages_request unique (request_id),
  constraint ck_copilot_messages_code check (code in ('OK', 'SAFE_MODE', 'INSUFFICIENT_DATA', 'OUT_OF_SCOPE')),
  constraint ck_copilot_messages_intent check (intent is null or intent in ('OVERVIEW', 'CASH_FLOW', 'CATEGORY',
    'MERCHANT', 'HEALTH', 'INSIGHTS', 'CARDS', 'FORECAST', 'GOALS', 'GOAL_SIMULATION', 'OUT_OF_SCOPE',
    'UNSUPPORTED_ACTION')),
  constraint ck_copilot_messages_confidence check (confidence is null or confidence in ('HIGH', 'MEDIUM', 'LOW')),
  constraint ck_copilot_messages_validation check (validation in ('VALID', 'REGENERATED', 'SAFE_MODE',
    'NOT_APPLICABLE')),
  constraint ck_copilot_messages_expiry check (expires_at > created_at)
);

-- Leitura da conversa em ordem e continuidade (últimas perguntas).
create index ix_copilot_messages_conversation on copilot_messages (conversation_id, created_at);
-- Job de retenção.
create index ix_copilot_messages_expires on copilot_messages (expires_at);

-- Feedback por pergunta: sem texto livre e sem conteúdo; vale com ou sem histórico. O último envio vale.
create table copilot_feedback (
  request_id    uuid         primary key,
  workspace_id  uuid         not null,
  user_id       uuid         not null,
  rating        varchar(20)  not null,
  reason        varchar(30),
  intent        varchar(30),
  versions      jsonb        not null,
  created_at    timestamptz  not null,
  updated_at    timestamptz  not null,
  expires_at    timestamptz  not null,
  constraint fk_copilot_feedback_workspace foreign key (workspace_id) references workspaces (id),
  constraint fk_copilot_feedback_user foreign key (user_id) references users (id),
  constraint ck_copilot_feedback_rating check (rating in ('HELPFUL', 'NOT_HELPFUL')),
  constraint ck_copilot_feedback_reason check (reason is null or reason in ('WRONG_NUMBERS', 'DID_NOT_ANSWER',
    'CONFUSING', 'OTHER')),
  constraint ck_copilot_feedback_intent check (intent is null or intent in ('OVERVIEW', 'CASH_FLOW', 'CATEGORY',
    'MERCHANT', 'HEALTH', 'INSIGHTS', 'CARDS', 'FORECAST', 'GOALS', 'GOAL_SIMULATION', 'OUT_OF_SCOPE',
    'UNSUPPORTED_ACTION')),
  constraint ck_copilot_feedback_expiry check (expires_at > created_at)
);

create index ix_copilot_feedback_expires on copilot_feedback (expires_at);

-- O feedback valida que a pergunta é da pessoa no Workspace pelo ledger.
create index ix_ai_usage_request on ai_usage (request_id);
