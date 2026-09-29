-- Identity: Workspace é o boundary de isolamento de todo dado financeiro (specs 05.2/05.4, ADR-0003).
-- Autorização acontece sempre via workspace_memberships; owner_user_id existe para a regra
-- "no máximo um Workspace pessoal por usuário".
create table workspaces (
   id             uuid         primary key,
   kind           varchar(20)  not null,
   owner_user_id  uuid         not null,
   name           varchar(100) not null,
   base_currency  varchar(3)   not null,
   created_at     timestamptz  not null,
   updated_at     timestamptz  not null,
   version        bigint       not null default 0,
   constraint fk_workspaces_owner foreign key (owner_user_id) references users (id),
   constraint ck_workspaces_kind check (kind in ('PERSONAL')),
   -- MVP: moeda base fixa. Ampliar este check via nova migration quando houver multi-moeda.
   constraint ck_workspaces_base_currency check (base_currency = 'BRL')
);

-- Idempotência do provisionamento sob concorrência: INSERT ... ON CONFLICT usa este índice.
create unique index uk_workspaces_personal_owner on workspaces (owner_user_id) where kind = 'PERSONAL';

-- Memberships fazem parte do agregado Workspace (lock otimista via workspaces.version).
create table workspace_memberships (
   workspace_id  uuid         not null,
   user_id       uuid         not null,
   role          varchar(20)  not null,
   joined_at     timestamptz  not null,
   constraint pk_workspace_memberships primary key (workspace_id, user_id),
   constraint fk_workspace_memberships_workspace foreign key (workspace_id) references workspaces (id) on delete cascade,
   constraint fk_workspace_memberships_user foreign key (user_id) references users (id),
   constraint ck_workspace_memberships_role check (role in ('OWNER'))
);

-- Autorização e listagem consultam por usuário.
create index ix_workspace_memberships_user on workspace_memberships (user_id);
