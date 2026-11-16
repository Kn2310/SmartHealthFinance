# ADR-0039 — Modelo de IA do Copilot (AI Gateway, privacidade, tools, validação, quotas)

## Status

Accepted

## Context

Decisões aprovadas pelo usuário em 2026-11-10 (Sessão 8.1, Parte A): provedor Anthropic com o SDK Java oficial;
Haiku 5.5 para intenção e Sonnet 5.5 para resposta; valores por referência na resposta; regenerar uma vez e depois
modo seguro, com Resilience4j; só comerciante normalizado como texto de origem; quotas conservadoras; histórico por
90 dias; tools como propostas.


O M11 entrega o Financial Copilot (05.8, 05.13 §10) em escopo controlado (mvp.md). A Sessão 8.1 constrói a fundação
(Partes B e C); a Sessão 8.2, a API de conversa, as telas e a avaliação. A Parte 0 (ADR-0038) já deu o consentimento
`COPILOT_DATA_USE`/`COPILOT_HISTORY` e a porta `ConsentReader`. Não existe código de IA, rate limit nem uso do Redis
(o Redis está no compose e nos Testcontainers, só sem uso). Planos e entitlements são do M12, então quotas são
provisórias.

Regra de ouro: **a IA interpreta; o sistema calcula.** Toda decisão abaixo serve a ela.

## Decision

### 1. Provedor, modelos e SDK

- **Provedor:** Anthropic, Claude API direta (1P).
  - Suporta tool use, saída estruturada com esquema (`output_config.format`), `strict: true` em tools, prompt
    caching e PT-BR.
  - Retenção: a API comercial não usa dados para treinamento por padrão, guarda por até 30 dias para abuso e oferece
    zero data retention por acordo. Isso deve ser confirmado no DPA antes de produção.
  - Inferência: `inference_geo` só oferece `global` ou `us`; não há região Brasil. É transferência internacional (LGPD
    art. 33), coberta por cláusulas contratuais no DPA e pelo consentimento do ADR-0038. A PIA (05.12) é pré-requisito
    de produção.
- **Roteamento de modelos** (ids exatos, sem sufixo de data):
  - Intenção: `claude-haiku-5-5` ($0,10 / $0,50 por MTok; até 100K tokens de prompt), esforço `low`, saída
    estruturada com o enum de intenções.
  - Resposta: `claude-sonnet-5-5` ($2 / $10 por MTok; cache read $0,20), esforço `low`, saída estruturada §6.
  - Modelos ficam em configuração (`shf.copilot.models.*`), com a tabela de preços versionada (`pricing-2026-11`).
  - **Adendo (2026-11-10, Parte B):** a página oficial de preços dá o cache read do Sonnet 5.5 a **$0,10/MTok** (5% do
    input), não $0,20. A tabela `pricing-2026-11` usa os valores da página: Haiku 5.5 $0,10 / $0,50 / cache read
    $0,01 / cache write $0,125, com a faixa acima de 100.000 tokens de prompt a $0,50 / $2,50 / $0,05 / $0,625;
    Sonnet 5.5 $2 / $10 / $0,10 / $2,50. A estimativa de custo das Consequences não muda.
- **SDK:** oficial `com.anthropic:anthropic-java` (versão estável mais recente na Parte B; ≥ 3.2.0), só no adapter.
  - **Adendo (2026-11-10, Parte B):** fixado `anthropic-java` 3.7.0 e Resilience4j 2.4.0 (core: circuitbreaker,
    retry, bulkhead, micrometer), aprovados pelo usuário. O SDK traz Jackson 2, Kotlin stdlib e OkHttp, com as
    versões gerenciadas pelo Spring Boot; o projeto segue em Jackson 3 (pacotes diferentes).
  Spring AI foi avaliado e fica de fora (ver Alternatives).
- **Sem** recursos que mantêm estado no provedor: Message Threads, Files API, Managed Agents, memory tool, web search,
  code execution. O contexto vai inteiro em cada chamada.

### 2. Porta `AiGateway` e resiliência

- Porta em `copilot.application.port.AiGateway`, independente do SDK:
  - `IntentResult classify(IntentRequest)`;
  - `AnswerResult answer(AnswerRequest)`.
- Os dois resultados trazem `usage` (tokens de entrada, saída e cache, modelo, latência) e um `outcome` tipado:
  `OK`, `REFUSED`, `TIMEOUT`, `UNAVAILABLE`, `RATE_LIMITED`, `INVALID_OUTPUT`.
- Adapters:
  - `AnthropicAiGateway` (infra), ativo só com `shf.copilot.provider=anthropic` e `ANTHROPIC_API_KEY` vinda de
    variável de ambiente ou secret;
  - `SimulatedAiGateway`, determinístico e padrão no local, nos testes e na CI. Ele monta a resposta a partir do
    próprio contexto e tem modos de falha configuráveis por teste.
- Timeouts:
  - intenção: 5 s;
  - resposta: 25 s;
  - orçamento total do pipeline: 30 s.
  - **Adendo (2026-11-10, Parte C, aprovado pelo usuário):** cada chamada leva o prazo final do orçamento (`AiRequest.deadline`).
    - A cada tentativa, inclusive no retry do Resilience4j, o adapter usa o menor entre o que resta até o prazo e o
      timeout do papel (`RequestOptions` do SDK).
    - Com menos de 1 s restante, a tentativa falha como `TIMEOUT` sem ir à rede e sem retry.
    - O pipeline só chama o modelo com ao menos 3 s restantes; senão, responde em modo seguro.
- Resiliência com **Resilience4j** (dependência nova, aprovada): os módulos core `resilience4j-circuitbreaker`,
  `resilience4j-retry` e `resilience4j-bulkhead`, configurados programaticamente no adapter, sem o starter (que não
  depende da compatibilidade com o Boot 4). Métricas pelo binder Micrometer do Resilience4j.
- Retries:
  - `maxRetries` do SDK = 0;
  - um retry do Resilience4j só em 429/5xx/timeout de conexão, com backoff exponencial e jitter, dentro do orçamento.
- Concorrência: bulkhead no adapter, padrão 16 chamadas simultâneas.
- Circuit breaker no adapter. Abre com 50% de falha numa janela de 20 chamadas (mínimo 5); fica aberto 30 s e depois
  faz meia-abertura com 1 chamada.
- Sem fallback de provedor no MVP (há um só). Com o provedor fora, o circuito aberto ou um timeout, o pipeline responde
  em **modo seguro** (§7.3) com os dados determinísticos já calculados.
- **Falha de IA nunca afeta o núcleo.** O Copilot não escreve em tabela financeira, não tem `@Transactional` de
  escrita sobre dados financeiros e não roda dentro de transação de outro módulo. A chamada ao provedor acontece fora
  de qualquer transação de banco.

### 3. Privacidade e minimização (classificação "AI Context", 05.12)

- **Vai ao LLM:**
  - a pergunta;
  - agregados e valores calculados pelas tools (totais, médias, score e dimensões, faturas, previsões, objetivos,
    simulações);
  - nomes de categoria (do catálogo);
  - nomes de comerciante normalizados, delimitados como dados;
  - meses e datas;
  - rótulos `FACT` / `ESTIMATED` / `SIMULATED`.
- **Nunca vai:**
  - nome da pessoa, e-mail, `userId`, `workspaceId` e qualquer id interno;
  - os 4 últimos dígitos do cartão;
  - número de conta, agência e instituição;
  - descrição crua de transação;
  - apelido de conta ou cartão digitado pela pessoa;
  - nome de objetivo digitado pela pessoa;
  - linhas brutas de importação.
- **Pseudonimização:**
  - contas, cartões e objetivos viram referências locais da requisição (`conta_1` com o tipo, `cartao_1` com a
    bandeira, `objetivo_1` com o tipo);
  - o servidor mantém o mapa só em memória durante a requisição e traduz de volta nos `sources`/`actions`;
  - nada desse mapa é gravado.
- **Adendo (2026-11-12, ADR-0042 §5, aprovado pelo usuário):** comerciante normalizado vindo de transferência (Pix,
  TED, DOC, pela regra do normalizador) pode ser nome de pessoa. Ele vai ao modelo como pseudônimo local
  `destinatario_N` e volta com o nome só na resposta renderizada, como os apelidos. Nenhum nome de terceiro sai do app.
  **Pendente (2026-11-12, aprovado pelo usuário):** a analytics só guarda o nome já normalizado, sem a origem; a
  implementação exige marcar a origem de transferência na projeção (migration e adendo ao ADR-0013) e vai num PR
  próprio, bloqueante de produção. Até lá o Copilot roda só com o adapter simulado (local e CI).
- **Logs, métricas e traces:** nunca levam pergunta, resposta, contexto, valores ou nomes. Só ids internos (em log),
  códigos, contagens, tokens, custo, latência e versões.
- **Treinamento:** opt-out por padrão do provedor (API comercial), registrado no runbook com o item do DPA a conferir.

### 4. Intenções do MVP e tools somente leitura

- Intenções (enum versionado `intents-v1`):
  - `OVERVIEW` (saldo e visão geral);
  - `CASH_FLOW` (gastos e receitas por período);
  - `CATEGORY` e `MERCHANT`;
  - `HEALTH` (score e dimensões);
  - `INSIGHTS`;
  - `CARDS` (fatura, cartão, parcelamentos);
  - `FORECAST` (previsão e próximos eventos);
  - `GOALS`;
  - `GOAL_SIMULATION` (`SIMULATED`);
  - `OUT_OF_SCOPE`;
  - `UNSUPPORTED_ACTION` (pedidos de transferir, pagar ou editar).
- **Tools:** adapters finos em `copilot.infrastructure.tools`, sem SQL novo e sem repositório. Cada uma chama um caso
  de uso de leitura existente, que já faz `requireMember`, e recebe o `workspaceId` do pipeline, nunca do modelo. Por
  intenção:

  | Intenção | Casos de uso usados como tool |
  |---|---|
  | `OVERVIEW` | `GetFinancialOverview` |
  | `CASH_FLOW` | `AnalysisQueries.overview`, `spending`, `income`, `evolution` |
  | `CATEGORY` | `AnalysisQueries.categories` e `category` |
  | `MERCHANT` | `AnalysisQueries.merchants` |
  | `HEALTH` | `HealthQueries.summary` e `evolution`, `GetHealthStatus` |
  | `INSIGHTS` | `InsightQueries.list`, `detail` e `highlights` |
  | `CARDS` | `GetCardsSummary`, `GetInvoice`, `GetFutureInvoices`, `GetInstallmentPlans`, `GetCardUtilization` |
  | `FORECAST` | `ForecastQueries.month`, `upcoming`, `cashFlow`, `nextIncome` e `invoices` |
  | `GOALS` | `ListGoals`, `GetGoal`, `GetGoalProjection` |
  | `GOAL_SIMULATION` | `SimulateGoal`; não grava, ADR-0036 §2 |

- **Seleção determinística:** o modelo de intenção só escolhe a intenção e os parâmetros (período, categoria, mês,
  cenário de simulação) num esquema estrito. O pipeline decide e executa as tools da intenção. O modelo de resposta
  não chama tools (sem loop agêntico no MVP).
- **Fora:**
  - listar ou buscar transações individuais (descrições cruas);
  - importações;
  - recorrências como edição;
  - qualquer escrita (criar objetivo, "Aplicar" plano, marcar insight);
  - comparação com outras pessoas;
  - recomendações de investimento ou crédito;
  - dados fora do app.
- Pré-requisito da Parte C: confirmar que `HealthQueries.summary` e `evolution` (`@Transactional` sem `readOnly`) não
  gravam. Se gravarem, a tool usa a leitura do snapshot.
  - **Adendo (2026-11-10, Parte C, aprovado pelo usuário):** gravam, assim como `ForecastQueries` e `InsightQueries`.
    Quando o snapshot é de um dia anterior, a freshness marca o Workspace para recálculo (`markDirty`: só
    `dirty_since`/`due_at` do job, nenhum dado financeiro). As tools chamam as mesmas consultas das telas: abrir a tela
    já faz a mesma marcação, idempotente. Ler os stores direto quebraria a fronteira e pularia o `requireMember`. O §12
    segue valendo: nenhuma gravação financeira.
  - **Adendo (2026-11-10, Parte C, aprovado pelo usuário):** `ListCategories` (transactions, só leitura, com
    `requireMember`) entra na lista fechada. É o catálogo do sistema, igual para todos os Workspaces e sem dado pessoal:
    dá os nomes pt-BR das categorias à resposta e ao modelo de intenção.
  - **Adendo (2026-11-10, Parte C, aprovado pelo usuário): forma da lista fechada no ArchUnit.** Valem as classes exatas dos casos de uso,
    os records aninhados delas (`GetFinancialOverview.Query`, `GetGoalProjection.Projection`) e dois records de entrada
    que ficam fora de `usecase`: `AnalysisContextLoader.Query` e `GoalSimulator.Scenario` (entrada de `SimulateGoal`).
    Valem também os pacotes `application.dto` e `domain.enums`/`model`/`valueobject` dos módulos lidos, porque são o
    que os casos de uso devolvem. Repositórios, serviços de domínio, infraestrutura, apresentação e outros casos de
    uso continuam proibidos. A tool de insights usa `InsightQueries.list` e não `highlights`, que carrega textos
    prontos.
    - **Adendo (2026-11-11, ADR-0040, aprovado pelo usuário):** a lista passa a incluir os pacotes
      `application.exception` de `cards`, `goals`, `insights` e `transactions`. São só os tipos de "não encontrado"
      que o contexto de entrada traduz para 404 e o pipeline, para `INSUFFICIENT_DATA`. Nenhum caso de uso novo entra.
  - **Adendo (2026-11-10, Parte C, aprovado pelo usuário): catálogo da intenção.** Antes da intenção, o pipeline monta
    o catálogo da pergunta com `ListCategories`, `ListGoals` e `GetCardsSummary`: categorias (código e nome) e alvos
    pseudonimizados (`objetivo_N` com tipo, prazo e status; `cartao_N` com a bandeira). O modelo de intenção pode
    escolher um alvo. Sem alvo válido, um único item do tipo é usado; com vários, `GOALS`/`CARDS` cobrem até 5, e
    `GOAL_SIMULATION` volta `INSUFFICIENT_DATA` pedindo o objetivo e o que mudar.
  - **Adendo (2026-11-10, Parte C, aprovado pelo usuário): insights só com campos estruturados.** Detector,
    importância, período, impacto (dinheiro e pontos) como refs, e o assunto só quando é categoria do catálogo ou
    comerciante normalizado. Nunca `title`, `support`, `changeLine`, `recommendation` nem `evidence`. Pela mesma razão,
    os textos prontos do Health (`summary`, `explanation`, fatores, próximos passos) não vão ao modelo.
- **Fronteiras (ArchUnit, aprovado em 2026-11-10):**
  - o `copilot` depende só de uma **lista fechada** de casos de uso de leitura e simulação (as classes da tabela acima)
    e dos DTOs que eles devolvem, mais `identity` (guard e usuário corrente), `consents.application.port.ConsentReader`
    e `consents.domain.enums`, e `shared`;
  - nunca de `*.domain.repository`, `*.infrastructure`, `*.presentation` nem de outro caso de uso;
  - nenhum acesso a JDBC, JPA ou Spring Data a partir do `copilot`;
  - nenhum módulo depende do `copilot`;
  - as regras que proíbem depender de Insights, Forecast e Goals ganham a exceção do `copilot` (adendos datados nos
    ADR-0022 §1, ADR-0031 §1 e ADR-0034 §8).

### 5. Context Builder e Context Validation

- **Janela padrão:** mês corrente e os 6 meses fechados anteriores, ou o período pedido, limitado ao histórico
  existente.
- **Contexto:** esquema versionado `context-v1` em JSON. Cada valor é `{ref, label, value, unit, kind, period,
  source}`:
  - `kind` ∈ `FACT` / `ESTIMATED` / `SIMULATED`;
  - `source` é a tool e a versão do algoritmo (ex.: `health-v2`, `forecast-v1`);
  - dinheiro vai como string decimal (`"1234.56"`), nunca número de ponto flutuante.
- **Qualidade de dados** (`dataQuality`):
  - meses com dados;
  - completude (faturas faltando, meses parciais);
  - freshness (estados `UP_TO_DATE` / `UPDATING` / `NOT_BUILT` dos read models);
  - fontes (OFX, CSV, manual);
  - presença de estimados e simulados.
- **Validação antes do LLM:**
  - intenção sem dados (`NO_DATA`, período antes do histórico, read model `NOT_BUILT`) → resultado
    `INSUFFICIENT_DATA` com a alternativa possível (05-states "Dados insuficientes"), **sem chamar o LLM**;
  - dados parciais seguem com a sinalização;
  - contexto acima do teto (12K tokens estimados) é cortado por prioridade da intenção, nunca truncado no meio de um
    valor.

### 6. Saída estruturada (`answer-v1`)

```json
{
  "answer": "texto com referências {{v:ref}}",
  "type": "analysis | explanation | comparison | simulation | out_of_scope | insufficient_data",
  "financialData": [{ "ref": "v3", "label": "...", "kind": "FACT" }],
  "explanation": ["passo com {{v:ref}}"],
  "confidence": "high | medium | low",
  "actions": [{ "kind": "OPEN_INVOICE", "target": "cartao_1" }],
  "sources": [{ "tool": "GetInvoice", "version": "cards-v1" }]
}
```

- O esquema vai ao provedor por `output_config.format` e é revalidado no servidor (Structured Output Validation).
- `confidence` é **calculada pelo sistema** a partir de `dataQuality` (regra determinística versionada
  `confidence-v1`). O que o modelo declara é ignorado.
- `actions` são links para telas existentes, de um enum fechado (`OPEN_INVOICE`, `OPEN_INSTALLMENTS`, `OPEN_HEALTH`,
  `OPEN_INSIGHT`, `OPEN_GOAL`, `OPEN_ANALYSIS_CATEGORY`, `OPEN_FORECAST`). O `target` é traduzido pelo mapa de
  pseudônimos; um alvo desconhecido é descartado.

### 7. Financial Claim Validation

1. **Valores por referência:** o modelo escreve `{{v:ref}}` no lugar de números. O sistema substitui pelo valor
   formatado em pt-BR e pelo rótulo (`Estimado`/`Simulado`). Valores derivados (diferenças, variações percentuais,
   somas) são calculados na etapa Deterministic Calculations e entram no contexto como refs próprias.
2. **Validação:**
   - todo `ref` precisa existir no contexto;
   - nenhum dígito pode aparecer fora de uma ref em `answer`/`explanation`, salvo uma lista fechada (anos e dias
     vindos de refs de data, e "1"/"2" como numeral em palavras de ordem);
   - um ref `ESTIMATED`/`SIMULATED` sempre recebe o rótulo;
   - não há tolerância numérica porque o modelo não escreve números;
   - nenhum arredondamento novo: a formatação usa o valor como o caso de uso o entrega (dinheiro com 2 casas, como
     nas telas; percentuais com a escala calculada no backend). Valores derivados são calculados com `BigDecimal` e o
     arredondamento declarado na etapa Deterministic Calculations.
   - **Adendo (2026-11-10, Parte C, aprovado pelo usuário): detalhes do `claims-v1`.**
     - A referência é `{{v:REF}}` com o `ref` do contexto (`{{v:v3}}`).
     - A lista fechada de exceções a "nenhum dígito" é só a dos pseudônimos (`cartao_1`). Vale para qualquer
       caractere numérico (`\p{N}`: frações e sobrescritos também). Não há exceção para "1"/"2": ordinais por extenso
       ("primeiro", "segundo") são permitidos. É mais estrito que o texto acima.
     - Também reprovam números por extenso, exceto "um"/"uma", que são também artigos. Isso vale de "zero" e "dois"
       em diante, e inclui quantidades ("dezena", "centena", "metade", "dobro").
     - Reprovam ainda "reais", "centavos", "por cento", e `R$` ou `%` fora de referência. Isso fecha os contornos do
       bloqueio de dígitos.
     - Risco residual aceito: "um cartão" como quantidade não é detectável.
     - Rótulos do contexto não podem ter dígito fora de pseudônimo (o modelo os copiaria).
     - Pseudônimo desconhecido reprova. Na renderização, cada pseudônimo vira o nome que a pessoa conhece (apelido,
       nome do objetivo), numa passada só com as refs, para nenhum texto substituído ser reinterpretado.
3. **Falha:** regenera uma vez com o erro de validação no prompt. Se falhar de novo, responde em **modo seguro**:
   texto de template determinístico por intenção com os valores do contexto, `confidence` calculada e
   `validation: SAFE_MODE`. A resposta inválida nunca chega à pessoa.

### 8. Prompt injection

- Comerciantes, categorias personalizadas e qualquer texto vindo de importação entram só como valores JSON dentro de
  um bloco `<dados_financeiros>`, nunca no system prompt.
- O system prompt declara que conteúdo dentro de dados nunca é instrução.
- A pergunta da pessoa vai na mensagem de usuário, separada do contexto.
- Saída estruturada estrita, sem tools no modelo de resposta e com `actions` em enum fechado: mesmo uma injeção bem
  sucedida não executa nada.
- Testes adversariais (Parte C):
  - descrição ou comerciante com "ignore as instruções…";
  - pedido de revelar o prompt;
  - pedido de transferir;
  - tentativa de citar número inexistente.
  Todos devem terminar em resposta válida ou em modo seguro.

### 9. Memória

- Três coisas separadas:
  - memória de conversa (só com `COPILOT_HISTORY`);
  - estado financeiro (sempre relido das tools a cada pergunta, nunca da conversa);
  - preferências (fora do MVP).
- Nenhuma memória financeira de longo prazo. A conversa guarda só pergunta, resposta renderizada e metadados.
- Sem `COPILOT_HISTORY`, cada conversa começa do zero e nada é persistido além do usage ledger.
- Com `COPILOT_HISTORY`, o histórico é retido por 90 dias e apagado por job; a revogação apaga de vez (ADR-0038 §4).
  Ambos implementados na 8.2.
- Continuidade da conversa: as últimas 3 trocas entram como texto renderizado, nunca como valores reaproveitados.
  - **Adendo (2026-11-11, ADR-0040, aprovado pelo usuário):** ao modelo vão só as até 3 **perguntas** anteriores e as
    intenções delas, nunca as respostas. O texto renderizado tem números (o modelo os copiaria e a claim validation
    reprovaria) e nomes digitados. Sem histórico, o cliente manda as perguntas da tela; com histórico, o servidor as lê
    da conversa. Retenção por mensagem (90 dias depois de criada) e exclusão pela pessoa: ADR-0040 §6.

### 10. Usage ledger, quotas e rate limit

- **Ledger** (migration na Parte B, tabela `ai_usage`):
  - campos: `id`, `workspace_id`, `user_id`, `request_id`, `request_type` (`INTENT`/`ANSWER`/`REGENERATE`),
    `provider`, `model`, `input_tokens`, `output_tokens`, `cache_read_tokens`, `cost` `numeric(19,6)` em USD da
    tabela de preços versionada, `pricing_version`, `latency_ms`, `outcome`, `created_at`;
  - sem conteúdo;
  - mantido mesmo sem histórico.
- **Quotas provisórias** por Workspace (até o M12), consultadas antes de chamar o provedor, em configuração
  (`shf.copilot.quota.*`):
  - 10 perguntas por dia;
  - 100 por mês;
  - 1M tokens de entrada por mês;
  - estouro → `QUOTA_EXCEEDED` com a data de liberação (05-states "Locked").
  - **Adendo (2026-11-10, Parte B, aprovado pelo usuário):** conta como pergunta a chamada de intenção registrada no
    ledger, **exceto** as que falharam do lado do provedor (`UNAVAILABLE`, `TIMEOUT`, `RATE_LIMITED`): queda do
    provedor não consome a quota. Recusa e saída inválida contam (o provedor respondeu e cobrou). O ledger registra
    tudo, com custo. Limitações aceitas até o M12: a checagem não é atômica com o registro (perguntas simultâneas
    podem passar, limitadas pelo token bucket de 3 por pessoa), e o token do balde é gasto antes da checagem de quota.
- **Rate limit:**
  - Redis, token bucket por `(workspace, user)`: 3 perguntas por minuto (capacidade 3, recarga de 1 a cada 20 s),
    script Lua atômico
    sobre o `StringRedisTemplate` existente, sem dependência nova;
  - chave `shf:copilot:rl:{workspaceId}:{userId}` com TTL;
  - estouro → `RATE_LIMITED`, que vira 429 com `Retry-After` na API da 8.2;
  - Redis fora → falha fechada para o Copilot (`UNAVAILABLE`), sem afetar o resto do app.

### 11. Versionamento

- Prompts em `backend/src/main/resources/copilot/prompts/<nome>-vN.md`, carregados no boot com hash SHA-256.
- Esquemas em `copilot/schemas/` (`intents-v1`, `context-v1`, `answer-v1`).
- Algoritmos versionados como constantes (`confidence-v1`, `claims-v1`).
- Cada resposta registra no ledger e no resultado as versões de prompt, esquemas e algoritmos.
  - **Adendo (2026-11-10, Parte C, aprovado pelo usuário):** migration `V25` acrescenta a `ai_usage` as colunas
    `prompt_version` (ex.: `answer-v1@3fa1c2d9`, com os 8 primeiros caracteres do SHA-256) e `schema_version`
    (`answer-v1`), `not null default ''` (as linhas da Parte B ficam vazias). O hash é calculado com quebras de linha
    normalizadas para LF, igual no Windows e na CI. As versões de algoritmos (`claims-v1`, `confidence-v1`,
    `context-v1`, `intents-v1`) vão no resultado. `context-v1.json` documenta o formato do contexto; um teste confere
    o codec contra ele.
- Mudar texto de prompt exige nova versão do arquivo e passar pela suíte de avaliação da 8.2.

### 12. Permissões

- Só leitura e simulação.
- Nenhuma gravação financeira iniciada pela IA; simular não grava (ADR-0036).
- Ações sugeridas são links (§6). O pedido de executar algo vira `UNSUPPORTED_ACTION` com o link da tela.

### 13. Pipeline (caso de uso da Parte C)

Auth → Workspace (`requireMember`) → consentimento `COPILOT_DATA_USE` → rate limit → quota → Intent (Haiku) →
Context Builder → Financial Queries (tools) → Deterministic Calculations → Context Validation → Prompt →
`AiGateway.answer` (Sonnet) → Structured Output Validation → Financial Claim Validation → renderização → ledger →
resultado.

Códigos de resultado:

| Código | Quando |
|---|---|
| `OK` | resposta válida |
| `SAFE_MODE` | modo seguro (§7.3) |
| `CONSENT_REQUIRED` | sem `COPILOT_DATA_USE` ativo |
| `RATE_LIMITED` | rate limit estourado |
| `QUOTA_EXCEEDED` | quota estourada |
| `INSUFFICIENT_DATA` | dados insuficientes para a intenção |
| `OUT_OF_SCOPE` | pergunta fora do escopo |
| `VALIDATION_FAILED` | só interno: vira `SAFE_MODE` |
| `PROVIDER_UNAVAILABLE` | provedor fora ou circuito aberto, sem dados para o modo seguro |

**Adendo (2026-11-10, Parte C, aprovado pelo usuário): como cada situação vira código.**

- Admissão `UNAVAILABLE` (Copilot desligado ou Redis fora) → `PROVIDER_UNAVAILABLE`.
- Intenção recusada pelo modelo → `OUT_OF_SCOPE`.
- Intenção com falha do provedor, ou fora do esquema → `PROVIDER_UNAVAILABLE`.
- `UNSUPPORTED_ACTION` → `OUT_OF_SCOPE` com `intent = UNSUPPORTED_ACTION` e texto fixo: não há código novo.
  Diferente do §12, a resposta ainda não traz o link da tela: o modelo de intenção não diz qual tela, e escolher uma
  por palavra-chave seria adivinhar. Fica para a 8.2, com a UI do Copilot.
- Outras exceções de uma tool ou do catálogo (ex.: um objetivo apagado entre o catálogo e a tool) propagam como erro
  da requisição, com a intenção já no ledger. É risco aceito até a 8.2 mapear o not-found.
- Tool sem dados (`NO_DATA`, `NOT_BUILT`, alvo faltando) ou parâmetro inválido para o caso de uso
  (`InvalidValueException`) → `INSUFFICIENT_DATA`, sem chamar o modelo de resposta.
- Resposta com falha do provedor (inclusive recusa e timeout) → `SAFE_MODE`, sem regenerar.

**Adendo (2026-11-10, Parte C, aprovado pelo usuário): endpoint de diagnóstico.**

- `POST /api/v1/workspaces/{id}/copilot/diagnostic`, só no perfil `local` (`@Profile("local")`) e fora do OpenAPI
  (`@Hidden`).
- Passa pela mesma autenticação, membership, consentimento, rate limit e quota do caso de uso.
- Serve para o smoke test com a chave real. A API de conversa da 8.2 o substitui.

**Adendo (2026-11-11, ADR-0040, aprovado pelo usuário): API de conversa.**

- O endpoint de diagnóstico é removido; o smoke test com a chave real usa `POST /copilot/questions` (ADR-0040).
- `UNSUPPORTED_ACTION` traz o link da tela quando a pergunta tem contexto de entrada (insight, fatura, parcelamento ou
  objetivo); sem contexto, continua sem link.
- Not-found de uma tool no meio do pipeline vira `INSUFFICIENT_DATA`, sem chamar o modelo de resposta. O alvo do
  contexto de entrada é validado antes do rate limit (404 `COPILOT_CONTEXT_NOT_FOUND`).
- O contexto de entrada substitui a escolha de alvo do modelo de intenção.

## Para a Sessão 8.2

Preenchido ao fim da Parte C (2026-11-10).

### Interface do caso de uso

```java
// copilot.application.usecase.AskCopilot — sem @Transactional; nunca chamar dentro de uma transação
CopilotResult ask(UUID workspaceId, String question);
// lança WorkspaceNotFoundException (não membro → 404) antes de tudo,
// e InvalidValueException("question", REQUIRED | TOO_LONG) depois da autorização (máx. 500 caracteres)

record CopilotResult(UUID requestId, ResultCode code, Intent intent, RenderedAnswer answer,
                     AnswerConfidence confidence, ValidationStatus validation,
                     Duration retryAfter, Instant resetsAt, Map<String, String> versions)

record RenderedAnswer(AnswerType type, String text, List<String> explanation,
                      List<Value> financialData, List<Action> actions)
record Value(String ref, String label, String formatted, ValueKind kind, String period, String source)
record Action(ActionKind kind, String targetId, String targetName)  // targetId = UUID ou código; nulo em telas
                                                                    // do Workspace
```

- `answer` existe com `OK`, `SAFE_MODE`, `INSUFFICIENT_DATA` e `OUT_OF_SCOPE`.
- `intent` é nulo quando a pergunta não chegou à classificação.
- `confidence` só existe com contexto financeiro (`OK`, `SAFE_MODE`).
- `validation`: `VALID`, `REGENERATED`, `SAFE_MODE` ou `NOT_APPLICABLE`.
- `versions`: `intents`, `intentPrompt`, `intentSchema`, `answerPrompt`, `answerSchema`, `context`, `claims`,
  `confidence`.
- O texto já vem renderizado em pt-BR. `targetName` e os rótulos podem trazer nomes digitados pela pessoa (só para
  ela).

### Esquema `answer-v1` final

O de §6, em `backend/src/main/resources/copilot/schemas/answer-v1.json`, com campos obrigatórios e
`additionalProperties: false`. Opcionais usam `anyOf` com `null`, porque a saída estruturada não aceita `maxLength` nem
`maxItems`; os limites ficam na validação do servidor (resposta até 2.000 caracteres, até 6 passos, até 3 ações). O
esquema de intenção é `intents-v1.json`.

### Códigos para a API

| `ResultCode` | HTTP sugerido | Observação |
|---|---|---|
| `OK`, `SAFE_MODE`, `INSUFFICIENT_DATA`, `OUT_OF_SCOPE` | 200 | corpo com `answer` |
| `CONSENT_REQUIRED` | 403 | levar para `/profile/consents` |
| `RATE_LIMITED` | 429 | `Retry-After` = `retryAfter` |
| `QUOTA_EXCEEDED` | 429 | estado "Limite" com `resetsAt` |
| `PROVIDER_UNAVAILABLE` | 503 | provedor fora, circuito aberto, Copilot desligado ou Redis fora |
| (exceção) `WorkspaceNotFoundException` | 404 | `WORKSPACE_NOT_FOUND` |
| (exceção) `InvalidValueException` | 400 | `question` `REQUIRED` / `TOO_LONG` |

`VALIDATION_FAILED` nunca sai: vira `SAFE_MODE`.

### Lacunas para a 8.2

- **API de conversa:** endpoint, contrato OpenAPI, `schema.d.ts`, BFF e suíte de isolamento. Remover o endpoint de
  diagnóstico local.
- **Histórico (`COPILOT_HISTORY`):** tabelas, retenção de 90 dias por job, hard delete na revogação. A continuidade
  manda as últimas 3 trocas como texto. Atenção: o texto renderizado tem nomes digitados (apelido, nome do objetivo).
  Para ir ao modelo, a 8.2 guarda ou reconstrói a versão com pseudônimos, ou não manda os nomes.
- **Suíte de avaliação:** perguntas reais por intenção contra o provedor real, com custo medido. É pré-requisito para
  mudar prompt (§11). Não há uma ainda: só os testes determinísticos com o simulado.
- **Smoke test com a chave real** (`Authorization: Bearer` do SDK 3.7.0), pelo endpoint de diagnóstico local.
- **Telas:** estados de `05-states` (consentimento, limite com data, dados insuficientes, modo seguro com os valores,
  indisponível) e a renderização de `financialData` com o rótulo de estimado ou simulado.
- **Calibração (M12):** quotas, preços e o teto de contexto (12K tokens estimados por caracteres ÷ 4, não pelo
  tokenizador).
- **Limites conhecidos da Parte C:**
  - o simulado escolhe a intenção por palavras-chave e não preenche parâmetros (mês, categoria, alvo, cenário);
  - `UNSUPPORTED_ACTION` sem link de tela, e not-found de tool como erro (adendo do §13);
  - pergunta vazia ou longa demais é rejeitada depois da admissão: gasta um token do balde (ADR-0003, autorizar
    antes de validar). A tela da 8.2 pode validar antes de enviar; **resolvido em 2026-11-12** (adendo ADR-0040 §4);
  - nenhum valor derivado novo é calculado no Copilot: diferenças e variações vêm dos casos de uso (`Variation`),
    que já são determinísticos;
  - DPA e PIA continuam pendentes para produção.

## Alternatives

- **Spring AI 2.x (starter Anthropic):**
  - pró: abstração de provedor pronta e saída estruturada;
  - contra: mais uma camada sobre o mesmo SDK, ciclo de versões próprio acoplado ao Boot, menos controle sobre
    timeout, retry e erro tipado.
  - A porta `AiGateway` já dá a troca de provedor.
- **Claude via Amazon Bedrock ou Vertex AI:** cobrança e residência do parceiro, sem `inference_geo` e sem
  `fallbacks` server-side; cabe como segundo adapter se a residência exigir.
- **OpenAI ou Gemini:**
  - também têm tool calling, JSON estruturado e opt-out de treinamento na API;
  - os preços atuais não foram confirmados em fonte oficial nesta pesquisa;
  - a porta permite um adapter futuro.
- **Um só modelo** (Sonnet para tudo, ou Opus 5.5 para a resposta):
  - Sonnet para tudo: mais simples, mas a classificação fica cerca de 20 vezes mais cara que no Haiku;
  - Opus 5.5 ($4 / $20): o dobro do custo da resposta, para um texto cujos números já vêm prontos.
- **Loop agêntico com tools no modelo:** rejeitado no MVP; o modelo escolheria dados e a seleção deixaria de ser
  determinística e testável.
- **Claim validation por extração de números com tolerância:** frágil com formatos pt-BR ("1,2 mil") e com números
  derivados; as refs eliminam a classe de erro.
- **Circuit breaker próprio + `@Retryable`/`@ConcurrencyLimit` do Spring Framework 7:** sem dependência nova, mas
  um breaker escrito à mão; preterido em favor do Resilience4j, que já vem testado e com métricas.
- **Bucket4j para rate limit:** dependência nova; um script Lua de token bucket resolve.

## Consequences

- O Copilot nunca mostra número que o sistema não calculou; o custo é um prompt mais exigente (refs) e um modo seguro
  menos fluido.
- Custo estimado por pergunta: cerca de US$ 0,02–0,03 (intenção ~2K/100 tokens no Haiku + resposta ~8K/600 no
  Sonnet). 100 perguntas por mês ≈ US$ 2–3 por Workspace no teto, a calibrar no M12.
- Transferência internacional de dados agregados para os EUA, mitigada por minimização, pseudonimização,
  consentimento e DPA.

## Security / Privacy

§3, §8, §9; chave só por variável de ambiente ou secret (`*.example` com placeholder); CI e testes sempre com o
adapter simulado; ArchUnit proíbe o `copilot` de depender de repositório, JDBC ou JPA de qualquer módulo.

## Financial Integrity

Nenhum valor nasce no LLM (§7). As tools leem os mesmos casos de uso das telas; o Copilot não escreve dado financeiro.

## Operational Impact

- Dependências novas (Parte B): `anthropic-java` e os módulos core do Resilience4j (+ binder Micrometer).
- Métricas `shf.copilot.*` (requests por resultado, tokens, custo, latência por etapa, validação, tools, modo seguro,
  circuito), sem conteúdo.
- Runbook `docs/runbooks/copilot.md`: trocar provedor ou modelo, desligar o Copilot (`shf.copilot.enabled=false`),
  ajustar quotas, girar a chave, ler o ledger.

## Related Specs

05.8, 05.12, 05.13 §10/§17, 05.4.1, 05.5 (AI Gateway, Redis), 05.9, 05.11, mvp.md, features.md; ADR-0003, ADR-0006,
ADR-0015, ADR-0019, ADR-0023, ADR-0026, ADR-0032, ADR-0035, ADR-0036, ADR-0038.

## Date

2026-11-10
