# Runbook — Avaliação do Copilot (golden set e modelo real)

Referência: ADR-0042 §1–§3 (golden set e avaliação), ADR-0039 §7 e §11 (claims e versões de prompt), ADR-0040.
Operação geral do Copilot (métricas, custo, desligar): `docs/runbooks/copilot.md`.

Há duas camadas, com o mesmo conjunto de casos (`backend/src/test/resources/copilot/golden/`, versão `golden-v1`,
80 casos sintéticos; formato em `golden/README.md`):

| Camada | Provedor | Quando roda | Custo |
|---|---|---|---|
| Golden set no CI | simulado (saída roteirizada do caso) | todo `./mvnw test` e `verify`, em todo PR | zero |
| Avaliação com o modelo real | Anthropic | sob demanda: local ou workflow manual | tokens reais (teto padrão US$ 2,00) |

## 1. No CI (automático)

`CopilotGoldenSetTest` roda dentro de `./mvnw -B -ntp verify` (e de `test`), sem job novo, sem Docker e sem chave. Cada
caso passa pelo `AskCopilot` e pelo `CopilotPipeline` reais; o provedor devolve o `scripted` do caso. Confere código,
intenção, validação, números citados, fontes, rótulos, avisos, ações, quadro e o que vai ao modelo. **Um caso que falha
derruba o build**, e o nome do caso aparece na mensagem (`golden case <id> (<grupo>)`).

Rodar só ele: `cd backend && ./mvnw -q test -Dtest='*Golden*'`.

Mudou prompt, esquema, claim validation ou pipeline? O golden set precisa passar (ADR-0039 §11) e, para prompt, a
avaliação real da seção 5 também.

## 2. Localmente, com a sua chave

A chave é lida **só** da variável de ambiente `ANTHROPIC_API_KEY`. Nunca a escreva em arquivo, `.env`, `application.yml`
ou comando versionado. Sem a chave o perfil para com `copilot-eval needs ANTHROPIC_API_KEY in the environment...` e
nunca cai no simulado.

Bash (Linux, macOS, Git Bash):

```bash
cd backend
read -rs -p "ANTHROPIC_API_KEY: " ANTHROPIC_API_KEY && export ANTHROPIC_API_KEY   # digita sem ecoar
./mvnw -B -ntp -Pcopilot-eval test
unset ANTHROPIC_API_KEY
```

PowerShell:

```powershell
cd backend
$env:ANTHROPIC_API_KEY = Read-Host "ANTHROPIC_API_KEY"   # cole a chave; não a escreva no comando
.\mvnw.cmd -B -ntp -Pcopilot-eval test
Remove-Item Env:ANTHROPIC_API_KEY
```

Opções (ambas opcionais):

- `-Dcopilot.eval.cases=` subconjunto, separado por vírgula: ids, `group:<grupo>` ou `intent:<INTENÇÃO>`
  (ex.: `-Dcopilot.eval.cases=group:adversarial,cards-limit-usage`). Item sem nenhum caso é erro. No PowerShell, ponha o
  `-D...` entre aspas.
- `-Dcopilot.eval.maxUsd=` teto de custo em US$ (padrão `2.00`). O teto é conferido antes de cada caso: ao atingi-lo a
  execução para, grava o relatório parcial (`"aborted": true`) e o perfil falha.

O perfil roda **só** `CopilotRealEvaluation` (o nome não casa com os padrões do surefire nem do failsafe, então o
`verify` normal nunca o executa) e pula as ITs. Os 10 casos que dependem de falha roteirizada do provedor (`real.skip`)
são pulados; restam 70. O perfil usa os modelos, timeouts e preços de `application.yml` (`shf.copilot.*`), como em produção.

## 3. Pelo workflow (GitHub Actions)

`.github/workflows/copilot-eval.yml`, só `workflow_dispatch`: nunca roda em push nem em pull request.

1. Crie o secret (uma vez, o dono do repositório): Settings → Secrets and variables → Actions → New repository secret,
   nome `ANTHROPIC_API_KEY`, valor a sua chave. Prefira uma chave dedicada, com limite de gasto no console da Anthropic.
2. Actions → "Copilot evaluation" → Run workflow. Entradas: `cases` (mesma sintaxe da seção 2; vazio = todos) e
   `max_usd` (padrão `2.00`).
3. Sem o secret, o job falha logo no primeiro passo, sem tentar nada. A chave só entra nos dois passos que a usam e
   nunca é impressa.
4. O resultado fica no resumo do job e no artefato `copilot-eval-<run id>` (`report.json` e `report.md`, 30 dias).

## 4. Lendo o relatório

`backend/target/copilot-eval/report.md` (leitura) e `report.json` (comparação entre execuções). **Só ids de caso e códigos**:
nunca texto de pergunta, resposta ou contexto. Pode ser colado em PR e issue.

| Campo | Significa |
|---|---|
| `accuracy`, `accuracyByIntent` | casos sem nenhuma falha ÷ avaliados, geral e pela intenção esperada |
| `claimViolationsByCode` | violações da claim validation (`DIGIT_OUTSIDE_REF`, `NUMBER_WORD`, `UNKNOWN_REF`...) nas respostas do modelo, **inclusive as que a regeneração corrigiu** |
| `quality.claimLeaksToThePerson` | casos em que um número que não vem do contexto chegou ao texto final. Deve ser sempre zero |
| `quality.safeMode`, `safeModeRate` | respostas que caíram em modo seguro; conta como falha de qualidade do caso |
| `quality.regenerated`, `regeneratedRate` | respostas aprovadas só na regeneração (custo e latência a mais) |
| `latencyMs.intent`, `latencyMs.answer` | p50, p95 e máximo por papel (`answer` inclui a regeneração), medidos pelo gateway por chamada |
| `tokens`, `costUsd` | tokens por papel e custo pela `PricingTable` do `application.yml`, total e por caso (`costUsd.byCase`) |
| `versions` | versões de prompts e esquemas medidas (`answerPrompt: answer-v2@<hash>`...): é o que identifica o que foi avaliado |
| `results[]` | por caso: intenção esperada e obtida, código, validação, `failures` (códigos), chamadas e custo |

Códigos de falha: `INTENT_MISMATCH`, `CODE_MISMATCH`, `SAFE_MODE`, `NUMBER_OUTSIDE_VALUES`, `REF_NOT_IN_CONTEXT`,
`SEGMENT_REF_NOT_CITED`, `TEXT_EXCLUDED`, `ACTIONS_MISMATCH`, `ERROR_MISMATCH`, `CODE_VALIDATION_INCONSISTENT`.

Ordem de grandeza do custo: cerca de US$ 0,5 para os 70 casos com os preços de `pricing-2026-11`; o valor real vem do
relatório. O teto de US$ 2,00 deixa folga para regenerações.

## 5. Critério para aprovar uma mudança de prompt

> **PROPOSTA a calibrar** (ADR-0042 não fixa números). Revise depois de algumas execuções reais e registre os valores
> definitivos aqui ou em adendo ao ADR.

Uma mudança de prompt (`intent-vN`/`answer-vN`) ou de esquema só entra se, na avaliação real do golden set inteiro (sem
subconjunto), comparada com a execução da versão atual:

1. o golden set do CI passa (build verde);
2. **zero** `claimLeaksToThePerson` e zero violação que chegue à pessoa (inegociável: ADR-0039 §7);
3. acerto geral **≥ 90%**, e nenhuma intenção com acerto menor que o da versão atual (com poucos casos por intenção, olhe os casos, não só o percentual);
4. modo seguro **≤ 5%** dos casos avaliados;
5. regenerações **≤ 20%** (sinal de prompt que erra o formato) e sem piora relevante contra a versão atual;
6. p95 de `answer` **≤ 20 s** (o mesmo limite do alerta de latência do ADR-0042 §4) e custo total sem alta maior que 25% sem justificativa;
7. nenhum caso `adversarial` falhando.

Procedimento: rode a avaliação **antes** (baseline, versão atual) e **depois** (prompt novo, arquivo novo `-vN`; nunca
edite um prompt versionado, ADR-0039 §11); cole os dois resumos na descrição do PR. A saída do modelo varia entre
execuções: se o resultado ficar perto de um limite, rode de novo antes de decidir. Caso novo ou alterado no golden set
entra no mesmo PR.

## 6. Limites da avaliação (para ler o relatório com cuidado)

- As tools do golden set são **fixtures fixas**: devolvem os mesmos valores qualquer que seja o mês, a categoria ou o
  alvo que o modelo escolheu. A avaliação real mede intenção, código, validação e números citados; **não** mede se o
  modelo escolheu o mês certo (isso está nos testes de unidade do `IntentResolver` e das tools).
- Casos que dependem de falha do provedor (`real.skip`) só valem no CI.
- O teto de custo é conferido por caso: o gasto pode passar dele em até as chamadas de um caso (no máximo 3).
- Dados sintéticos e perguntas curtas: o golden set detecta regressão, não substitui o acompanhamento do SLO em produção
  (`docs/runbooks/copilot.md`).

## 7. Problemas comuns

| Sintoma | Causa | O que fazer |
|---|---|---|
| `copilot-eval needs ANTHROPIC_API_KEY in the environment` | variável ausente ou vazia | defina a variável (seção 2) ou o secret (seção 3) |
| `model(s) without a price in the pricing table` | modelo novo sem preço em `shf.copilot.pricing.models` | atualize preços e versão (`copilot.md`, "como atualizar") e rode de novo |
| `cost ceiling US$ ... reached` | teto atingido | confira o relatório parcial; suba `maxUsd` conscientemente ou rode um subconjunto |
| muitos `CODE_MISMATCH` com `PROVIDER_UNAVAILABLE` | chave inválida, sem saldo ou provedor fora | confira a chave e o console da Anthropic; os 401/429/5xx não aparecem no relatório além do código |
| `no golden case matches '<item>'` | id ou grupo digitado errado | use id, `group:<grupo>` ou `intent:<INTENÇÃO>` |
