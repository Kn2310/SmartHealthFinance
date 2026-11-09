-- Consents (spec 05.12 §Consent, ADR-0038): consentimento da pessoa dentro de um Workspace, por finalidade.
-- Persistidos só ACTIVE e REVOKED; PENDING (sem linha) e EXPIRED (versão do termo antiga) são derivados na leitura.
create table consents (
  id                uuid         primary key,
  workspace_id      uuid         not null,
  user_id           uuid         not null,
  purpose           varchar(40)  not null,
  status            varchar(20)  not null,
  -- Versão do termo concedida por último (mantida na revogação).
  terms_version     varchar(40)  not null,
  granted_at        timestamptz  not null,
  revoked_at        timestamptz,
  created_at        timestamptz  not null,
  updated_at        timestamptz  not null,
  version           bigint       not null default 0,
-- Sem cascade: a revogação e a exclusão de conta são fluxos explícitos (05.12).
  constraint fk_consents_workspace foreign key (workspace_id) references workspaces (id),
  constraint fk_consents_user foreign key (user_id) references users (id),
  constraint uq_consents_workspace_user_purpose unique (workspace_id, user_id, purpose),
  constraint ck_consents_purpose check (purpose in ('COPILOT_DATA_USE', 'COPILOT_HISTORY')),
  constraint ck_consents_status check (status in ('ACTIVE', 'REVOKED')),
  constraint ck_consents_terms_version_not_blank check (btrim(terms_version) <> ''),
  constraint ck_consents_revoked_at check ((status = 'REVOKED') = (revoked_at is not null))
);

-- Auditoria separada dos logs técnicos (05.12 §Auditoria), só de inserção. Sem IP nem user-agent (minimização).
-- Sem FK para consents: a trilha sobrevive a qualquer reorganização da tabela de estado.
create table consent_audit (
  id                uuid         primary key,
  consent_id        uuid         not null,
  workspace_id      uuid         not null,
  user_id           uuid         not null,
  purpose           varchar(40)  not null,
  action            varchar(20)  not null,
  terms_version     varchar(40)  not null,
  cause             varchar(20)  not null,
  occurred_at       timestamptz  not null,
  constraint fk_consent_audit_workspace foreign key (workspace_id) references workspaces (id),
  constraint fk_consent_audit_user foreign key (user_id) references users (id),
  constraint ck_consent_audit_action check (action in ('GRANTED', 'REVOKED')),
  constraint ck_consent_audit_cause check (cause in ('USER', 'CASCADE'))
);

create index ix_consent_audit_workspace_user on consent_audit (workspace_id, user_id, occurred_at);

-- A trilha nunca é reescrita (ADR-0038 §5). Exclusão fica reservada ao fluxo de exclusão de conta (M13).
create function consent_audit_reject_update() returns trigger
  language plpgsql as
$$
begin
  raise exception 'consent_audit is append-only';
end;
$$;

create trigger tr_consent_audit_append_only
  before update on consent_audit
  for each row execute function consent_audit_reject_update();
