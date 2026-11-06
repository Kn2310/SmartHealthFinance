# ADR-0036 — Previsão de conclusão e simulação dos Objetivos (M10, Sessão 7.2, parte C)

## Status

Accepted

> Decisões aprovadas em 2026-11-06:
> - API de simulação **sob `/goals`**: `POST /goals/{goalId}/simulations` (até 5 cenários) e
>   `POST /goals/simulations/preview` (rascunho do D-CreateGoal);
> - médias de despesa (alvo sugerido da reserva) e de renda (peso na renda) pela **porta do Analytics com a janela do
>   Health**, com teste de paridade;
> - `CREATE_SAVINGS_GOAL` **disponível com sugestão**, sem trocar a versão dos Insights;
> - **`GET /goals/{goalId}/projection`** para o gráfico do D-GoalDetail.
>
> O modelo (fórmulas, faixa, confiança, efemeridade) é o do ADR-0034 §4–§5; aqui ficam o contrato, as integrações e os
> detalhes de implementação.

## Context

O ADR-0034 fixou a previsão (Estimado) e a simulação (Simulado) dos objetivos; o ADR-0035, o contrato de `/goals`. O
handoff do M9 (ADR-0030 "Para a Sessão 7.2") oferece a porta `ForecastReader` (capacidade de aporte, estado do
snapshot) e manda mostrar só fatos sem Forecast pronto. O 05.6 lista `/simulations` como recurso; o layout pede a
prévia ao vivo no D-CreateGoal, o Simulation Card no D-GoalDetail, o gráfico real + projeção + prazo e o resumo
"no ritmo" do D-Goals. A Health mostra a média de despesas e a referência de 6 meses; os Insights D1/D2 tinham a ação
"Criar objetivo de economia" como placeholder.

## Decision

### 1. Previsão no `GoalResponse` (campo aditivo `forecast`)

- `{basis: "ESTIMATED", status, reason, completion, contributionsLeft, monthsLate, monthsEarly, range: {optimistic,
  pessimistic, openEnded}, confidence, realPace, freshness}`.
- `status`: `ON_TRACK`, `LATE`, `NO_DEADLINE`, `REACHED` ou `NO_FORECAST`; `reason` só em `NO_FORECAST`:
  `FORECAST_NOT_READY`, `NO_PLAN`, `NOT_ACTIVE` ou `TOO_FAR` (conclusão além de 50 anos).
- Pronto = o snapshot `forecast-v1` existe, sem `INSUFFICIENT_HISTORY` e com `savingCapacity`. Lido só por
  `ForecastReader.current` e `state` (sem calcular nada no Forecast). `freshness`: `NOT_BUILT` sem snapshot,
  `RECALCULATING` com o Workspace marcado ou eventos pendentes, `UP_TO_DATE` nos demais.
- Ritmo real: aporte líquido (aportes − resgates) de cada mês fechado desde o mês do `trackingStart`, mediana dos
  últimos 3 (com 2, a média dos dois em centavos `HALF_EVEN`).
- Fórmulas, faixa e confiança: ADR-0034 §4. A capacidade comparada é `monthlySavingCapacity.monthly.value` (central,
  Estimado) contra a soma dos planos dos objetivos `ACTIVE` do Workspace.
- Resumo da lista ganha `plannedIncomeShare` (planos ÷ renda média, % inteiro `HALF_UP`), `forecastCount` (ativos com
  previsão), `onTrackCount` (no prazo ou sem prazo) e `forecastFreshness`.
- **ArchUnit (adendo ADR-0031 §1):** `goals` pode ler `forecast` só por `ForecastReader` e `forecast.domain`.

### 2. Simulação

`POST /goals/{goalId}/simulations` com `{scenarios: [{monthlyAmount?, deadline?, reduction?: {categoryCode,
amount}}]}`, de 1 a 5 cenários:
- aporte do cenário = (`monthlyAmount` ou o plano do objetivo) + `reduction.amount`;
- só `deadline` (sem aporte nem redução): o aporte necessário para cumprir o prazo (centavos para cima); prazo antes
  do primeiro aporte é `400 PAST`;
- `deadline` ausente usa o prazo do objetivo; `categoryCode` precisa existir no catálogo (`400 INVALID`); a redução só
  soma ao aporte, nenhum gasto muda;
- sobre o que falta hoje (fato), a partir do mês seguinte.

Resposta: `{basis: "SIMULATED", remaining, start, averageIncome, assumptions: ["EQUAL_MONTHLY_CONTRIBUTIONS",
"NO_YIELD"], scenarios: [{monthlyAmount, completion, contributions, firstContribution, lastContribution {month, amount},
deadline {month, within, monthsFromDeadline}, requiredMonthlyForDeadline, incomeShare, tooFar, reduction}]}`.

Nada é gravado nem emitido. "Aplicar ao objetivo" é o `PUT /goals/{goalId}/plan` (ADR-0035), com confirmação na UI.

Divergência registrada: o 05.6 lista `/simulations` no topo; como só os objetivos simulam (ADR-0034 §8), a simulação
fica sob `/goals`. Um simulador de compra sem objetivo, no futuro, pode ganhar o recurso próprio.

### 3. Prévia de criação

`POST /goals/simulations/preview` com `{type, targetAmount?, plannedMonthly, deadline?, destinationAccountId?}`:
- `alreadySaved`: o saldo do destino hoje (fato, nunca negativo); na reserva, a soma das `SAVINGS` ativas;
- `suggestedTarget`: só na reserva, 6 × a despesa média; nulo sem despesa;
- `target`: o informado ou, na reserva sem alvo, o sugerido; `simulation`: o cenário do plano digitado (`SIMULATED`),
  nulo sem alvo.
- O destino só precisa existir no Workspace e estar ativo; as regras de criação (exclusividade, `SAVINGS`) continuam no
  `POST /goals`.

### 4. Médias e "Criar objetivo de economia"

- **Médias:** `goals` lê `AnalyticsReadModel.entries` e `firstActivity` e aplica a janela W do Health (até 6 meses
  fechados até hoje, a partir do mês do primeiro lançamento; o mês corrente entra no último dia dele) com o mesmo
  `FlowLedger` (total ÷ n, 2 casas `HALF_EVEN`). Um teste de paridade compara a janela com a `HealthWindow`. Com o
  09-dados: despesa média abr–ago R$ 4.192 → alvo sugerido R$ 25.152.
- **Insights (adendo ADR-0021, ADR-0024):** D1 e D2 trazem `CREATE_SAVINGS_GOAL` disponível com `suggestedMonthly` =
  excesso arredondado para baixo em R$ 50 (mínimo R$ 50), `suggestedTarget` = 6 ×, `suggestedDeadline` = o 6º mês a
  partir do seguinte e, no D1, `categoryCode`. No 09-dados (alimentação fora +R$ 311): R$ 300, R$ 1.800, mar/2027 — o
  "Guardar a diferença do delivery". Os Insights não dependem de `goals`: a regra é deles.

### 5. Gráfico e Health

- `GET /goals/{goalId}/projection`: `{target, deadline, actual: {basis: "FACT", points}, projected: {basis:
  "ESTIMATED", points}, forecast}`. `actual`: o guardado no fim de cada mês, do mês do início (no máximo 36 meses) até o
  corrente (até hoje). `projected`: do guardado hoje até a conclusão pelo plano, limitado ao alvo; vazio sem previsão.
- **Health (adendo ADR-0020):** `BUILD_RESERVE` aponta para o objetivo de reserva ou para a criação dele; a frase com o
  mês estimado sai da previsão do objetivo. O backend do Health não muda.

## Alternatives

- **`/simulations` no topo:** recurso genérico para um único caso hoje; adiado.
- **Ler o snapshot do Health para as médias:** número idêntico ao do Health, mas exige dependência Goals → Health e a
  renda média só existe com cartão (dimensão Debt) e a despesa some sem conta `SAVINGS`.
- **Ação sem sugestão ou `insights-v4`:** a primeira perde a prévia do layout; a segunda troca de versão por uma
  mudança só de ação.
- **Gráfico montado no frontend:** cálculo financeiro fora do backend (CLAUDE.md).

## Consequences

- Os objetivos ganham previsão rotulada sem nenhum dado novo gravado: tudo é calculado na leitura sobre fatos e o
  snapshot do Forecast. Sem rebuild nem runbook.
- Um Workspace novo (sem 3 meses de histórico) vê só fatos e a simulação; a previsão aparece quando o Forecast fica
  pronto.
- A janela das médias existe em dois lugares (Health e Goals), presa pelo teste de paridade.
- A projeção faz uma consulta de saldo por mês do gráfico (até 36).

## Security / Privacy

- Toda leitura filtra por Workspace; os endpoints novos estão na suíte de isolamento (objetivo alheio = inexistente;
  destino alheio na prévia = conta inexistente).
- Simulações não são gravadas nem logadas com valores; logs só com ids.

## Financial Integrity

- Motor e previsão puros, determinísticos, em `BigDecimal`, versionados como `goals-v1`.
- Fato (`progress`, `actual`, `alreadySaved`), estimativa (`forecast`, `projected`) e simulação (`simulations`,
  `preview.simulation`) têm `basis` próprio e nunca se somam.
- A simulação não altera dado financeiro nem o objetivo; a redução de categoria não toca em gasto.

## Operational Impact

- Sem jobs, filas, tabelas ou runbooks novos. A freshness da previsão é a do Forecast.
- OpenAPI: só adições (campo `forecast`, campos do resumo, três operações).

## Related Specs

- ADR-0017, 0020, 0021, 0024, 0030, 0031, 0034, 0035
- `specs/05-system-design/05.3`, `05.6`, `05.13` §5 e §9
- `design/specs/02-components.md`, `03-screens.md`, `09-dados-de-exemplo.md`

## Date

2026-11-06
