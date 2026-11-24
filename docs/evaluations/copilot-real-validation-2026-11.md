# Validação do Copilot com a chave real — 2026-11 (Fase 9, Parte E)

Procedimento: `docs/runbooks/copilot-real-validation.md`. Critério: `docs/runbooks/copilot-evaluation.md` §5.

**Só ids, códigos, contagens, custo e latência.** Nunca texto de pergunta, resposta, contexto, `workspace_id` ou chave.
Dados: só fictícios (golden set sintético e seed do staging).

Estado: **pendente**. Os passos com a chave real são do usuário.

## Versões avaliadas

| Item | Valor |
|---|---|
| Commit (`main`) | _a preencher_ |
| `intentPrompt` / `answerPrompt` | _a preencher (`report.json` → `versions`)_ |
| Modelos | `claude-haiku-5-5` (intenção), `claude-sonnet-5-5` (resposta) |
| Tabela de preços | `pricing-2026-11` |
| SDK | `anthropic-java` 3.7.0 |

## 1. Smoke de autenticação

| Data | Run | Caso | Resultado | Observação |
|---|---|---|---|---|
| _a preencher_ | _id do run_ | `overview-total-balance` | _verde / 401 / outro_ | cabeçalho `Authorization: Bearer` aceito? |

## 2. Golden set real

| Métrica | Critério (§5) | Execução 1 | Execução 2 (se perto do limite) |
|---|---|---|---|
| Casos avaliados | 70 | | |
| Acerto geral | ≥ 90% | | |
| Pior intenção (acerto) | sem regressão | | |
| `claimLeaksToThePerson` | **0** | | |
| Modo seguro | ≤ 5% | | |
| Regenerações | ≤ 20% | | |
| `adversarial` falhando | 0 | | |
| p95 `intent` (ms) | — | | |
| p95 `answer` (ms) | ≤ 20.000 | | |
| Tokens (entrada / saída) | — | | |
| Custo total (US$) | ~0,50 | | |
| Run / artefato | — | | |

Falhas por código (`results[].failures`): _a preencher_.

## 3. Staging ponta a ponta

| Passo | Esperado | Código observado | Confere |
|---|---|---|---|
| 0 consentimento (conceder os dois) | `CONSENT_REQUIRED` | | |
| 1 visão geral | `OK` | | |
| 2 fluxo de caixa | `OK` | | |
| 3 categoria | `OK` | | |
| 4 comerciante (pseudônimo resolvido na resposta) | `OK` | | |
| 5 saúde | `OK` | | |
| 6 cartões | `OK` | | |
| 7 previsão | `OK` | | |
| 8 simulação de meta | `OK` | | |
| 9 injeção de instrução | sem número inventado | | |
| 10 ação não suportada | `OUT_OF_SCOPE`, texto fixo, sem link, nada executado | | |
| 11 quota | `QUOTA_EXCEEDED` | | |
| 12 isolamento (Bruno, com consentimento) | `INSUFFICIENT_DATA` | | |
| 13 rate limit (Bruno) | 4ª `RATE_LIMITED` | | |
| 14 revogação | histórico some; pede consentimento | | |
| 15 feature flag | 503 `COPILOT_UNAVAILABLE`; resto normal | | |

| Conferência | Resultado |
|---|---|
| Ledger: perguntas e custo do dia (Marina / Bruno) | |
| Custo do ledger × Console (`shf-staging`) | |
| Painel do Copilot no Grafana | |
| Alertas (nenhum esperado) | |
| Logs da API sem conteúdo, token ou chave | |

## 4. Conclusão e calibração

_A preencher depois das execuções:_

- autenticação do SDK: confirmada / ajuste necessário;
- critério §5: aprovado / reprovado (itens);
- custo observado por pergunta (US$) e por execução do golden set;
- proposta de calibração dos limiares do §5 e dos alertas do ADR-0042 §4, em adendo datado, com aprovação do usuário.
