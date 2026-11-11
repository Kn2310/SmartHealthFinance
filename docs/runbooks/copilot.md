# Runbook — Financial Copilot / AI Gateway

Referência: ADR-0039 (modelo de IA), ADR-0038 (consentimentos). Configuração em `shf.copilot.*`
(`backend/src/main/resources/application.yml`); variáveis em `backend/.env.example`.

## Princípios

- **Falha de IA nunca afeta o núcleo financeiro.** O Copilot não grava dado financeiro, não entra na readiness e
  nenhum módulo depende dele (ArchUnit).
- **A chave do provedor só entra por variável de ambiente ou secret** (`ANTHROPIC_API_KEY`). Nunca em arquivo
  versionado, log, issue ou PR. CI e testes usam sempre o provedor simulado.
- Logs, métricas e traces nunca levam pergunta, contexto ou resposta: só ids, códigos, tokens, custo e latência.

## Desligar o Copilot

1. Defina `COPILOT_ENABLED=false` e reinicie a API.
2. Toda admissão passa a responder `UNAVAILABLE`, antes de qualquer chamada ao provedor e sem consumir rate limit.
3. O resto do produto não muda. Para religar, volte a `true`.

## Trocar de provedor ou de modelo

- **Simulado ↔ real:**
  - `COPILOT_PROVIDER=simulated` (padrão) ou `COPILOT_PROVIDER=anthropic`;
  - o real exige `ANTHROPIC_API_KEY`: sem ela, a API **não sobe** e informa só o nome da variável que falta.
- **Modelo:**
  - edite `shf.copilot.models.intent` / `answer`, sempre com ids exatos e sem sufixo de data;
  - inclua o preço do modelo novo em `shf.copilot.pricing.models` e troque `pricing.version`;
  - sem preço, a chamada é registrada com custo zero, com o log `Copilot model without price` e a métrica
    `shf.copilot.ai.unpriced`.
- **Outro provedor:** um novo adapter de `AiProvider` em `copilot.infrastructure.ai`, atrás da mesma porta
  `AiGateway`. Exige adendo ao ADR-0039 (privacidade, DPA, região).

## Girar a chave

1. Crie a nova chave no console do provedor.
2. Atualize o secret (ou o `backend/.env` local) e reinicie a API.
3. Revogue a chave antiga.
4. Se a nova for inválida, as chamadas voltam `UNAVAILABLE` (status 401 no log, sem retry) e o circuito abre. Corrija a
   chave e reinicie.

## Ajustar quotas e rate limit

- Valores provisórios até o M12 em `shf.copilot.limits.*`:
  - 10 perguntas por dia e 100 por mês por Workspace;
  - 1.000.000 tokens de entrada por mês;
  - 3 perguntas por minuto por pessoa (`bucket-capacity: 3`, `refill-interval: 20s`).
- As quotas contam no **usage ledger** (`ai_usage`, Postgres), no fuso de negócio (`shf.overview.zone`).
- O Redis guarda só o token bucket (`shf:copilot:rl:{workspaceId}:{userId}`, com TTL). Apagar a chave zera o balde de
  uma pessoa.
- Redis fora ou lento (timeout de 1 s em `spring.data.redis.timeout`): o Copilot responde `UNAVAILABLE` (falha fechada)
  e o resto do app segue normal.
- Conta como pergunta a chamada de intenção, exceto as que falharam do lado do provedor (`UNAVAILABLE`, `TIMEOUT`,
  `RATE_LIMITED`). Recusa e saída inválida contam.
- Limitações aceitas até o M12 (adendo ao ADR-0039 §10):
  - a checagem de quota não é atômica com o registro: perguntas simultâneas podem passar, limitadas pelo balde de 3
    por pessoa;
  - uma pergunta barrada pela quota já gastou um token do balde.

## Resiliência

- Por chamada:
  - timeout de 5 s na intenção e 25 s na resposta;
  - 1 retry só para 429, 5xx e erro de conexão (nunca timeout de leitura);
  - bulkhead de 16 chamadas simultâneas.
- Circuit breaker `copilot-ai`:
  - abre com 50% de falha numa janela de 20 chamadas (mínimo 5);
  - fica aberto 30 s e depois testa com 1 chamada.
- Métricas do Resilience4j: `resilience4j_circuitbreaker_state{name="copilot-ai"}`, `resilience4j_retry_calls`,
  `resilience4j_bulkhead_available_concurrent_calls`.

## Ler o ledger e o custo

```sql
-- custo e perguntas do mês por Workspace (USD, tabela de preços da versão gravada)
select workspace_id, pricing_version,
       count(*) filter (where request_type = 'INTENT') as perguntas,
       sum(input_tokens + cache_read_tokens + cache_write_tokens) as tokens_entrada,
       sum(output_tokens) as tokens_saida,
       sum(cost) as custo_usd
from ai_usage
where created_at >= date_trunc('month', now() at time zone 'America/Sao_Paulo') at time zone 'America/Sao_Paulo'
group by workspace_id, pricing_version
order by custo_usd desc;
```

Métricas da aplicação:

| Métrica | Tags |
|---|---|
| `shf.copilot.ai.requests` | `role`, `provider`, `outcome` |
| `shf.copilot.ai.latency` | — |
| `shf.copilot.ai.tokens` | `type`, `kind` |
| `shf.copilot.ai.cost.usd` | `type`, `model` |
| `shf.copilot.admission` | `code` |
| `shf.copilot.rate_limit` | `status` |
| `shf.copilot.ai.short_circuited` | — |
| `shf.copilot.ai.bulkhead_full` | — |
| `shf.copilot.ai.unpriced` | — |

## Pipeline (Parte C)

- Caso de uso `AskCopilot` → `CopilotPipeline` (ADR-0039 §13). Cada pergunta grava no ledger a intenção, a resposta e,
  se houve, a regeneração, todas com o mesmo `request_id`, `prompt_version` (`answer-v1@<hash>`) e `schema_version`.
- Resultados: `OK`, `SAFE_MODE`, `INSUFFICIENT_DATA`, `OUT_OF_SCOPE`, `CONSENT_REQUIRED`, `RATE_LIMITED`,
  `QUOTA_EXCEEDED` e `PROVIDER_UNAVAILABLE`.
- **Modo seguro:** a resposta do modelo reprovou duas vezes na validação de claims, o provedor falhou na resposta ou o
  orçamento de 30 s acabou. A pessoa recebe os valores calculados, sem texto do modelo. Um aumento de
  `shf.copilot.validation{status="SAFE_MODE"}` depois de trocar prompt ou modelo indica regressão.
- **Prompts:** `backend/src/main/resources/copilot/prompts/*-vN.md` e `.../schemas/*.json`. Nunca edite um arquivo
  publicado: crie `-v2`, troque o nome em `ClasspathCopilotPrompts` e rode a suíte de avaliação (8.2).

| Métrica | Tags |
|---|---|
| `shf.copilot.pipeline` | `code`, `intent` |
| `shf.copilot.pipeline.latency` | `code` |
| `shf.copilot.validation` | `status` |
| `shf.copilot.validation.violation` | `code` (ex.: `DIGIT_OUTSIDE_REF`, `INTENT_SCHEMA`) |
| `shf.copilot.tool` | `intent`, `status` |
| `shf.copilot.budget_exhausted` | — |

```sql
-- violações não ficam no banco; custo e regenerações por versão de prompt
select prompt_version, request_type, outcome, count(*), sum(cost) as custo_usd
from ai_usage
where created_at >= now() - interval '7 days'
group by prompt_version, request_type, outcome
order by prompt_version, request_type;
```

## Smoke test pela API de conversa

O endpoint de diagnóstico foi removido (ADR-0040 §2). Para o smoke test local, use `POST
/api/v1/workspaces/{workspaceId}/copilot/questions` com o header `Idempotency-Key` e `{"question": "Qual é o meu saldo?"}`.
Exige token de quem é membro e o consentimento `COPILOT_DATA_USE` ativo (Perfil → Consentimentos). `GET
/api/v1/workspaces/{workspaceId}/copilot/usage` mostra o consumo das quotas (só membership).

## Histórico, feedback, retenção e exclusão (Sessão 8.2, ADR-0040)

- O histórico só existe com `COPILOT_HISTORY` ativo. Guarda a pergunta e a resposta renderizada (`copilot_messages`),
  nunca o contexto financeiro. Sem o consentimento, a conversa é efêmera e só o usage ledger e o feedback (sem conteúdo)
  são gravados.
- Tabelas: `copilot_conversations`, `copilot_messages` (FK `on delete cascade`, `expires_at` = criação + 90 dias),
  `copilot_feedback` (sem conteúdo, `expires_at` = 90 dias).
- Nenhum log, métrica ou trace leva pergunta, resposta ou título: só ids e contagens.

| Métrica | Tags |
|---|---|
| `shf.copilot.history.written` | `kind` (`CREATED`, `APPENDED`) |
| `shf.copilot.history.skipped` | `reason` (`NO_CONSENT`, `CONVERSATION_GONE`) |
| `shf.copilot.history.record_failed` | — (a resposta seguiu efêmera) |
| `shf.copilot.history.deleted` | `cause` (`USER`, `REVOCATION`, `RETENTION`), em conversas |
| `shf.copilot.history.expired_messages` / `expired_feedback` | — |
| `shf.copilot.api.feedback` | `rating` |

### Retenção (90 dias)

- Job diário `CopilotRetentionJob`, cron em `shf.copilot.history.retention.cron` (padrão `0 30 3 * * *`, 03:30 no
  fuso do servidor). Apaga, em lotes de 500 com `FOR UPDATE SKIP LOCKED`, as mensagens expiradas, as conversas que
  ficaram vazias e o feedback expirado. Repete até sobrar menos que um lote cheio.
- A API nunca devolve mensagem com `expires_at <= now`, mesmo antes do job: um atraso do job não expõe conteúdo velho.
- O log `Copilot retention purge messages=N conversations=N feedback=N` e as métricas `expired_*` / `deleted{cause=RETENTION}`
  mostram o que cada rodada apagou.
- Para rodar na hora (manutenção), com acesso ao banco, repita até retornar 0:

```sql
-- o que está vencido e ainda não foi apagado
select count(*) filter (where expires_at <= now()) as mensagens_vencidas from copilot_messages;
select count(*) filter (where expires_at <= now()) as feedback_vencido from copilot_feedback;

-- apagar um lote (o mesmo que o job faz)
delete from copilot_messages where id in (
  select id from copilot_messages where expires_at <= now() order by expires_at limit 500 for update skip locked);
delete from copilot_conversations where id in (
  select c.id from copilot_conversations c
  where not exists (select 1 from copilot_messages m where m.conversation_id = c.id) limit 500 for update skip locked);
delete from copilot_feedback where request_id in (
  select request_id from copilot_feedback where expires_at <= now() order by expires_at limit 500 for update skip locked);
```

- Job parado: confira se `shf.copilot.history.retention.cron` não foi alterado e se o scheduler está ativo. A janela de
  revogação aceita no ADR-0040 §6 (revogação entre a conferência e o commit) também é coberta por este job.

### Exclusão

- A pessoa apaga uma conversa (`DELETE .../copilot/conversations/{id}`) ou todas (`DELETE .../copilot/conversations`),
  sempre hard delete (as mensagens saem em cascata). O usage ledger e o feedback ficam (sem conteúdo).
- Revogar `COPILOT_HISTORY` (ou `COPILOT_DATA_USE`, que revoga o histórico em cascata) apaga todas as conversas da pessoa
  no Workspace pelo consumidor abaixo.
- Atendimento de direito de exclusão fora da API (com acesso ao banco), de uma pessoa num Workspace:

```sql
delete from copilot_conversations where workspace_id = :workspace and user_id = :user;
delete from copilot_feedback where workspace_id = :workspace and user_id = :user;
```

### Consumidor de `consent.revoked` e DLQ

- Fila `shf.copilot.history-revocation` (binding `consent.revoked` em `shf.events`), DLQ
  `shf.copilot.history-revocation.dlq`. Inbox `ProcessedEvents` com o consumidor `copilot.history-revocation`: toda
  entrega entra na inbox; só `purpose = COPILOT_HISTORY` apaga (as conversas da pessoa do payload no Workspace do
  envelope). Reentrega do mesmo evento não tem efeito.
- Retry só de falha transitória (banco ou conexão), com backoff exponencial e jitter (`shf.copilot.history-revocation.retry.*`,
  padrão 4 tentativas, 1 s a 30 s). Evento ilegível, de versão ou tipo inesperado, ou sem `purpose`/`userId`, vai
  direto à DLQ.
- **Mensagem na DLQ** (log `Copilot event sent to DLQ messageId=... eventType=... error=...`): o histórico daquela
  pessoa continua guardado até o reprocesso, então trate como pendência de privacidade.
  1. Descubra a causa pelo `error` do log (banco fora, bug) e corrija.
  2. Reprocesse movendo as mensagens da DLQ de volta para a fila (Shovel ou, no console do RabbitMQ, *Move messages*
     da `shf.copilot.history-revocation.dlq` para a exchange padrão com a routing key `shf.copilot.history-revocation`).
     A inbox torna o reprocesso seguro.
  3. Confira: `select count(*) from copilot_conversations where user_id = :user and workspace_id = :workspace` deve dar 0
     se a pessoa não tem `COPILOT_HISTORY` ativo.
- Fila crescendo sem consumo: verifique se o listener subiu (`CopilotEventListener`) e as conexões do RabbitMQ.

## Catálogo de métricas `shf.copilot.*`

Nomes emitidos pelo código (Micrometer; no Prometheus, pontos viram `_` e contadores ganham `_total`). Nenhuma leva
pergunta, resposta ou contexto.

| Métrica | Tipo | Tags |
|---|---|---|
| `shf.copilot.admission` | contador | `code` |
| `shf.copilot.rate_limit` | contador | `status` |
| `shf.copilot.pipeline` | contador | `code`, `intent` |
| `shf.copilot.pipeline.latency` | timer | `code` |
| `shf.copilot.validation` | contador | `status` |
| `shf.copilot.validation.violation` | contador | `code` |
| `shf.copilot.tool` | timer | `intent`, `status` |
| `shf.copilot.budget_exhausted` | contador | — |
| `shf.copilot.ai.requests` | contador | `role`, `provider`, `outcome` |
| `shf.copilot.ai.latency` | timer | `role`, `outcome` |
| `shf.copilot.ai.tokens` | contador | `type`, `kind` |
| `shf.copilot.ai.cost.usd` | contador | `type`, `model` |
| `shf.copilot.ai.unpriced` | contador | `model` |
| `shf.copilot.ai.short_circuited` | contador | `role` |
| `shf.copilot.ai.bulkhead_full` | contador | `role` |
| `shf.copilot.api.responses` | contador | `code` |
| `shf.copilot.api.replays` | contador | `result` (`IN_PROGRESS`, `REPLAYED`, `ALREADY_ANSWERED`, `KEY_REUSED`) |
| `shf.copilot.api.idempotency_unavailable` | contador | — |
| `shf.copilot.api.feedback` | contador | `rating` |
| `shf.copilot.history.written` | contador | `kind` |
| `shf.copilot.history.skipped` | contador | `reason` |
| `shf.copilot.history.record_failed` | contador | — |
| `shf.copilot.history.deleted` | contador | `cause` |
| `shf.copilot.history.expired_messages` | contador | — |
| `shf.copilot.history.expired_feedback` | contador | — |

## Simular falhas localmente

Use `COPILOT_SIMULATED_MODE`: `ok`, `refused`, `invalid_output`, `timeout`, `rate_limited` ou `unavailable`. Ele só
vale com `COPILOT_PROVIDER=simulated`.

## Antes de produção (pendente, ADR-0039)

- **Smoke test com a chave real.** O `anthropic-java` 3.7.0, com `.apiKey(...)`, manda a chave em
  `Authorization: Bearer <chave>` e não em `x-api-key`, verificado contra o servidor falso do
  `AnthropicAiProviderTest`. A aceitação pela API real ainda não foi conferida. Com `COPILOT_PROVIDER=anthropic` e a
  chave no `backend/.env` (descomente a linha `ANTHROPIC_API_KEY`), suba a API no perfil `local`, faça uma pergunta
  pelo endpoint de diagnóstico e confira `outcome=OK` no ledger. Um 401 aparece no log como `status=401`.
- Com `COPILOT_PROVIDER=anthropic` e sem chave, a API não sobe, mesmo com `COPILOT_ENABLED=false`. Para desligar sem
  chave, volte também a `COPILOT_PROVIDER=simulated`.
- DPA com o provedor: retenção, zero data retention, cláusulas de transferência internacional.
- PIA (05.12).
- Calibrar quotas e preços no M12.
