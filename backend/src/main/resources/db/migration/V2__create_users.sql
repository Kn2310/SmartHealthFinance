-- Identity: projeção local da identidade OIDC. Senhas ficam exclusivamente no IdP.
create table users (
   id            uuid         primary key,
   oidc_issuer   varchar(255) not null,
   oidc_subject  varchar(255) not null,
   email         varchar(254) not null,
   display_name  varchar(100) not null,
   status        varchar(20)  not null,
   created_at    timestamptz  not null,
   updated_at    timestamptz  not null,
   version       bigint       not null default 0,
   constraint uk_users_oidc_identity unique (oidc_issuer, oidc_subject),
   constraint ck_users_status check (status in ('ACTIVE', 'DISABLED'))
);

comment on column users.email is 'Dado pessoal (LGPD). Sincronizado do IdP. Não registrar em logs.';