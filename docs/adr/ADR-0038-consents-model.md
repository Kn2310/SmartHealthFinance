# ADR-0038 — Consentimentos do Copilot

## Status

Accepted

## Context

O Copilot (M11, spec 05.8) só pode enviar dados financeiros a um LLM com consentimento explícito da pessoa (05.12 §IA,
§Consent; 05.13 §17). Não existe nenhum modelo de consentimento no produto: o ADR-0021 §10 adiou o consentimento "gerar
insights" até haver um módulo. O layout `D-ProfileConsents` (design/specs/03-screens.md) mostra três switches com data
de concessão: gerar insights, Copilot usar dados e guardar histórico do Copilot, mais um alerta sobre revogação. A 05.12
define os estados `PENDING / ACTIVE / EXPIRED / REVOKED / FAILED`, pensados também para o Open Finance.

Decisões aprovadas pelo usuário em 2026-11-09 (Sessão 8.1, Parte 0).

## Decision

### 1. Módulo e chave

- Módulo novo `consents`. Um consentimento é da **pessoa dentro de um Workspace**: chave única
  `(workspace_id, user_id, purpose)`. Hoje só existe `OWNER` (ADR-0003), mas a chave já serve para Workspaces
  compartilhados: cada membro decide por si.
- Autorização por membership (`WorkspaceAccessGuard`, ADR-0003). A pessoa só vê e altera os **próprios** consentimentos;
  Workspace alheio ou inexistente → 404 `WORKSPACE_NOT_FOUND`.

### 2. Finalidades (`ConsentPurpose`)

| Finalidade | Termo vigente | Pré-requisito | Efeito |
|---|---|---|---|
| `COPILOT_DATA_USE` | `copilot-data-v1` | — | Sem ela, o pipeline do Copilot não envia nada ao LLM |
| `COPILOT_HISTORY` | `copilot-history-v1` | `COPILOT_DATA_USE` ativo | Sem ela, as conversas não são guardadas |

- `INSIGHTS_GENERATION` fica **adiado**: o nome está reservado, mas não existe no código nem na API. Insights e Health
  seguem gerados como hoje. A tela mostra só os dois switches do Copilot (divergência aprovada do layout, que mostra
  três). Dívida registrada no backlog.
- O termo vigente de cada finalidade é uma constante versionada no código (`copilot-data-v1`). O texto do termo vive
  no frontend, junto da versão que ele representa.

### 3. Estados e transições

- Persistidos só `ACTIVE` e `REVOKED`. O estado exposto (`ConsentState`) traz o enum completo da 05.12:
  - `PENDING`: nunca respondido (não há linha). É o padrão: **opt-in**, Privacy by Default.
  - `ACTIVE`: concedido na versão vigente do termo.
  - `EXPIRED`: concedido numa versão anterior do termo. Derivado na leitura, sem job; conta como **não ativo**.
  - `REVOKED`: revogado.
  - `FAILED`: reservado para o Open Finance; nunca produzido aqui.
- Conceder exige informar a versão vigente do termo (`termsVersion`): versão ausente ou diferente → 409
  `CONSENT_TERMS_OUTDATED`. Conceder `COPILOT_HISTORY` sem `COPILOT_DATA_USE` ativo → 409
  `CONSENT_PREREQUISITE_MISSING`.
- **Idempotência:** conceder o que já está `ACTIVE` na mesma versão, ou revogar o que não está ativo (`PENDING` ou
  `REVOKED`), não grava, não audita e não emite evento.
- **Cascata:** revogar `COPILOT_DATA_USE` revoga `COPILOT_HISTORY` ativo na mesma transação, auditado como duas
  revogações (a segunda com causa `CASCADE`).
- Reconceder depois de revogar reutiliza a linha: `grantedAt` passa a ser a nova data e `revokedAt` é limpo. O
  histórico completo fica na auditoria.

### 4. Efeito da revogação

- `COPILOT_DATA_USE`: vale na hora. A porta de leitura é consultada **a cada** requisição do Copilot (§6), sem cache.
- `COPILOT_HISTORY`: as conversas guardadas são **apagadas de vez** (hard delete), de forma assíncrona e idempotente,
  por um consumidor de `consent.revoked` com inbox (`ProcessedEvents`). O consumidor nasce com o histórico, na Sessão
  8.2; até lá não há histórico para apagar. O usage ledger (sem conteúdo de conversa) é mantido. A UI pede confirmação
  antes de revogar, porque é irreversível.
- Dados importados e fatos financeiros nunca são apagados por revogação (05.12: exclusão histórica é fluxo separado).

### 5. Auditoria

- Tabela `consent_audit`, **só de inserção** (um trigger recusa `UPDATE`), separada dos logs técnicos (05.12
  §Auditoria): consentimento, Workspace, pessoa, finalidade, ação (`GRANTED`/`REVOKED`), versão do termo, causa
  (`USER`/`CASCADE`) e data. Sem IP e sem user-agent (minimização).

### 6. Porta de leitura para outros módulos

- `consents.application.port.ConsentReader.isActive(WorkspaceId, UserId, ConsentPurpose)`: verdadeiro só para `ACTIVE`
  na versão vigente. É a única parte do módulo que outro módulo pode usar (ArchUnit). Hoje ninguém a usa; o pipeline do
  Copilot (Parte C) a chamará depois da autorização do Workspace e antes de qualquer leitura financeira.

### 7. API

- `GET /api/v1/workspaces/{workspaceId}/consents`: lista **todas** as finalidades, as nunca respondidas como `PENDING`.
  Cada item: `purpose`, `state`, `termsVersion` (a concedida, ou nulo), `currentTermsVersion`, `grantedAt`,
  `revokedAt`, `requires` (finalidades pré-requisito).
- `PUT /api/v1/workspaces/{workspaceId}/consents/{purpose}` com `{ "granted": true|false, "termsVersion": "..." }`:
  idempotente, devolve a **lista inteira** (a cascata muda mais de um item). Finalidade desconhecida → 404
  `CONSENT_PURPOSE_NOT_FOUND`; corpo sem `granted` → 400 `VALIDATION_FAILED`. Concorrência → 409 `CONFLICT` (versão otimista + chave única).
- Os dois endpoints entram na suíte de isolamento (`WorkspaceIsolationIT`).

### 8. Eventos

- `consent.granted` e `consent.revoked` v1, pela outbox na mesma transação, só quando algo mudou. `aggregateType`
  `Consent`; payload `{consentId, userId, purpose, termsVersion, cause}`: só ids e códigos, sem PII.
- Sem consumidores nesta parte (o de `consent.revoked` para o histórico é da Sessão 8.2).

### 9. Frontend

- `/profile` redireciona para `/profile/consents`. O submenu de Perfil mostra Consentimentos ativo e os demais itens
  (Dados pessoais, Instituições, Contas, Conexões, Segurança, Preferências) como "Em breve", mais Sair.
- O item "Consentimentos" do `M-More` passa a funcionar.
- Switches do `D-ProfileConsents` com a data de concessão e o alerta de revogação; revogar o histórico (ou o uso de
  dados com histórico ativo) pede confirmação.

## Alternatives

- **Consentimento só por usuário (sem Workspace):** rejeitado; os dados pertencem ao Workspace e um Workspace
  compartilhado exigiria migrar a chave.
- **Incluir `INSIGHTS_GENERATION` agora com efeito real:** rejeitado nesta parte; pausar Insights e Health é uma mudança
  transversal em dois módulos e nas telas. Um switch sem efeito real foi rejeitado por enganar a pessoa.
- **Revogar o histórico anonimizando ou mantendo até a retenção:** rejeitado; anonimizar texto livre com dados
  financeiros não é confiável, e a pessoa espera efeito imediato.
- **Histórico independente do uso de dados:** rejeitado; guardaria conteúdo sem finalidade ativa.
- **Tabela única só de eventos (estado = último evento):** rejeitada; a consulta por requisição do Copilot precisa ser
  uma leitura por chave, e a auditoria separada mantém o histórico.
- **Job para expirar consentimentos:** desnecessário; `EXPIRED` é derivado da versão do termo na leitura.

## Consequences

- O Copilot tem uma barreira explícita, auditável e testável antes de qualquer envio ao LLM.
- Trocar a versão de um termo faz todos os consentimentos daquela finalidade virarem `EXPIRED` (pedir de novo).
- A tela diverge do layout (dois switches em vez de três) até a dívida de `INSIGHTS_GENERATION` ser paga.
- A área de Perfil passa a existir, mas só com Consentimentos.

## Security / Privacy

- Opt-in por padrão; revogação imediata; cascata impede histórico sem finalidade.
- Auditoria só de inserção, sem IP/user-agent; payload de evento só com ids e códigos.
- Logs só com ids e códigos (`consentId`, `workspaceId`, `purpose`).

## Financial Integrity

Nenhum impacto em fatos financeiros. Revogar nunca apaga dados importados.

## Operational Impact

- Migration `V23__create_consents.sql` (`consents`, `consent_audit`).
- Dois tipos de evento novos na outbox, sem fila nesta parte.
- Contrato: dois endpoints e três códigos de erro novos no `openapi.json` (ADR-0010) e no `schema.d.ts`.
- Métrica `shf.consents.changes` com tags `purpose`, `action` e `cause` (sem ids). Sem DLQ nem read model, logo sem runbook.
- Nenhuma dependência nova.

## Related Specs

05.8, 05.12 (Consent, IA, Auditoria), 05.13 §10 e §17, design/specs/03-screens.md (`D-ProfileConsents`, `M-More`),
05-states.md (Permission denied), ADR-0003, ADR-0021 §10.

## Date

2026-11-09
