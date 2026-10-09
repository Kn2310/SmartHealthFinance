# ADR-0005 — Módulo Transactions (M2)

## Status

Accepted

## Context

O First Financial Loop (Workspace → Account → Transaction → Balance → Home) exige transações antes do saldo (M3).
As specs (05.2, 05.4, 05.6, 05.7, 05.13) definem tipos, status, dinheiro em `NUMERIC(19,4)`, valores positivos
+ type, "transferências não são despesas", "refund rastreável" e `Idempotency-Key` com request hash, mas não
definem:

- a rota da API;
- onde fica o objeto de valor `Money`;
- como representar uma transferência (uma linha ou duas);
- como um `ADJUSTMENT` (ex.: saldo inicial, ADR-0004 §4) indica a direção, já que o valor é sempre positivo;
- as transições de status permitidas;
- o que é editável depois do lançamento;
- como a idempotência é garantida sob concorrência.

## Decision

1. **Rota aninhada:** `/api/v1/workspaces/{workspaceId}/transactions[/{transactionId}]`, como no ADR-0004.
   - Ações de domínio: `POST .../{id}/post`, `.../cancel` e `.../reverse` (05.6).
   - Listagem paginada por offset (`page`, `pageSize` ≤ 100, padrão 50), mais recentes primeiro
     (`occurredOn desc, createdAt desc, id desc`).
   - Filtros: `from`/`to` (inclusivos), `type`, `status`, `accountId` (origem **ou** destino) e `q`
     (trecho da descrição, literal, sem diferenciar maiúsculas).
   - Envelope `{items, page, pageSize, totalItems}`.
2. **`Money` em `shared.domain`.** `BigDecimal` com escala fixa 4 + `Currency`.
   - Precisão acima de 4 casas ou mais de 15 dígitos inteiros é rejeitada, nunca arredondada.
   - Será reutilizado por Cards, Goals e Forecast.
   - Na API: `{"amount": "86.40", "currency": "BRL"}`, sempre string (`shared.presentation.money.MoneyDto`).
3. **Valor positivo + tipo.** `amount > 0` no domínio e por `check` no banco.
   - O valor da transação não pode ter mais casas que a moeda (BRL → 2).
4. **Transferência = uma transação** com `accountId` (origem) e `destinationAccountId` (destino), contas distintas
   do mesmo Workspace.
   - O destino só existe em `TRANSFER`.
   - O saldo (M3) debita a origem e credita o destino. Transferência nunca entra como despesa ou receita.
5. **`ADJUSTMENT` com `adjustmentDirection`** (`INCREASE` | `DECREASE`), obrigatória só nesse tipo.
   - Permite saldo inicial negativo (cheque especial) sem violar "valor positivo".
6. **Reembolso rastreável.**
   - `REFUND` pode apontar para `refundOfTransactionId`.
   - O original precisa ser uma `EXPENSE` `POSTED` do mesmo Workspace.
   - A soma dos reembolsos não anulados (PENDING/POSTED) nunca ultrapassa o valor original.
   - Reembolsos concorrentes do mesmo original são serializados por `SELECT ... FOR UPDATE` no original.
   - O reembolso pode cair em outra conta do Workspace.
7. **Ciclo de vida:** `PENDING → POSTED | CANCELLED`, `POSTED → REVERSED`. `CANCELLED` e `REVERSED` são terminais.
   - Criação aceita apenas `PENDING` ou `POSTED` (padrão `POSTED`).
   - Repetir a mesma transição é idempotente (sem novo `save`). Transição inválida → `409 TRANSACTION_STATUS_CONFLICT`.
   - Lançar (`post`) exige contas ativas. Cancelar/estornar não exige, porque remove movimento.
8. **Fatos imutáveis.** Tipo, contas, valor, moeda e data não mudam depois da criação.
   - Correção = cancelar/estornar + novo lançamento, o que preserva a trilha.
   - Só a `description` é editável (`PUT .../{id}`).
   - Os campos financeiros também são `updatable = false` no mapeamento JPA.
   - Não existe exclusão física (`DELETE` → 405).
9. **Contas.**
   - A conta (e o destino) precisa existir no Workspace autorizado (`404 ACCOUNT_NOT_FOUND`) e estar ativa
     (`409 ACCOUNT_ARCHIVED`).
   - A moeda informada precisa ser a da conta (BRL no MVP).
10. **Isolamento também no banco.**
    - FKs compostas `(account_id, workspace_id)`, `(destination_account_id, workspace_id)` e
      `(refund_of_transaction_id, workspace_id)`.
    - Uma transação nunca referencia conta ou transação de outro Workspace, mesmo com bug na aplicação.
    - Transação de outro Workspace ou inexistente → `404 TRANSACTION_NOT_FOUND` (regra do 404, ADR-0003).
11. **Idempotência de criação.**
    - `Idempotency-Key` é **obrigatório** no `POST` (400 `VALIDATION_FAILED` se ausente).
    - A chave tem escopo por Workspace e é guardada em `transaction_idempotency_keys` com o SHA-256 dos campos de
      negócio normalizados (`TransactionFingerprint`, formato versionado `v1`; ids e timestamps ficam de fora).
    - Mesma chave + mesmo conteúdo → `200` com a transação original. A primeira chamada responde `201`.
    - Mesma chave + outro conteúdo → `422 IDEMPOTENCY_KEY_REUSED`.
    - Requisição rejeitada não consome a chave.
    - Concorrência resolvida no banco: o claim usa `INSERT ... ON CONFLICT DO NOTHING` (mesmo padrão dos
      ADR-0002/0003) **antes** do insert da transação, na mesma transação de banco.
    - A FK da chave para a transação é `DEFERRABLE INITIALLY DEFERRED`.
    - Uma requisição concorrente espera o commit da vencedora e devolve o replay.
12. **Origem (`source`).** Apenas `MANUAL` no M2. `IMPORT` (M4) e Open Finance ampliam o `check` via migration.
    - Estimativas e simulações nunca são transações (05.13 §5).
13. **Categorias adiadas para o M5** (backlog: Analytics → categories). Não há campo de categoria nem
    "Alterar categoria" no M2.
14. **Eventos `transaction.*` adiados** até existir o Transactional Outbox (mesma decisão dos ADR-0003/0004).
    - O fan-out da spec 05.7 (`transaction.posted` → Analytics/Health/Forecast/Insights) entra junto com a outbox.
15. **Direção entre módulos:** Transactions → Accounts → Identity, garantida por ArchUnit.
    - Transactions usa `AccountRepository` (porta de domínio de Accounts) e as exceções de Accounts.
    - Accounts nunca conhece Transactions.
16. **UI (adendo de 2026-10-09; decisões de UX aprovadas, nenhuma decisão arquitetural nova):** telas sobre o BFF
    do ADR-0007.
    - BFF: `GET|POST /api/bff/transactions`, `GET|PUT /api/bff/transactions/{id}`,
      `POST .../{id}/post|cancel|reverse`. O Workspace vem só da sessão; mutações exigem `Origin` do app; ids e
      filtros fora do formato são descartados (página fixa de 50). O `POST` aceita só `INCOME`, `EXPENSE` e
      `ADJUSTMENT` (transferência e reembolso pela UI estão fora do escopo; os existentes são exibidos), valor
      **sempre string** (`number` em JSON é recusado) e repassa a `Idempotency-Key` sem alteração. Só passam
      códigos estáveis: `VALIDATION_FAILED` (campo conhecido + código), `TRANSACTION_NOT_FOUND`,
      `ACCOUNT_NOT_FOUND`, `ACCOUNT_ARCHIVED`, `TRANSACTION_STATUS_CONFLICT`, `CONFLICT` e
      `IDEMPOTENCY_KEY_REUSED`; o resto vira `UNAVAILABLE`. Logs só com status HTTP.
    - Idempotência na UI: uma `Idempotency-Key` (`web:<uuid>`) por abertura do formulário (= uma intenção). Duplo
      clique é travado e, se escapasse, usaria a mesma chave; "tentar de novo" após falha de rede reenvia a mesma
      chave. Só um `IDEMPOTENCY_KEY_REUSED` gera chave nova (a anterior já registrou outro conteúdo), com aviso.
    - Telas: `/transactions` (lista agrupada por dia, filtros de período, tipo, status e conta na URL; a busca por
      texto fica só no estado da tela, nunca na URL), `/transactions/new` e `/transactions/{id}`. O detail drawer
      (D-TxnDetail) virou página, como os modais do ADR-0004 §11. Efetivar, cancelar e estornar seguem o §7;
      cancelar e estornar pedem confirmação. Só a descrição é editável (§8). Padrões: `POSTED` e data de hoje no
      fuso de negócio (ADR-0006 §10).
    - Navegação conforme `design/specs/04-navigation-flows.md` (sem área principal nova): desktop pela Home ("Ver
      todas") e pela conta; mobile por Mais (M-More mínimo com Contas e Transações).
    - Fora desta etapa: as métricas do topo de D-Transactions (Entradas/Saídas/Transferências/Resultado) e
      "Por que meu dinheiro mudou?" (depende de categorias, M5).
    - O sinal exibido (+/−) é apresentação do tipo/direção já decididos pelo backend (mesma tabela de
      `originEffect`); transferência só tem sinal na página de uma das contas. Nenhum valor é somado no frontend.

## Alternatives

- **Transferência como duas transações (débito + crédito):** rejeitada. Duplica o fato, exige vínculo e
  consistência entre as duas linhas e facilita contar a transferência como despesa/receita por engano.
  Pode ser revista se o M4 precisar casar as duas pernas de extratos diferentes.
- **Valor com sinal em vez de tipo/direção:** rejeitada pela spec 05.4 (valores positivos + type).
- **Tipos `ADJUSTMENT_IN`/`ADJUSTMENT_OUT`:** rejeitada. Alteraria a lista de tipos aprovada na spec.
- **Edição completa de transações manuais:** rejeitada. Apaga a trilha de um fato financeiro. Estorno +
  novo lançamento mantém tudo auditável.
- **`Idempotency-Key` opcional:** rejeitada. Em operação financeira crítica, um retry sem chave duplica o
  lançamento.
- **Guardar chave/hash na própria tabela `transactions`:** rejeitada. Mistura preocupação de transporte com o
  fato financeiro e não serve aos imports (M4), que deduplicam por external ID.
- **Tabela de idempotência genérica em `shared`:** adiada. Só Transactions precisa dela hoje. Contributions,
  payments e sync (05.6) podem motivar a extração.
- **Lock otimista no original para reembolsos:** rejeitado. Exigiria escrever no original a cada reembolso
  (`updatedAt`/`version`) só para detectar o conflito.

## Consequences

- Positivas:
  - nenhum retry duplica lançamento, inclusive sob concorrência;
  - o saldo do M3 pode ser derivado deterministicamente de `POSTED` + tipo + direção;
  - o isolamento por Workspace é garantido em três camadas (Application, query e FK composta);
  - os fatos financeiros ficam auditáveis.
- Negativas:
  - o frontend precisa gerar uma `Idempotency-Key` por intenção de lançamento;
  - corrigir valor ou data exige estorno + novo lançamento;
  - `accounts` ganhou a constraint `unique (id, workspace_id)` (aditiva);
  - cada criação faz algumas leituras extras (Workspace, contas, chave).

## Security / Privacy

- Valor e descrição são dados financeiros: os logs registram apenas `transactionId` e `workspaceId` (há teste).
- O texto de busca é tratado como literal (escape de `%`, `_` e `\`) e nunca é concatenado em SQL.
- A `Idempotency-Key` aceita só caracteres não reservados (máx. 100) e não carrega dado pessoal.
- Testes de isolamento cobrem listagem, consulta, criação (inclusive conta alheia no corpo), edição, mudança de
  status e a indistinguibilidade entre transação alheia e inexistente.

## Financial Integrity

- `NUMERIC(19,4)` + moeda, sem float/double (ArchUnit). Precisão excedente é rejeitada, nunca arredondada.
- Restrições `check` no banco independem da aplicação: valor > 0, tipos, status, origem, moeda, regras de
  transferência/ajuste/reembolso e descrição não vazia.
- Sem cascade: conta e Workspace com transações não podem ser apagados.
- Reembolsos nunca excedem o original, com teste de concorrência real (8 requisições simultâneas).

## Operational Impact

- Migration `V5__create_transactions.sql`, somente aditiva.
- Índices pelo padrão de consulta: listagem por Workspace/data, filtro por conta (origem e destino) e reembolsos.
- Sem nova dependência ou infraestrutura. Traces HTTP/JDBC continuam via OpenTelemetry.
- Métricas de negócio (lançamentos por tipo, replays) ficam para quando houver dashboards (M14).

## Related Specs

- `specs/05-system-design/05.2-domain-architecture.md`
- `specs/05-system-design/05.4-data-architecture.md`
- `specs/05-system-design/05.6-api-integration.md`
- `specs/05-system-design/05.7-event-async.md`
- `specs/05-system-design/05.13-consolidated-system-design.md`
- `docs/adr/ADR-0001-modular-monolith-clean-architecture.md`
- `docs/adr/ADR-0003-personal-workspace-and-membership-authorization.md`
- `docs/adr/ADR-0004-accounts-module.md`

## Date

2026-10-06
