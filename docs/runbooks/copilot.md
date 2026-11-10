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

## Simular falhas localmente

Use `COPILOT_SIMULATED_MODE`: `ok`, `refused`, `invalid_output`, `timeout`, `rate_limited` ou `unavailable`. Ele só
vale com `COPILOT_PROVIDER=simulated`.

## Antes de produção (pendente, ADR-0039)

- **Smoke test com a chave real.** O `anthropic-java` 3.7.0, com `.apiKey(...)`, manda a chave em
  `Authorization: Bearer <chave>` e não em `x-api-key`, verificado contra o servidor falso do
  `AnthropicAiProviderTest`. A aceitação pela API real ainda não foi conferida. Com `COPILOT_PROVIDER=anthropic` e a
  chave no `backend/.env`, faça uma chamada e confira `outcome=OK` no ledger. Um 401 aparece no log como `status=401`.
- Com `COPILOT_PROVIDER=anthropic` e sem chave, a API não sobe, mesmo com `COPILOT_ENABLED=false`. Para desligar sem
  chave, volte também a `COPILOT_PROVIDER=simulated`.
- DPA com o provedor: retenção, zero data retention, cláusulas de transferência internacional.
- PIA (05.12).
- Calibrar quotas e preços no M12.
