# ADR-0040 — API de conversa do Copilot (perguntas, histórico, uso e feedback)

## Status

Accepted

## Context

A Sessão 8.1 entregou o pipeline do Copilot (ADR-0039), com o caso de uso interno `AskCopilot.ask(workspaceId,
question)` e um endpoint de diagnóstico só no perfil `local`. Faltam a API pública de conversa, o histórico com
`COPILOT_HISTORY` (ADR-0038 §4, ADR-0039 §9), a retenção de 90 dias, o consumo de `consent.revoked`, a consulta de uso
e o feedback. As telas `D-Copilot`, `D-CopilotChat` e `D-CopilotStates` pedem coisas que o `answer-v1` não tem: tabela
de fatores com total, "Dados usados" com rótulos legíveis, título de conversa e sugestões.

Decisões aprovadas pelo usuário em 2026-11-11 (Sessão 8.2, Parte D).

## Decision

### 1. Resposta completa, sem streaming

- `POST` síncrono, dentro do orçamento de 30 s do pipeline (ADR-0039 §2). Nada de SSE.
- Streaming de tokens está fora de questão: a Financial Claim Validation precisa da resposta inteira antes de mostrar
  qualquer texto (ADR-0039 §7).
- O estado de carregamento mostra mensagens de etapa geradas no cliente, e outra acima de 10 s (05-states, Loading).
- BFF e CSP (ADR-0007) não mudam: o browser fala só com o BFF (`connect-src 'self'`).

### 2. Rotas

Base: `/api/v1/workspaces/{workspaceId}/copilot`. Erros no formato ProblemDetail do projeto.

| Método e rota | Corpo | Resposta |
|---|---|---|
| `POST /questions` | `AskQuestionRequest`; header `Idempotency-Key` obrigatório | `200 CopilotAnswer` |
| `GET /conversations?limit=&before=` | — | `200 ConversationPage` |
| `GET /conversations/{conversationId}` | — | `200 Conversation` |
| `DELETE /conversations/{conversationId}` | — | `204` |
| `DELETE /conversations` | — | `204` (todas as conversas da pessoa no Workspace) |
| `GET /usage` | — | `200 CopilotUsage` |
| `PUT /questions/{requestId}/feedback` | `FeedbackRequest` | `204` |

Todas exigem membership (`WorkspaceAccessGuard`, ADR-0003) e entram na suíte de isolamento. Os recursos são **da
pessoa dentro do Workspace**: conversa, mensagem ou feedback de outra pessoa ou de outro Workspace → 404.

O endpoint de diagnóstico `POST /copilot/diagnostic` (adendo ADR-0039 §13) é removido.

### 3. Envio de pergunta

`AskQuestionRequest`:

```json
{
  "question": "Por que minha fatura está mais alta?",
  "conversationId": "uuid | null",
  "previousQuestions": ["..."],
  "context": { "kind": "INVOICE", "cardId": "uuid", "month": "2026-10" }
}
```

- `question`: 1 a 500 caracteres depois do `strip`.
- `conversationId`: continua uma conversa guardada; só existe com `COPILOT_HISTORY` ativo.
- `previousQuestions`: até 3, cada uma até 500 caracteres; só na conversa efêmera (sem `conversationId`). Mandar os dois
  → 400.
- `context` (opcional), vindo dos pontos "Perguntar ao Copilot":

  | `kind` | Campos | Alvo |
  |---|---|---|
  | `INSIGHT` | `insightId` | insight do Workspace |
  | `INVOICE` | `cardId`, `month` (`YYYY-MM`) | fatura do cartão |
  | `INSTALLMENT_PLAN` | `cardId`, `planId` | parcelamento |
  | `GOAL` | `goalId` | objetivo |

  O backend valida que o alvo existe no Workspace (404 `COPILOT_CONTEXT_NOT_FOUND`) e o usa como alvo da intenção, no
  lugar da escolha do modelo de intenção. A Home não manda contexto.

`CopilotAnswer` (200; `code` ∈ `OK`, `SAFE_MODE`, `INSUFFICIENT_DATA`, `OUT_OF_SCOPE`):

```json
{
  "requestId": "uuid",
  "conversationId": "uuid | null",
  "messageId": "uuid | null",
  "code": "OK",
  "intent": "CARDS",
  "question": "...",
  "answer": {
    "type": "explanation",
    "segments": [{ "kind": "TEXT", "text": "Sua fatura está em " }, { "kind": "VALUE", "ref": "v1" }],
    "explanation": [[{ "kind": "TEXT", "text": "..." }]],
    "values": [{ "ref": "v1", "label": "...", "formatted": "R$ 3.412,80", "kind": "FACT", "period": "2026-10",
                 "source": "cards-v1" }],
    "breakdown": { "title": "...", "rows": [{ "label": "...", "valueRef": "v4", "detail": "..." }],
                   "totalRef": "v7" },
    "actions": [{ "kind": "OPEN_INVOICE", "label": "Ver fatura", "targetId": "uuid", "targetName": "...",
                  "params": { "month": "2026-10" } }],
    "dataUsed": [{ "label": "Fatura out/2026", "partial": true }]
  },
  "confidence": "high",
  "validation": "VALID",
  "notices": ["PARTIAL_PERIOD", "ESTIMATED"],
  "versions": { "answerPrompt": "answer-v1@3fa1c2d9", "...": "..." },
  "createdAt": "2026-11-11T12:00:00Z"
}
```

- **Segmentos tipados, sem HTML:** o texto vem como lista de `TEXT` e `VALUE`; o valor é resolvido por `ref` em
  `values`. A UI renderiza o número em negrito e com o rótulo `Estimado`/`Simulado` sem interpretar marcação.
- **`breakdown`** (nulo quando não se aplica) é **montado pelo sistema** a partir do que a tool já decompõe, nunca pelo
  modelo: fatores da explicação da fatura (D11), waterfall da análise e dimensões da saúde (sem total). Linhas e total
  apontam para refs do contexto. O `answer-v1` não muda.
- **`dataUsed`**: rótulos legíveis montados no backend a partir das fontes e dos períodos do contexto, com `partial`.
- **`actions`**: o enum do ADR-0039 §6; a rota é montada pela UI (como nos Insights, ADR-0021 §2).
- **`notices`**: `PARTIAL_PERIOD`, `ESTIMATED`, `SIMULATED`, `SAFE_MODE`, `UPDATING`, derivados do contexto e da
  validação.
- Sugestões de entrada e de continuação **não** vêm da API: são um catálogo fixo no frontend, por contexto e por
  intenção.

### 4. Erros

| Status | Código | Quando |
|---|---|---|
| 400 | `VALIDATION_FAILED` | pergunta vazia ou longa, `previousQuestions` acima de 3, `conversationId` com `previousQuestions`, contexto mal formado, `Idempotency-Key` ausente ou inválida |
| 403 | `COPILOT_CONSENT_REQUIRED` | sem `COPILOT_DATA_USE` ativo |
| 404 | `WORKSPACE_NOT_FOUND` | não membro |
| 404 | `COPILOT_CONVERSATION_NOT_FOUND` | conversa inexistente, de outra pessoa, expirada ou sem `COPILOT_HISTORY` |
| 404 | `COPILOT_CONTEXT_NOT_FOUND` | alvo do contexto fora do Workspace |
| 409 | `COPILOT_REQUEST_IN_PROGRESS` | replay de uma chave ainda em processamento |
| 409 | `COPILOT_ALREADY_ANSWERED` | replay de pergunta já respondida numa conversa efêmera (§5) |
| 422 | `IDEMPOTENCY_KEY_REUSED` | mesma chave com outro conteúdo |
| 429 | `COPILOT_RATE_LIMITED` | rate limit; header `Retry-After` |
| 429 | `COPILOT_QUOTA_EXCEEDED` | quota; `resetsAt` no corpo |
| 503 | `COPILOT_UNAVAILABLE` | provedor fora, circuito aberto, Copilot desligado ou Redis fora |

Ordem: autenticação → membership → idempotência → consentimento → contexto → rate limit → quota → pipeline. O contexto
é validado antes do rate limit para um alvo inválido não gastar quota. A validação da pergunta continua depois da
admissão (ADR-0039, limites da Parte C); a tela valida antes de enviar.

### 5. Idempotência

- `Idempotency-Key` obrigatório no `POST /questions` (mesmo formato aceito no Cards, ADR-0026).
- Chave no Redis `shf:copilot:idem:{workspaceId}:{userId}:{hash(key)}` por **10 minutos**, com o hash do corpo e o
  estado (`IN_PROGRESS` ou `DONE` com `requestId` e `messageId`). **Sem conteúdo de pergunta ou resposta.**
- É checada antes da admissão: um replay nunca gasta token do balde nem quota.
- Replay:
  - em andamento → 409 `COPILOT_REQUEST_IN_PROGRESS`;
  - outro corpo → 422 `IDEMPOTENCY_KEY_REUSED`;
  - concluído com a mensagem guardada (`COPILOT_HISTORY`) → 200 com a mensagem;
  - concluído sem histórico → 409 `COPILOT_ALREADY_ANSWERED`, sem cobrar de novo. A tela pede para perguntar de novo.
- Resultados de erro (403, 429, 503) liberam a chave, para que a pessoa possa tentar de novo com a mesma chave.
- Redis fora → 503, como o rate limit (falha fechada só para o Copilot).

### 6. Histórico (`COPILOT_HISTORY`)

- **Sem `COPILOT_HISTORY`:** conversa efêmera. Nada além do usage ledger (e do feedback, §8) é gravado; a conversa vive
  só na tela. `conversationId` e `messageId` voltam nulos.
- **Com `COPILOT_HISTORY`:** a primeira pergunta cria a conversa; as seguintes mandam o `conversationId`.
- O que se guarda: a pergunta, o `CopilotAnswer` renderizado (para exibir), o código, a intenção, a confiança, a
  validação e as versões. Nunca o contexto financeiro, as refs cruas ou o mapa de pseudônimos.
- Uma resposta aberta do histórico é uma foto daquele momento: a tela mostra a data, porque os números podem ter mudado.
  O estado financeiro nunca é relido da conversa (ADR-0039 §9).
- **Título:** a primeira pergunta, cortada em 80 caracteres sem quebrar palavra. Determinístico, sem texto do modelo.
- **Retenção:** cada mensagem expira 90 dias depois de criada (`expires_at`). Um job diário apaga as expiradas, e a
  conversa que fica vazia também.
- **Exclusão:** `DELETE` de uma conversa ou de todas, hard delete (direito de exclusão, 05.12).
- **Revogação:** um consumidor de `consent.revoked` com inbox (`ProcessedEvents`) apaga todas as conversas da pessoa no
  Workspace quando o propósito é `COPILOT_HISTORY` (inclusive em cascata). Idempotente.
- A gravação da mensagem confere o consentimento na mesma transação. A janela que sobra (revogação entre a conferência
  e o commit) fica coberta pelo job de retenção: risco aceito.

### 7. Continuidade da conversa

- Ao modelo vão as **até 3 perguntas anteriores** e as intenções delas, como texto da pessoa dentro de um bloco
  delimitado. **Nunca as respostas**: elas têm números (que o modelo copiaria e a claim validation reprovaria) e nomes
  digitados (adendo ADR-0039 §9).
- Com histórico, o servidor lê as perguntas da conversa; sem histórico, o cliente manda `previousQuestions`, que são as
  perguntas da própria pessoa naquela tela.
- O estado financeiro é sempre relido pelas tools.

### 8. Uso e feedback

- `CopilotUsage`:
  `{ "month": {"used", "limit", "resetsAt"}, "day": {"used", "limit", "resetsAt"}, "dataScope": {"from": "YYYY-MM",
  "to": "YYYY-MM"} | null, "provisional": true }`. Os valores vêm do ledger com a mesma regra da quota (ADR-0039 §10).
  Exige só membership: não envia nada ao modelo. `provisional` marca a quota provisória até o M12.
- `FeedbackRequest`: `{ "rating": "HELPFUL | NOT_HELPFUL", "reason": "WRONG_NUMBERS | DID_NOT_ANSWER | CONFUSING |
  OTHER" | null }`. Sem texto livre.
  - Tabela `copilot_feedback`: `request_id`, Workspace, pessoa, avaliação, motivo, intenção, versões, datas e
    `expires_at` (90 dias). Sem conteúdo; vale com ou sem histórico.
  - Idempotente: vale o último envio. `requestId` que não é da pessoa no Workspace → 404 `COPILOT_REQUEST_NOT_FOUND`.

### 9. Mudanças internas no pipeline

- `AskCopilot` passa a receber um comando: pergunta, perguntas anteriores e contexto de entrada.
- O `AnswerRenderer` passa a gerar segmentos, além do texto usado pelo modo seguro e pelo "Copiar".
- `UNSUPPORTED_ACTION` traz o link da tela quando há contexto de entrada; sem contexto, continua sem link (adendo
  ADR-0039 §13).
- Not-found de uma tool no meio do pipeline (alvo apagado entre o catálogo e a tool) vira `INSUFFICIENT_DATA` (adendo
  ADR-0039 §13).

### 10. Persistência

Migration `V26`:

- `copilot_conversations`: `id`, `workspace_id`, `user_id`, `title`, `created_at`, `last_message_at`; índice
  `(workspace_id, user_id, last_message_at desc)`.
- `copilot_messages`: `id`, `conversation_id` (FK com `on delete cascade`), `workspace_id`, `user_id`, `request_id`
  único, `question`, `intent`, `code`, `answer` `jsonb`, `confidence`, `validation`, `versions` `jsonb`,
  `created_at`, `expires_at`; índice em `expires_at`.
- `copilot_feedback`: chave `request_id`; `workspace_id`, `user_id`, `rating`, `reason`, `intent`, `versions` `jsonb`,
  `created_at`, `updated_at`, `expires_at`.
- Nenhuma coluna de dinheiro: os valores ficam formatados dentro da resposta guardada, nunca como dado financeiro.

## Alternatives

- **SSE só com eventos de etapa:** progresso real ("Analisando 3 faturas…"), mas com stream no BFF, timeouts de proxy e
  idempotência mais complexos. Preterido.
- **Continuidade com as trocas pseudonimizadas:** mais fiel ao texto original do ADR-0039 §9, mas guarda conteúdo em
  dobro, e os pseudônimos mudam entre requisições. Preterida.
- **Sem continuidade:** o mais simples, mas "E em agosto?" deixaria de funcionar. Preterida.
- **Cache curto da resposta no Redis para replay:** retry transparente, mas guardaria conteúdo financeiro sem
  `COPILOT_HISTORY`. Rejeitado.
- **Tabela de fatores escolhida pelo modelo (`answer-v2`):** exigiria esquema e prompt novos e a avaliação da Parte F.
  Preterida em favor do quadro montado pelo sistema.
- **Título gerado pelo modelo:** texto fora da claim validation. Rejeitado.
- **Retenção por conversa (90 dias depois da última mensagem):** uma conversa ativa guardaria conteúdo por mais de 90
  dias. Rejeitada.
- **Pergunta pronta enviada pelo ponto de entrada:** gastaria quota sem a pessoa confirmar. Rejeitada.

## Consequences

- O Copilot passa a ter uma API completa sem dependência nova, com o mesmo BFF e a mesma CSP.
- A continuidade é mais pobre (só perguntas), em troca de nenhum número ou nome digitado ir ao modelo.
- Uma queda de rede depois da resposta numa conversa efêmera perde a resposta (a pessoa pergunta de novo).
- O quadro de fatores só existe nas intenções cujas tools já decompõem um total.

## Security / Privacy

- Nada de conteúdo fora do histórico consentido: idempotência e feedback só com ids, hashes e códigos.
- Texto do modelo nunca vira HTML: segmentos tipados.
- Hard delete na revogação, na exclusão e na retenção; usage ledger mantido (sem conteúdo).
- Ids em URL só como identificadores; nunca pergunta ou valor em URL, log, métrica ou trace.

## Financial Integrity

Nenhum valor nasce no modelo nem na API: segmentos, quadro e "Dados usados" usam as refs do contexto calculado pelas
tools (ADR-0039 §7). Nada é gravado em tabela financeira.

## Operational Impact

- Migration `V26`; consumidor novo de `consent.revoked` com fila e DLQ próprias; job diário de retenção.
- Métricas `shf.copilot.api.*` (respostas por código, replays, exclusões, feedback por avaliação), sem conteúdo.
- Runbook `docs/runbooks/copilot.md`: retenção, DLQ do consumidor, exclusão.
- Contrato: rotas e códigos de erro novos no `openapi.json` e no `schema.d.ts`; diagnóstico removido.

## Related Specs

05.8, 05.12 (IA, consentimento, retenção, direitos), 05.4.1, 05.6, 05.7, 05.9, 05.10; design `D-Copilot`,
`D-CopilotChat`, `D-CopilotStates`, `M-Copilot`, `M-CopilotChat`, 05-states; ADR-0003, ADR-0007, ADR-0021, ADR-0026,
ADR-0038, ADR-0039.

## Date

2026-11-11
