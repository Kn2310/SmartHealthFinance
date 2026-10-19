---
name: flyway-migration
description: Cria ou altera o schema PostgreSQL do Smart Health Finance via Flyway (backend/src/main/resources/db/migration), seguindo as convenções de isolamento por Workspace, dinheiro em numeric(19,4), constraints espelhando o domínio e índices por consulta. Use sempre que uma tarefa criar tabela, coluna, índice, constraint ou backfill.
---

# Flyway migration

O schema pertence **exclusivamente** ao Flyway: `spring.jpa.hibernate.ddl-auto: validate`. Se a entidade JPA não bater com o schema, a aplicação não sobe. Referências: `V4__create_accounts.sql` (tabela CRUD), `V7__create_outbox_and_processed_events.sql` (infra de eventos) e `V15__create_insights.sql` (read model com jsonb e constraints entre colunas).

## Arquivo

- Caminho: `backend/src/main/resources/db/migration/V<N>__<verbo>_<objeto>.sql`, com N = maior versão existente + 1 (verifique com `ls`). Use inteiros e dois underscores. Exemplos: `V16__create_credit_cards.sql`, `V17__add_transaction_card_id.sql`, `V18__index_transactions_by_card.sql`.
- **Nunca edite uma migration já mergeada em `main`.** O Flyway grava um checksum (CRC32) de cada migration aplicada e o `validate` falha com `CHECKSUM_MISMATCH` em todos os ambientes que já a aplicaram. Corrigir = nova migration. Não use `flyway repair` para esconder edição.
- Uma migration por mudança coesa, escrita em SQL puro para PostgreSQL 17 (sem Java migrations, sem placeholders).
- Primeira linha: comentário citando spec/ADR e o porquê, em português. Comente decisões não óbvias ao lado da coluna/constraint (veja V4 e V15).

## Convenções de tabela

```sql
-- <Recurso> (spec 05.x, ADR-00NN): <o que é e o que NÃO guarda>.
create table <recursos> (
  id            uuid          primary key,            -- UUIDv7 gerado no domínio
  workspace_id  uuid          not null,
  ...
  status        varchar(20)   not null,
  amount        numeric(19,4) not null,               -- dinheiro: nunca real/double/float/money
  currency      varchar(3)    not null,
  created_at    timestamptz   not null,
  updated_at    timestamptz   not null,
  version       bigint        not null default 0,     -- optimistic lock (@Version)
-- Sem cascade: dado financeiro nunca some junto com o Workspace por acidente.
  constraint fk_<recursos>_workspace foreign key (workspace_id) references workspaces (id),
  constraint ck_<recursos>_status check (status in ('ACTIVE', 'ARCHIVED'))
);

-- Toda consulta é por Workspace (isolamento).
create index ix_<recursos>_workspace on <recursos> (workspace_id, created_at, id);
```

- Nomes em `snake_case`, tabela no plural. Prefixos: `pk_`, `fk_<tabela>_<alvo>`, `uq_`, `ck_`, `ix_`.
- **`workspace_id not null`** em todo dado de usuário, com FK para `workspaces(id)` **sem** `on delete cascade`. Exceção: tabelas de sistema (outbox, inbox), comentadas.
- Datas e horas: `timestamptz` para instantes e `date` para data de negócio (ex.: `as_of`, `occurred_on`). Nunca `timestamp` sem fuso.
- **Dinheiro:** `numeric(19,4)` + `currency varchar(3)`, igual ao `shared.domain.Money` (escala 4, 15 dígitos inteiros). Valor dentro de `jsonb` vai como **string decimal**, nunca número JSON.
- Enums: `varchar(N)` + `check (col in (...))` com exatamente os valores do enum Java. Enum ganhou valor → nova migration com `drop constraint` + `add constraint`.
- Invariantes que o banco consegue garantir viram `check`: texto não vazio (`btrim(name) <> ''`), faixas (`between 0 and 100`), colunas que andam juntas (`(a is null) = (b is null)`), exclusivas (`(a is null) <> (b is null)`).
- Idempotência e unicidade de negócio via `unique` (ex.: `uq_insights_fingerprint (workspace_id, fingerprint, algorithm_version)`; inbox com PK `(consumer, event_id)`).
- `jsonb` só para conteúdo de leitura sem consulta por campo (snapshots, conteúdo renderizado). Nada consultado em `where` vai para dentro do jsonb.

## Índices

- Um índice para cada método de repositório/consulta, **começando por `workspace_id`** e terminando na ordenação usada (`created_at, id`).
- Índices parciais para filas e jobs: `where published_at is null`, `where due_at is not null`, `where status <> 'ACTIVE'`.
- Em tabela grande já populada, avalie `create index concurrently`. O Flyway roda cada migration numa transação e `concurrently` não roda dentro de uma, então isso exige migration própria e decisão registrada.

## Dados sensíveis e LGPD

- Nunca crie coluna para CVV, senha bancária, senha de Open Finance ou segredo em texto puro (CLAUDE.md). Credencial de integração → referência a cofre ou valor cifrado, com ADR.
- Colunas de erro guardam a **classe** da exceção, nunca a mensagem ou o payload (`last_error varchar(200)` na outbox).
- Retenção e expurgo (spec 05.12) ficam documentados no comentário da tabela e implementados num job, não em trigger.

## Alterar tabela existente

- Coluna nova em tabela com dados: `add column ... null`, depois backfill e só então `set not null`, ou `not null default <valor>` quando o default for verdadeiro para as linhas antigas. Nunca deixe a migration falhar em produção por causa de linhas existentes.
- Renomear ou remover coluna quebra a versão anterior da API durante o deploy: faça em duas etapas (expand → contract) e registre o plano na ADR.
- Backfill pesado ou dependente de regra de domínio não é SQL na migration: use o padrão de rebuild na inicialização (`*RebuildRunner` com `shf.<modulo>.rebuild: missing|all|off`, como Analytics e Insights).

## Depois de escrever

1. Atualize a `@Entity` em `infrastructure/persistence/entity` (`@Column(name=..., nullable=..., updatable=...)`, `@Enumerated(EnumType.STRING)`, `@Version`). O `ddl-auto: validate` acusa divergências.
2. Tabela nova → `delete from <tabela>` em `backend/src/test/java/com/smarthealthfinance/identity/IdentityTables.java`, **antes** das tabelas que ela referencia (ordem das FKs).
3. Cubra no `*RepositoryIT`/`*StoreIT` (Postgres real via Testcontainers): isolamento por Workspace, `unique`/`check` relevantes (`DataIntegrityViolationException`) e optimistic lock. Detalhes na skill `backend-tests`.
4. Rode `./mvnw -B -ntp verify` (no Windows, `mvnw.cmd`; Docker precisa estar no ar). As ITs sobem do zero e aplicam todas as migrations.

## Checklist

- [ ] Versão N+1, nome `V<N>__verbo_objeto.sql`, nenhuma migration antiga editada
- [ ] Comentário com spec/ADR; `workspace_id not null` + FK sem cascade
- [ ] Dinheiro `numeric(19,4)` + `currency`; enums com `check`; invariantes em `check`
- [ ] Índice por consulta começando por `workspace_id`
- [ ] Entity JPA atualizada; `IdentityTables.clean` atualizado; IT cobrindo constraints
