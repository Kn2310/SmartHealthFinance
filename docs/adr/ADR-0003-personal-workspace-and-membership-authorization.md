# ADR-0003 — Workspace pessoal automático e autorização por membership

## Status

Accepted

## Context

As specs (05.2, 05.4, 05.13) definem o Workspace como boundary de isolamento de todo dado financeiro e exigem
testes de isolamento, mas não definem:

- quando e como o primeiro Workspace do usuário é criado;
- quais papéis existem no MVP;
- como outros módulos verificam o acesso a um Workspace;
- o que a API responde quando alguém tenta acessar um Workspace que não é seu.

O First Financial Loop (Workspace → Account → Transaction → Balance → Home) precisa de um Workspace antes de
qualquer conta existir. O provisionamento de usuário está definido no ADR-0002.

## Decision

1. **Workspace pessoal automático.**
   - `POST /api/v1/users/me` cria, na mesma transação, o usuário, um Workspace `PERSONAL` e a membership
     `OWNER`. A resposta inclui `workspaceId`.
   - O provisionamento continua idempotente: chamadas repetidas devolvem o mesmo Workspace.
   - Usuários provisionados antes desta decisão recebem o Workspace ausente no próximo `POST /users/me`
     (resposta 200; o 201 continua significando "usuário criado").
2. **Defaults do MVP.**
   - Nome `"Pessoal"`, editável no futuro.
   - Moeda base `BRL` fixa, garantida por `check` no banco e ampliável por migration.
   - Um Workspace pessoal por usuário. Workspaces compartilhados e múltiplos Workspaces ficam para depois.
3. **Idempotência sob concorrência no banco.**
   - Índice único parcial `workspaces (owner_user_id) where kind = 'PERSONAL'`.
   - Inserção com `INSERT ... ON CONFLICT DO NOTHING`, no mesmo padrão do ADR-0002.
4. **Papéis.** Apenas `OWNER` no MVP. Novos papéis exigem revisão deste ADR.
5. **Autorização sempre via membership.**
   - `workspace_memberships (workspace_id, user_id, role)` é a única fonte de autorização.
   - `workspaces.owner_user_id` existe apenas para a regra "um pessoal por usuário" e nunca é usado para
     autorizar.
   - Casos de uso de outros módulos chamam `WorkspaceAccessGuard.requireMember(userId, workspaceId)`
     (camada Application do módulo Identity) antes de ler ou escrever dados do Workspace.
6. **Regra do 404.**
   - Workspace inexistente e Workspace sem membership do usuário respondem igualmente
     `404 WORKSPACE_NOT_FOUND`.
   - A API nunca revela a existência de Workspaces de outros usuários. O mesmo vale para recursos filhos
     (contas, transações) de Workspaces sem acesso.
7. **Evento `WorkspaceCreated` adiado.**
   - A spec 05.7 exige Transactional Outbox para publicar eventos, e ainda não há outbox nem consumidor.
   - O evento será introduzido junto com a infraestrutura de outbox, de forma idempotente.

## Alternatives

- **Onboarding explícito ("crie seu Workspace"):** rejeitado para o MVP. Adiciona um passo sem valor ao
  First Financial Loop.
- **Criar o Workspace de forma assíncrona (evento `UserProvisioned`):** rejeitado. Exige outbox e deixa uma
  janela em que o usuário existe sem Workspace.
- **Autorizar por `owner_user_id`:** rejeitado. Não evolui para Workspaces compartilhados e criaria dois
  caminhos de autorização.
- **Responder 403 para Workspace alheio:** rejeitado. Revela a existência do recurso (enumeração de IDs).
- **`WorkspaceAccessGuard` como interface:** adiado. Há uma única implementação; extrair a interface é
  trivial quando houver necessidade real (por exemplo, cache).

## Consequences

- Positivas:
  - todo usuário ativo tem um Workspace após o provisionamento;
  - a autorização fica centralizada e testável;
  - a idempotência é garantida pelo banco, inclusive sob concorrência.
- Negativas:
  - `POST /users/me` passa a escrever em três tabelas;
  - o frontend deve usar o `workspaceId` retornado (ou `GET /api/v1/workspaces`) nas chamadas seguintes;
  - cada verificação de acesso faz uma leitura ao banco (sem cache no MVP).

## Security / Privacy

- O isolamento é verificado na camada Application, e não em filtros HTTP (ADR-0001).
- A resposta 404 uniforme evita enumeração de Workspaces.
- Os logs registram apenas IDs (`workspaceId`, `userId`), nunca e-mail ou nome.
- Testes de isolamento cobrem a listagem, a consulta por id e a indistinguibilidade entre Workspace
  inexistente e alheio.

## Financial Integrity

- O Workspace define a moeda base (`BRL`) que os módulos Accounts e Transactions usarão.
- Nenhum dado financeiro pode existir sem um Workspace, e nenhum é acessível sem membership.

## Operational Impact

- Migration `V3__create_workspaces.sql`, somente aditiva e sem backfill.
- Usuários existentes recebem o Workspace sob demanda no próximo `POST /users/me`.
- Nenhuma nova dependência ou infraestrutura.

## Related Specs

- `specs/05-system-design/05.2-domain-architecture.md`
- `specs/05-system-design/05.4-data-architecture.md`
- `specs/05-system-design/05.6-api-integration.md`
- `specs/05-system-design/05.7-event-async.md`
- `specs/05-system-design/05.13-consolidated-system-design.md`
- `docs/adr/ADR-0002-oidc-identity-provider.md`

## Date

2026-09-28
