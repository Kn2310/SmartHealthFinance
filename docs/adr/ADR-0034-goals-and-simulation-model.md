# ADR-0034 — Modelo de Objetivos e Simulação `goals-v1` (M10, Sessão 7.2, parte A)

## Status

Accepted

> Decisões aprovadas em 2026-11-06:
> - aporte e progresso por **destino dedicado**: o progresso é o saldo do destino (fato), os aportes e resgates são as
>   `TRANSFER` `POSTED` do destino, e o destino é tipado (`ACCOUNT` no v1) para receber as caixinhas do Open Finance
>   depois;
> - o objetivo de reserva **espelha o Reserve** do Health: no máximo um por Workspace, progresso igual à soma das contas
>   `SAVINGS` ativas, e nenhum outro objetivo usa conta `SAVINGS`;
> - previsão de conclusão pelo **plano + ritmo real**: o central vem do aporte planejado, o limite pessimista do ritmo
>   real, e sem Forecast pronto a meta mostra só fatos;
> - simulações **efêmeras**; "Aplicar ao objetivo" só altera o plano, com confirmação;
> - um só módulo `goals`, com o `SimulationEngine` puro no domínio;
> - eventos `goal.*` sem consumidor interno no M10;
> - insight acionável (D16) **adiado**;
> - "Marcar como transferência" para lançamentos importados fica como dívida, fora do M10.
>
> Este ADR fixa só o **modelo**: o objetivo, o aporte, o progresso, a reserva, a previsão, a simulação, os eventos e o
> que fica de fora. O contrato da API, a persistência detalhada e as integrações são das partes B e C; a UI, da parte D.

## Context

O `features.md` e o `ux.md` pedem "Meus objetivos, Criar objetivo, Progresso, Simulações". O 05.2 lista `Goal` como
agregado e o `SimulationEngine` como serviço de domínio; o 05.3, os casos de uso `CreateGoal`, `AddGoalContribution`,
`RunSimulation` e `GetGoals`; o 05.6, os recursos `/goals` e `/simulations` e o `Idempotency-Key` em `contributions`.
O 05.13 §5 e o 05.2 mandam manter confirmado, estimado e simulado separados, e o `principles.md` (12) diz que
confirmação, estimativa e simulação nunca se confundem. O `core-user-flows.md` define a simulação como determinística,
com as premissas explicadas e o usuário decidindo.

Ganchos das fases anteriores:

| Origem | O que ficou para o M10 |
|---|---|
| ADR-0017 §7 | próximos passos do Health sem link para Objetivos até eles existirem; prazo e simulação fora do Health |
| ADR-0020 §4 | `BUILD_RESERVE` leva a "Ver contas" (`/accounts`) |
| ADR-0021, ADR-0024 | ação `CREATE_SAVINGS_GOAL` "Criar objetivo de economia" do D1/D2 com `available = false` ("Em breve") |
| ADR-0030 §1, §7 e "Para a Sessão 7.2" | `basis` `SIMULATED` reservado; porta `ForecastReader`; sem Forecast, a meta mostra só fatos |

As specs **não definem**: o que é um aporte, como o progresso é medido, a relação do objetivo de reserva com o Reserve,
o método da previsão de conclusão, as regras da simulação, nem se simulações são guardadas.

Divergências entre o design e o modelo:
- **`09-dados`, Viagem para Lisboa:** os R$ 4.300 guardados não estão em nenhuma conta (os saldos somam R$ 18.420,35
  sem eles). No modelo aprovado, o dinheiro do objetivo precisa estar numa conta de destino.
- **D-CreateGoal, "Já guardado":** campo editável no layout; aqui é o saldo atual do destino, só leitura.
- **D-CreateGoal, "Conta de origem: Banco Norte · Reserva":** usar a reserva como origem de outro objetivo é permitido
  (origem é só o padrão do "Registrar aporte"), mas a reserva não pode ser o **destino** de outro objetivo (§3).
- **D-Goals, "✦ Simulação":** a sugestão é determinística; sem ✦ (ADR-0017 §7, ADR-0021).
- **05.4, tabela `simulations`:** a spec lista a entidade; aqui as simulações são efêmeras (§5) e a tabela não é criada.
- **05.2, contexto Simulation separado:** aqui fica dentro do módulo `goals` (§8).
- **D-GoalDetail, R$ 780 e R$ 900:** valores ilustrativos. O mínimo exato para jul/2027 é R$ 770 (7.700 ÷ 10). Os
  valores sugeridos no Simulation Card são decisão da parte D; o motor aceita qualquer valor.

## Decision

### 1. Objetivo (`Goal`)

Agregado do módulo `goals`, por Workspace:
- `name`: 1 a 60 caracteres, sem espaços nas pontas;
- `type`: `EMERGENCY_RESERVE`, `PURCHASE` ou `CUSTOM`;
- `targetAmount`: `Money` maior que zero, em BRL;
- `deadline`: mês (`YearMonth`), opcional; na criação e na edição, a partir do mês seguinte ao corrente;
- `plannedMonthly`: `Money` maior ou igual a zero (zero = sem plano);
- `sourceAccountId`: opcional; só a conta padrão do "Registrar aporte", sem efeito no progresso;
- `destination`: tipado, `{kind, id}` (§2);
- `origin`: `MANUAL`, `INSIGHT` (com `originInsightId`, para "Criado a partir do insight…") ou `HEALTH`;
- `status`: `ACTIVE`, `PAUSED`, `COMPLETED` ou `ARCHIVED`;
- `trackingStart`: a data de negócio da criação; marca o início do histórico de aportes;
- `completedAt` e `completedAmount`, gravados ao concluir (§2.4);
- `version` (lock otimista) e auditoria (`createdAt`, `updatedAt`).

Transições:
- `ACTIVE ⇄ PAUSED`;
- `ACTIVE | PAUSED → COMPLETED`: ação do usuário, permitida com o alvo atingido ou não;
- `ACTIVE | PAUSED | COMPLETED → ARCHIVED`; `ARCHIVED` é terminal e sai das listas e métricas;
- repetir a mesma transição é idempotente; transição inválida é `409`.

Sem exclusão física: arquivar é a forma de remover.

### 2. Aporte e progresso: destino dedicado

**2.1 Destino.** O objetivo aponta para um destino tipado, `destination = {kind, id}`:
- `ACCOUNT`: uma conta do Workspace. É o único `kind` de objetivos não-reserva no v1;
- `RESERVE`: o conjunto das contas `SAVINGS` ativas, exclusivo do `EMERGENCY_RESERVE` (§3), sem `id`.

Regras do `ACCOUNT`:
- a conta existe no Workspace e está ativa na criação e quando o destino muda;
- não pode ser `SAVINGS` (§3);
- **exclusiva:** uma conta é destino de no máximo um objetivo `ACTIVE` ou `PAUSED`;
- o destino pode mudar na edição; o histórico e o gráfico passam a ler o novo destino, e o evento registra a troca;
- conta arquivada depois: o objetivo continua com o saldo como fato, mas não recebe aportes até trocar o destino.

**2.2 Progresso (fato).**
- `progress` = saldo atual do destino, pela mesma regra de saldo do Overview (ADR-0006 §8: transações `POSTED` até
  hoje). Para `RESERVE`, a soma das contas `SAVINGS` ativas, a mesma do Health (ADR-0017 Reserve).
- Saldo negativo dá progresso zero; o saldo real aparece na evidência.
- `percent` = `progress ÷ targetAmount × 100`, inteiro `HALF_UP`, com teto 99 enquanto `progress < targetAmount` e
  100 quando atingido. Exemplos: 4.300 ÷ 12.000 → 36%; 10.000 ÷ 25.152 → 40%.
- `remaining` = `targetAmount − progress`, nunca negativo.
- `reached` = `progress ≥ targetAmount`. É derivado: um resgate pode desfazê-lo.
- O progresso inclui tudo o que está no saldo, inclusive rendimento real lançado na conta; a previsão e a simulação
  não projetam rendimento.

**2.3 Aportes (fato).** São os movimentos do destino a partir de `trackingStart`:
- **aporte:** `TRANSFER` `POSTED` que entra no destino;
- **resgate:** `TRANSFER` `POSTED` que sai do destino (para conta ou cartão);
- **outros movimentos:** `INCOME`, `EXPENSE`, `REFUND` e `ADJUSTMENT` do destino. Mudam o saldo, logo o progresso, mas
  não são aportes;
- **saldo na criação:** o saldo do destino no dia anterior a `trackingStart`, mostrado como linha inicial
  ("Aporte inicial" do D-GoalDetail);
- para `RESERVE`, uma transferência entre duas contas `SAVINGS` é interna e fica fora dos aportes e resgates;
- `PENDING`, `CANCELLED` e `REVERSED` nunca entram;
- **aporte líquido do mês** = aportes − resgates do mês.

Nada disso é gravado no módulo `goals`: aportes e progresso são lidos das transações e dos saldos, sem cópia e sem
rebuild.

**2.4 "Registrar aporte" (`AddGoalContribution`).** Cria uma `TRANSFER` `POSTED` da origem (padrão: `sourceAccountId`)
para o destino, pelo caso de uso de Transactions, com as regras do ADR-0005 (contas ativas, contas distintas, valor
positivo) e `Idempotency-Key` com hash do pedido. Para `RESERVE`, o usuário escolhe a conta `SAVINGS` de destino.
Transferências criadas por outros caminhos (manual, importação reclassificada) contam do mesmo jeito.

**2.5 Concluir.** Ao concluir, `completedAmount` = progresso naquele momento e `completedAt` = hoje. Um objetivo
`COMPLETED` mostra esse valor congelado (o dinheiro pode ter sido usado na compra) e libera a exclusividade da conta.

**2.6 Sem dupla contagem.**
- Um aporte é uma `TRANSFER`: não é despesa nem receita (ADR-0005 §4), e o saldo total não muda.
- O Health e a Análise não leem os objetivos.
- Uma conta pertence a um único objetivo, e a reserva é uma só fonte (§3).

### 3. Objetivo de reserva e Health

- No máximo um `EMERGENCY_RESERVE` `ACTIVE` ou `PAUSED` por Workspace, com destino `RESERVE`.
- Progresso = soma das contas `SAVINGS` ativas, a mesma definição do Reserve do Health, lida ao vivo pela mesma porta.
  O Health não muda.
- Alvo sugerido na criação = 6 × a média mensal de despesas do Health (ADR-0017 Reserve; no `09-dados`,
  6 × 4.192 = R$ 25.152). O alvo é editável. A referência de 6 meses do Health não muda.
- Nenhum outro objetivo usa uma conta `SAVINGS` como destino: o mesmo dinheiro nunca é reserva e viagem ao mesmo tempo.
- Sem conta `SAVINGS` ativa, o objetivo de reserva pode existir com progresso zero, e o próximo passo orienta a
  adicionar a conta (como o `ADD_RESERVE_ACCOUNT`).

### 4. Previsão de conclusão (`ESTIMATED`)

**Pré-condição:** o Forecast do Workspace está pronto (`READY`). Sem snapshot (`NOT_BUILT`) ou com histórico
insuficiente (`INSUFFICIENT_HISTORY`), não há previsão: o objetivo mostra só os fatos (regra herdada do ADR-0030).
Objetivos `PAUSED`, `COMPLETED` e `ARCHIVED` também não têm previsão.

**Central (plano):**
- `start` = o mês seguinte ao corrente. O mês corrente só conta pelos fatos já ocorridos;
- `n` = ⌈`remaining` ÷ `plannedMonthly`⌉; `completion` = `start` + `n` − 1;
- `plannedMonthly` = 0: sem previsão ("Defina um aporte mensal");
- `reached`: sem previsão, status "Meta atingida".

Confere com o `09-dados`, com hoje = 22/09/2026 e `start` = out/2026:

| Objetivo | Faltam | Plano | n | Conclusão |
|---|---|---|---|---|
| Reserva de emergência | R$ 15.152 | R$ 800 | 19 | abr/2028 |
| Viagem para Lisboa | R$ 7.700 | R$ 700 | 11 | ago/2027 |
| Guardar a diferença do delivery | R$ 1.800 | R$ 300 | 6 | mar/2027 |

**Faixa:**
- ritmo real `p` = mediana do aporte líquido dos meses fechados desde `trackingStart`, no máximo os últimos 3;
- limite otimista = o central;
- limite pessimista = a conclusão com `p` quando `0 < p < plannedMonthly`; "sem conclusão no ritmo real" quando
  `p ≤ 0`; igual ao central quando `p ≥ plannedMonthly` ou quando ainda não há mês fechado.

**Confiança:**
- `HIGH`: 3 meses fechados desde `trackingStart`, `p ≥ plannedMonthly` e a capacidade de aporte central do Forecast
  (`monthlySavingCapacity.monthly`) ≥ a soma dos planos dos objetivos `ACTIVE`;
- `LOW`: `p < plannedMonthly` com pelo menos um mês fechado, ou a capacidade central abaixo da soma dos planos;
- `MEDIUM`: os demais casos.

**Status em relação ao prazo** (pelo central):
- `NO_DEADLINE`: "Sem prazo";
- `ON_TRACK`: `completion ≤ deadline` ("No prazo"; com `monthsEarly` quando antes);
- `LATE`: `completion > deadline` ("{n} mês após o prazo" / "{n} meses após o prazo");
- `REACHED`: "Meta atingida";
- `NO_FORECAST`: sem previsão (pré-condição, pausa ou plano zero).

A previsão sempre aparece com o selo "Estimado", a faixa e a confiança, e com a premissa "aportes mensais iguais, sem
rendimento". É calculada na leitura sobre o snapshot do Forecast; nada dela é gravado.

### 5. Simulação (`SIMULATED`)

`SimulationEngine`: serviço de domínio puro, determinístico e em `BigDecimal`, no módulo `goals`.

**Entradas:** `remaining` (do objetivo, ou `targetAmount − saldo atual do destino` na prévia de criação), `start`
(§4), `deadline` opcional, a renda média mensal e uma variação:
- `MONTHLY_AMOUNT`: um aporte mensal ≥ R$ 0,01;
- `DEADLINE`: um prazo; o resultado traz o aporte necessário = ⌈`remaining` ÷ meses até o prazo⌉ em centavos;
- `CATEGORY_REDUCTION`: uma categoria e um valor mensal de redução, somado ao plano. Não muda nenhum gasto.

**Saídas:**
- `completion`, `contributions` (n), `firstContribution` (= `start`) e `lastContribution` (o resto, quando menor);
- a comparação com o prazo (dentro ou não, e por quantos meses);
- o aporte necessário para o prazo;
- `incomeShare` = aporte mensal ÷ renda média mensal do Analytics (até 6 meses fechados), em % inteiro `HALF_UP`,
  exibido como "≈ X%". Sem renda, não há peso;
- as premissas: "aportes mensais iguais, sem rendimento".

Exemplos (Lisboa, faltam R$ 7.700, `start` out/2026, prazo jul/2027):

| Aporte | n | Conclusão | Prazo |
|---|---|---|---|
| R$ 700 | 11 | ago/2027 | 1 mês após |
| R$ 780 | 10 | jul/2027 | dentro |
| R$ 900 | 9 | jun/2027 | dentro |

Prévia de criação (D-CreateGoal): R$ 1.800 ÷ R$ 300 → 6 aportes, de out/2026 a mar/2027, dentro do prazo; peso na renda
300 ÷ 6.600 → ≈ 5%.

Regras:
- toda saída tem `basis` `SIMULATED` e o rótulo "Simulado";
- a simulação lê o Forecast e o objetivo, e nunca grava nada em Forecast, Health, Insights, Transações ou saldos;
- **efêmera:** não há histórico de simulações;
- **"Aplicar ao objetivo"** substitui `plannedMonthly` e/ou `deadline` do objetivo, só com confirmação explícita, e emite
  `goal.plan-changed`. O dado financeiro não muda: a previsão nova vem do próprio plano.

### 6. Insight acionável (adiado)

O detector do 05.13 §9 ("reduzindo ≈ R$ 200/mês nessa categoria, a meta pode ser atingida ≈ 2 meses antes") fica
fora do M10. No M10:
- `CREATE_SAVINGS_GOAL` do D1/D2 fica disponível e leva à criação com origem `INSIGHT`. A regra de pré-preenchimento é
  fechada na parte C;
- o simulador do objetivo tem a variação `CATEGORY_REDUCTION`, que mostra o efeito sem criar um insight.

Motivo: o detector exige que os Insights leiam os Goals e consumam `goal.*`, com uma versão nova (`insights-v4`) e uma
cadeia de eventos a mais. Fica no backlog para depois do M10.

### 7. Eventos

Pela outbox transacional existente (05.7, `V7`, skill `async-events`), versão 1, idempotentes por `eventId`. A carga leva só ids, tipo, status
e datas, nunca valores:
- `goal.created`: `goalId`, `type`, `origin`;
- `goal.plan-changed`: `goalId` e os campos alterados (nomes, sem valores), inclusive a troca de destino;
- `goal.status-changed`: `goalId`, `from`, `to`.

No M10 nenhum módulo os consome. O Health e os Insights se ligam aos objetivos pelo frontend (adendos ao ADR-0020 e ao
ADR-0024 nas partes C e D), e os eventos ficam prontos para o Copilot (M11) e para um futuro D16.

### 8. Fronteiras

- Um módulo `goals` (o "Planning" e o "Simulation" do 05.2), com o `SimulationEngine` em `goals.domain`. Um módulo
  `simulation` separado seria abstração prematura enquanto só os objetivos simulam.
- O `goals` lê pelas portas existentes:
  - saldos e movimentos (Overview, Transactions);
  - contas (Accounts);
  - `ForecastReader`;
  - renda e despesa médias (Analytics/Health, a fixar na parte B).
- O `goals` cria a transferência pelo caso de uso de Transactions.
- Ninguém depende do `goals`. A parte B fixa as regras de ArchUnit.
- O progresso vem de uma porta própria (`GoalBalanceSource`, nome a confirmar na parte B), que responde o saldo e os
  movimentos de um destino. É o ponto de extensão do §9.

### 9. Open Finance (preparação, sem implementação)

As caixinhas devem chegar de um destes jeitos, conforme a instituição:
- **como conta** (poupança ou pagamento): o destino `ACCOUNT` atual serve;
- **como posição de investimento** (RDB/CDB): entra um `kind` `INVESTMENT_POSITION`, aditivo, com o saldo da posição
  como fato (inclui rendimento). A porta do §8 ganha uma implementação nova; o `Goal`, o `SimulationEngine` e o
  contrato não mudam;
- **só como aplicação e resgate** no extrato da corrente: exige reconhecer esses lançamentos como transferência para
  uma posição (ver Consequences).

Uma conta manual que depois passa a ser sincronizada é problema de Accounts e Connections ("vincular à conta
existente"). O objetivo aponta para o `accountId` e não muda.

### 10. Fora de escopo

- Investimentos, rendimento e juros na previsão e na simulação.
- Metas compartilhadas e limites por plano (M12, 05.4.1).
- Vários objetivos numa mesma conta e alocação parcial de saldo.
- Aportes automáticos ou agendados.
- Periodicidade não mensal.
- Histórico de simulações.
- Insight acionável D16.
- "Marcar como transferência" de lançamentos importados.
- IA ou LLM.

## Alternatives

- **Transferência vinculada ao objetivo** (várias metas por conta, progresso = soma dos vínculos): rejeitada. Resgates
  exigiriam vínculo manual, o progresso pode divergir do saldo real e as caixinhas do Open Finance encaixam pior.
- **Alocação virtual de parte do saldo:** rejeitada. Uma alocação é uma declaração, não um fato, e exigiria regra de
  corte quando a conta é gasta. O "guardado" poderia ser maior que o saldo.
- **Aporte manual registrado:** rejeitado. Não deriva de dados confirmados e diverge do saldo.
- **Tirar as contas de objetivos do Reserve** (`health-v3`): rejeitado agora. Mudaria a nota do Health e criaria
  dependência Health → Goals.
- **Reserva como objetivo comum:** rejeitada. Criaria duas fontes de verdade para a reserva.
- **Previsão pelo ritmo real ou por mín(plano, capacidade):** rejeitadas. O ritmo real é instável e não prevê objetivo
  novo; dividir a capacidade do Workspace entre objetivos é arbitrário.
- **Simulações salvas:** rejeitadas. O MVP não tem uso para o histórico, e o "Aplicar" já fica registrado pelo evento.
- **Módulo `simulation` separado:** rejeitado enquanto só os objetivos simulam.
- **D16 no M10:** adiado (§6).

## Consequences

- Os objetivos ganham progresso que é sempre fato, sem tabela de aportes, sem rebuild e sem risco de dupla contagem.
- Cada objetivo não-reserva exige uma conta dedicada (uma "caixinha"). O "Já guardado" do layout vira só leitura.
- O objetivo de reserva e o Reserve do Health mostram sempre o mesmo valor guardado.
- Uma poupança para viagem precisa ser cadastrada com outro tipo de conta (`PAYMENT` ou `OTHER`), senão entra na
  reserva.
- Sem Forecast pronto, nenhum objetivo mostra previsão (só fatos e a simulação rotulada).
- **Dívida — aportes importados dos dois lados:** se a corrente (OFX) e a poupança (CSV) forem importadas, o mesmo aporte
  vira despesa numa e receita na outra. O progresso continua certo (é o saldo), mas a Análise e o Health contam o fluxo.
  Isso já acontece hoje, independe dos Objetivos e pede um "Marcar como transferência" no padrão do `mark-as-refund`
  (fora do M10).
- **Dívida — caixinha como investimento:** quando o Open Finance trouxer posições, o Reserve do Health vai precisar aceitar
  "posição marcada como reserva" (uma `health-vN`), e o destino `INVESTMENT_POSITION` entra nos Objetivos (§9).

## Security / Privacy

- Objetivos são dados financeiros do Workspace: sempre filtrados por `workspace_id`, inclusive o destino e a origem.
- Nomes de objetivo podem ser pessoais: nunca em logs, métricas ou cargas de evento. Logs, métricas e eventos levam só
  ids, contagens, tipos, status e meses.

## Financial Integrity

- Progresso só de transações `POSTED` e saldos, pela regra existente; nenhuma regra nova de saldo.
- Previsão `ESTIMATED` e simulação `SIMULATED` nunca são somadas a fatos sem rótulo e nunca entram em saldo, fluxo,
  Health, Insights ou Forecast.
- Motor puro, determinístico, versionado (`goals-v1`) e em `BigDecimal`; sem `float`/`double`.
- O aporte é uma `TRANSFER` criada pelas regras de Transactions, com `Idempotency-Key`.

## Operational Impact

- Sem projeção nem snapshot próprios: progresso e previsão são calculados na leitura, sem rebuild nem runbook. A
  freshness da previsão é a do Forecast.
- Uma tabela nova (`goals`, a partir da `V22`); os eventos `goal.*` saem pela outbox existente.
- Métricas e o contrato da API: parte B.

## Related Specs

- `specs/01-product/features.md`, `mvp.md`, `principles.md`, `jobs-to-be-done.md`
- `specs/03-ux/ux.md`, `core-user-flows.md`
- `specs/05-system-design/05.13` §5 e §9; `05.2`, `05.3`, `05.4`, `05.6`, `05.10`, `05.12`
- ADR-0004, 0005, 0006, 0017, 0020, 0021, 0024, 0030, 0031
- `design/specs/02-components.md`, `03-screens.md`, `05-states.md`, `07-design-decisions.md`, `09-dados-de-exemplo.md`

## Date

2026-11-06
