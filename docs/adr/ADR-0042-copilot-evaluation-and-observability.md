# ADR-0042 — Avaliação, observabilidade e revisão de segurança do Copilot

## Status

Accepted

## Context

O ADR-0039 §11 exige uma suíte de avaliação antes de mudar prompts, e a Sessão 8.2 trocou os prompts para
`intent-v2`/`answer-v2` (ADR-0040) sem avaliação contra o modelo real. A 05.10 pede, para IA: esquema de saída,
tools, claim validation, testes de alucinação, prompt injection, avaliação e provedor falso. A 05.9 pede métricas de
IA e o SLO "Copilot success ≥ 98%", com alertas acionáveis. O repositório não tem Prometheus nem Grafana: só
`/actuator/prometheus` e o OTLP opcional.

Decisões aprovadas pelo usuário em 2026-11-12 (Sessão 8.2, Parte F).

## Decision

### 1. Golden set versionado

- Casos em `backend/src/test/resources/copilot/golden/` (JSON, um arquivo por grupo), versão `golden-v1`. Cada caso:
  id, intenção esperada, pergunta, contexto de entrada opcional, fixture de dados (o que as tools devolvem),
  saída roteirizada do modelo para o CI e o que se espera: código de resultado, refs/valores citados, fontes, rótulos
  `FACT`/`ESTIMATED`/`SIMULATED`, avisos e recusas.
- Cobertura mínima: as 10 intenções e `OUT_OF_SCOPE`/`UNSUPPORTED_ACTION`; os exemplos de `09-dados-de-exemplo`
  (fatura de R$ 3.412,80 com os fatores que somam R$ 520,60, entre outros); casos adversariais (injection em nome de
  comerciante, pedido de revelar o prompt, pedido de transferir ou gravar, número inventado, pergunta fora do escopo,
  alvo de outro Workspace); dados insuficientes e modo seguro.
- Os dados são sintéticos: nenhum dado real.

### 2. No CI (adapter simulado)

- `CopilotGoldenSetIT` roda no `./mvnw verify`, sem job novo. Para cada caso, o adapter simulado devolve a saída
  roteirizada e o teste confere pipeline, tools, claim validation, rótulos, avisos e recusas contra o esperado.
- Um caso que falha derruba o build. Mudar prompt ou esquema exige passar o golden set (ADR-0039 §11).

### 3. Fora do CI (modelo real)

- Perfil Maven `copilot-eval` roda o mesmo golden set com o adapter Anthropic, lendo `ANTHROPIC_API_KEY` só do
  ambiente. Sem a chave, o perfil para com uma mensagem clara; nunca cai no simulado em silêncio.
- No modelo real a saída roteirizada é ignorada; o caso confere a intenção, o código, a validação e os números
  citados (que só podem vir do contexto).
- Relatório em `backend/target/copilot-eval/` (JSON e Markdown): acerto por intenção, violações de claim por código,
  modo seguro, latência p50/p95 por papel, tokens e custo pela tabela de preços. **Sem texto de pergunta ou de
  resposta** (só ids de caso e códigos).
- Workflow `copilot-eval.yml` só com `workflow_dispatch`, usando o secret `ANTHROPIC_API_KEY` que o usuário cria, e o
  relatório como artefato. Nunca roda em push ou PR.

### 4. Observabilidade

- Métricas existentes `shf.copilot.*` (catálogo no runbook), só com tags de baixa cardinalidade; nunca `workspaceId`
  ou `userId` em métrica. Histogramas de percentis ligados para `shf.copilot.pipeline.latency` e
  `shf.copilot.ai.latency`.
- Custo por Workspace e por dia vem do ledger `ai_usage`, com consultas prontas no runbook.
- Painel Grafana (JSON) e regras de alerta Prometheus versionados em `infrastructure/observability/copilot/`, sem
  subir nada no compose. Um teste confere que são válidos e que só citam métricas que o código emite.
- Alertas propostos (valores iniciais, a calibrar com tráfego real):

  | Alerta | Condição | Prioridade |
  |---|---|---|
  | Sucesso do Copilot abaixo do SLO | (`OK`+`SAFE_MODE`+`INSUFFICIENT_DATA`+`OUT_OF_SCOPE`) ÷ respostas < 98% em 1 h | P2 |
  | Provedor indisponível | `PROVIDER_UNAVAILABLE` > 5% em 15 min ou circuito aberto | P2 |
  | Modo seguro alto | `SAFE_MODE` > 10% em 1 h | P3 |
  | Violação de claim alta | violações ÷ respostas do modelo > 20% em 1 h | P3 |
  | Latência | p95 do pipeline > 20 s em 15 min | P3 |
  | 429 alto | `RATE_LIMITED`+`QUOTA_EXCEEDED` > 20% em 1 h | P4 |
  | Custo diário | custo do dia > US$ 20 (soma de `shf.copilot.ai.cost.usd`) | P3 |
  | Preço desconhecido | `shf.copilot.ai.unpriced` > 0 | P3 |
  | DLQ da revogação | mensagens na DLQ `shf.copilot.history-revocation` > 0 | P2 (privacidade) |

### 5. Revisão de segurança e privacidade

- Rodar a revisão de segurança sobre o módulo `copilot` e o frontend do Copilot, conferindo minimização de dados,
  segredos, injection, isolamento, retenção e exclusão do histórico e o comportamento na revogação. Achados corrigidos
  ou registrados no backlog com prioridade.

## Alternatives

- Job separado no CI para o golden set: mais tempo de CI sem ganho de cobertura. Preterido.
- Só script local para o modelo real: sem histórico de execuções. Preterido em favor de script + workflow manual.
- Prometheus e Grafana no compose: duas imagens novas e manutenção. Preterido.
- `workspaceId` como tag de métrica: cardinalidade e id em métrica. Rejeitado.

## Consequences

- Toda mudança de prompt, esquema ou pipeline passa pelo golden set no CI; a qualidade real é medida sob demanda e com
  custo conhecido.
- Painel e alertas existem como código, prontos para o ambiente do M13/M14.

## Security / Privacy

Golden set sintético; relatório sem conteúdo de pergunta ou resposta; chave só no ambiente ou no secret; workflow
manual; nenhum id em métrica.

## Financial Integrity

O golden set confere que todo número citado vem do contexto calculado (ADR-0039 §7).

## Operational Impact

Perfil Maven e workflow manual novos; arquivos de painel e alertas; runbook do Copilot atualizado (avaliação,
alertas, custo por Workspace). Sem dependência de runtime nova.

## Related Specs

05.8, 05.9, 05.10, 05.12, 05.14; design/specs/09-dados-de-exemplo.md; ADR-0010, ADR-0011, ADR-0039, ADR-0040,
ADR-0041.

## Date

2026-11-12
