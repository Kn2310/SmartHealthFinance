-- Transactional Outbox (specs 05.4/05.7, ADR-0009 §14): o evento é gravado na mesma transação do fato e
-- publicado depois pelo relay. At-least-once: consumidores precisam ser idempotentes.
create table outbox_events (
  id              uuid          primary key,       -- eventId do envelope
  event_type      varchar(100)  not null,          -- resource.action
  event_version   integer       not null,
  workspace_id    uuid,                            -- nulo apenas para eventos de sistema
  aggregate_type  varchar(50)   not null,
  aggregate_id    uuid          not null,
  correlation_id  varchar(100),
  causation_id    uuid,
  trace_id        varchar(64),
  payload         jsonb         not null,          -- só identificadores e contagens, nunca dado financeiro bruto
  occurred_at     timestamptz   not null,
  published_at    timestamptz,
  attempts        integer       not null default 0,
  last_error      varchar(200),                    -- classe da exceção, nunca mensagem/payload
  constraint ck_outbox_events_version_positive check (event_version > 0),
  constraint ck_outbox_events_attempts check (attempts >= 0)
);

-- O relay só lê pendentes, na ordem em que ocorreram.
create index ix_outbox_events_pending on outbox_events (occurred_at, id) where published_at is null;
-- Limpeza de eventos já publicados.
create index ix_outbox_events_published on outbox_events (published_at) where published_at is not null;

-- Inbox: eventos já processados por consumidor crítico (spec 05.7). A PK é a garantia de idempotência.
create table processed_events (
  consumer      varchar(100)  not null,
  event_id      uuid          not null,
  processed_at  timestamptz   not null,
  constraint pk_processed_events primary key (consumer, event_id)
);
