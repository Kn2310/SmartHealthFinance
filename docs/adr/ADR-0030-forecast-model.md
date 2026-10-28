# ADR-0030 — Modelo de Forecast `forecast-v1` (M9, Sessão 7.1, parte A)

## Status

Accepted

> Decisões aprovadas em 2026-10-28:
> - projeção do mês = realizado + recorrentes pendentes + padrão do restante do mês (não o ritmo linear);
> - entram nas projeções as recorrências `CONFIRMED` e as `DETECTED` com confiança `MEDIUM`/`HIGH`;
> - incerteza = mediana + [mín, máx] das amostras;
> - fluxo de caixa até o fim do mês corrente + 3 meses, só o saldo total;
> - fatura aberta segue fato "Parcial" com um "≈ no fechamento" secundário, e as faturas futuras são o comprometido +
>   a mediana sem parcelas;
> - previsto × realizado gravado só no backend;
> - insight D15 incluído (`insights-v3`);
> - recorrências: confirmar, ignorar e editar (valor e dia), sem criação manual.
>
> Este ADR fixa só o **modelo**: o que é fato e o que é estimativa, recorrências, fórmulas, incerteza, confiança,
> versionamento e a interface para a Sessão 7.2. Onde e quando o cálculo roda (eventos, sob demanda ou híbrido), a
> persistência e a freshness são decisões da parte B; o contrato da API é da parte C.

## Context

O 05.13 §8 diz que o Forecast é derivado e versionado e que distingue fato, estimativa e simulação; o §5 manda manter
"confirmed/imported/estimated/simulated separados" e não confundir parcelamento com recorrência. O 05.2 proíbe misturar
dados confirmados, estimados e simulados. O 05.10 exige um Forecast determinístico, com evidência e qualidade de dado.
O 05.9 e o 05.13 §15 pedem freshness do Forecast de no máximo 15 min.

Ganchos que as fases anteriores deixaram para o M9:

| Origem | O que ficou para o M9 |
|---|---|
| ADR-0006 §18 | "Próximos eventos" da Home só com fatos; receitas e despesas recorrentes estimadas ficaram para o Forecast |
| ADR-0015/0016 | "Projeção do mês (Estimado)" em Gastos e "próxima receita prevista (Estimado)" em Receitas |
| ADR-0025 §10, ADR-0026 §4, ADR-0029 §4 | "Estimativa pelo seu padrão" da próxima fatura e das futuras, oculta até hoje |
| ADR-0017 | prazo e simulação ficaram para o Forecast e os Goals |
| ADR-0021 | detectores de previsão |

As specs **não definem**: o método de cada projeção, o horizonte, o método de incerteza, a detecção de recorrências,
os limiares de confiança, nem o contrato do `/recurring-expenses` (o 05.6 só lista o recurso).

Insumos que já existem e serão reaproveitados (uma única fonte de semântica financeira):
- `AnalyticsReadModel.entries` e `firstActivity` (fluxo por dia, categoria e comerciante, com o `CashFlowRole`);
- `IncomeSources` (ADR-0015 §6): fonte por comerciante (sem comerciante, por categoria), `RECURRING` em 3 ou mais dos
  6 meses, `typicalDay`;
- o `MerchantNormalizer` (ADR-0013) e a regra do D3 (`MerchantDetectors`: uma cobrança por mês, tolerância de 10%);
- `OverviewReadModel.accountFigures` (saldo das contas que entram no total);
- `CardPortfolio` (ADR-0027 §1): fatura atual, faturas fechadas e futuras, parcelas comprometidas e calendário.

Divergências entre o design e os ADRs:
- **D-Home e `09-dados`:** marcam o fechamento e o vencimento da fatura Aurora (R$ 3.412,80) como "≈ Estimado". No
  ADR-0006 §18, esse valor é **fato parcial** ("Parcial").
- **D-AnalysisSpending:** mostra "≈ R$ 4.600" com o texto "se o ritmo atual continuar". O ritmo linear dá R$ 5.244,82
  (3.846,20 ÷ 22 × 30); o valor do layout só fecha com o realizado mais o restante típico do mês.
- **D-Invoice e D-FutureInvoices:** mostram "≈ R$ 2.900" para novembro, que é a média das faturas **totais**
  (R$ 2.892,20). Essas faturas já incluem parcelas; somar o comprometido a essa média contaria as parcelas duas vezes.

## Decision

### 1. Taxonomia (`basis`)

Todo valor exposto pelo Forecast tem uma `basis`:
- `FACT`: transações `POSTED`, saldos, parcelas já contratadas e datas de fechamento e vencimento do calendário do
  cartão. Valores parciais continuam `FACT`, com `partial = true`.
- `ESTIMATED`: tudo que depende do padrão histórico (recorrências ainda não ocorridas, gasto variável, novas compras no
  cartão, saldos futuros).
- `SIMULATED`: reservado para a 7.2; o `forecast-v1` nunca produz esse valor.

Regras:
- um total que junta fato e estimativa é sempre `ESTIMATED` e sempre traz as partes (`factPart`, `estimatedPart`);
- duas estimativas só se somam dentro de uma mesma projeção, que declara o que soma;
- nada `ESTIMATED` entra em saldo, fluxo, Health ou Insights existentes. Esses continuam só com fatos.

### 2. Recorrências (`RecurringSeries`)

**Entidade** do módulo `forecast`, por Workspace:
- `direction`: `INCOME` ou `EXPENSE`;
- `key`: o comerciante normalizado; sem comerciante, só para `INCOME`, a categoria (a mesma chave do
  `IncomeSources.sourceKey`);
- `channel`: a conta ou o cartão mais frequente nas ocorrências;
- `periodicity`: só `MONTHLY` no v1;
- `expectedDay`: o dia mais frequente (regra do `typicalDay`). Em meses mais curtos, vale o último dia do mês;
- `expectedAmount`: a mediana das ocorrências usadas;
- `amountRange`: o mínimo e o máximo dessas ocorrências;
- `dateTolerance`: ±3 dias;
- `confidence`, as `occurrences` (ids de transação) e o `algorithmVersion`.

**Detecção**, determinística, sobre os 6 meses fechados anteriores ao mês corrente:
- candidata: a chave tem **exatamente uma** ocorrência em pelo menos 3 desses meses, e cada ocorrência fica a até ±10%
  da mediana (`RECURRING_TOLERANCE_PERCENT`, a mesma regra do D3);
- piso de R$ 10 (`RECURRING_MIN_AMOUNT`);
- despesa sem comerciante não vira recorrência no v1: sem chave estável, o risco de falso positivo é alto;
- parcelas de cartão **nunca** viram recorrência. Elas já são fato comprometido (05.13 §5).

**Confiança:**
- `HIGH`: 5 ou 6 meses com ocorrência e amplitude de valor (máx. − mín.) de até 5% da mediana;
- `MEDIUM`: 4 ou mais meses, ou amplitude de até 10%;
- `LOW`: os demais casos.

**Ciclo de vida e estado do usuário:**
- `status`: `DETECTED` (sugerida), `CONFIRMED` ou `IGNORED`;
- `origin`: `DETECTED` ou `USER`;
- o usuário pode:
  - **confirmar** a série;
  - **ignorar** a série. O mesmo `direction + key` não volta a ser sugerido;
  - **editar** `expectedAmount` e `expectedDay`. A edição fica em campos `user*` que a detecção nunca escreve. Um
    valor editado vale como exato: a faixa passa a ser o próprio valor e a confiança passa a `HIGH`;
- a redetecção atualiza **só** os campos detectados e as ocorrências. Ela nunca muda `status`, `origin` nem `user*`;
- uma série confirmada que some do histórico (3 meses sem ocorrência) continua `CONFIRMED`, mas vira `STALE` e sai das
  projeções até voltar a ocorrer ou ser confirmada de novo;
- **quais séries entram nas projeções**: `CONFIRMED` sempre e `DETECTED` com
  confiança `MEDIUM` ou `HIGH`, rotuladas como "Detectada". `IGNORED` e `LOW` nunca entram;
- **ocorrência no ciclo**: uma série está realizada no mês se existe uma transação `POSTED` com a mesma chave e o mesmo
  sentido em `expectedDay ± 3 dias` dentro do mês. Uma série realizada não é projetada de novo no mesmo mês.

### 3. Projeções do MVP

Período e fuso seguem o ADR-0006 §10. `today` é a data de negócio, e o mês corrente é parcial até hoje.

**3.1 Projeção do mês (Gastos e resultado)**, em D-AnalysisSpending
- Fórmula: `projetado = realizado (FACT) + recorrentes pendentes (ESTIMATED) + variável restante (ESTIMATED)`:
  - *realizado*: as despesas de 1 até hoje, com a semântica do ADR-0015 (bruta, cartão na data da compra);
  - *recorrentes pendentes*: as séries de despesa elegíveis ainda não realizadas, com `expectedDay` > hoje;
  - *variável restante*: para cada um dos até 5 meses fechados (mínimo 3, desde o primeiro lançamento), a despesa
    **não recorrente** entre os dias `hoje + 1` e o fim do mês, com o recorte de dias do ADR-0015 §2. O valor central
    é a mediana.
- **Resultado projetado:** `receitas realizadas + receitas recorrentes pendentes − despesa projetada`, com as mesmas
  partes.
- Conferência com os dados de exemplo: com agosto como único mês da base, 3.846,20 + (4.190,00 − 3.372,10) =
  **R$ 4.664,10**, contra o "≈ R$ 4.600" do layout.
- Texto: "≈ R$ 4.660 · Estimado · pelo seu padrão dos últimos N meses · entre R$ a e R$ b".

**3.2 Próxima receita prevista**, em D-AnalysisIncome
- É a série de receita elegível com a próxima data esperada depois de hoje e ainda não realizada no ciclo. No empate,
  vale o maior valor.
- `ESTIMATED`, com o valor esperado, a faixa, a data e a fonte. Exemplo: "Salário · 05 out, pelo seu histórico".
- Sem série elegível, o bloco fica vazio ("Sem receita recorrente identificada").

**3.3 Próximos eventos (30 dias)**, na Home
- **Fatos**: os eventos de fatura do ADR-0006 §18, sem mudança (`PARTIAL`, `OPEN`, `OVERDUE`).
- **Estimados**:
  - as ocorrências esperadas das séries elegíveis em `[hoje, hoje + 30]` ainda não realizadas, com valor, faixa e
    conta;
  - um valor secundário "≈ no fechamento" nos eventos de fatura aberta (3.5), sem trocar o fato.
- Ordenação: data, depois fatos antes de estimados, depois o maior valor.

**3.4 Fluxo de caixa projetado**
- **Lente de caixa**, diferente da 3.1:
  - a compra no cartão não sai da conta; o que sai é o pagamento da fatura, no vencimento;
  - assim, o cartão entra pelas faturas (3.5) e não pelo gasto variável, e nada é contado duas vezes.
- **Ponto de partida**: o saldo total de hoje, das contas que entram no total do Overview (`FACT`).
- **Movimentos projetados**, por dia:
  - receitas e despesas recorrentes **em conta** (as séries cujo canal é cartão já estão no padrão da fatura);
  - pagamento de faturas no vencimento. O que já foi faturado e as parcelas são `FACT`, as novas compras são
    `ESTIMATED`;
  - gasto variável em conta, isto é, despesa não recorrente fora do cartão: a mediana mensal dos últimos meses,
    distribuída pela curva diária média desses meses (determinística).
- **Horizonte**: até o fim do mês corrente mais os 3 meses seguintes, só com o saldo **total** (sem
  divisão por conta no v1).
- **Saída**:
  - por mês: saldo inicial, entradas e saídas (fato e estimado separados) e saldo final em faixa (baixo, central,
    alto);
  - o **menor saldo projetado** e a data dele (usado pelo insight do §8).

**3.5 Faturas de cartão**, em D-Invoice e D-FutureInvoices
- **Fatura aberta**: `fechamento estimado = parcial (FACT) + novas compras até o fechamento (ESTIMATED)`.
  - As novas compras são a mediana das 3 últimas faturas fechadas (mínimo 2), contando só o que entrou nelas entre o
    dia equivalente a hoje e o fechamento, sem parcelas.
- **Próxima fatura e futuras**, até o fim do horizonte:
  - `comprometido (FACT, parcelas) + novas compras (ESTIMATED)`;
  - as novas compras são a mediana das faturas fechadas **sem as linhas de parcela**, menos os créditos.
- **Exemplo Aurora**:

  | | jul | ago | set |
  |---|---|---|---|
  | Fatura total | 2.710,40 | 2.954,10 | 3.012,10 |
  | Sem parcelas | 2.710,40 | 2.474,10 | 2.532,10 |

  - A mediana sem parcelas é **R$ 2.532,10**; com o comprometido de R$ 480, novembro fica em ≈ R$ 3.012,10.
  - O layout mostra "≈ R$ 2.900", que é a média com as parcelas: somar o comprometido a essa média conta as parcelas
    duas vezes.
- **Exibição**: "Já comprometido R$ 480,00 (Confirmado)" e "Novas compras pelo seu padrão ≈ R$ 2.532 (Estimado)", e
  o total só com "≈" e as duas partes visíveis (ADR-0025 §10).

### 4. Incerteza

- **Mediana como valor central e [mínimo, máximo] das amostras usadas como faixa**. Não usa
  aleatoriedade nem desvio, e se explica numa frase: "nos últimos N meses, ficou entre R$ a e R$ b".
- **Propagação**:
  - somar estimativas soma os mínimos e os máximos (conservador);
  - um fato soma o mesmo valor nos dois limites;
  - no fluxo de caixa, a faixa é acumulada mês a mês.
- **Arredondamento**:
  - a API devolve centavos exatos (`NUMERIC`, HALF_EVEN);
  - a UI mostra a estimativa sem centavos e com "≈";
  - o texto alternativo diz "aproximadamente R$ X, entre R$ a e R$ b, estimado".

### 5. Confiança e qualidade dos dados

Cada projeção tem `confidence` (`HIGH`/`MEDIUM`/`LOW`) e `limitations` (padrão do ADR-0021):
- `INSUFFICIENT_HISTORY`: menos de 3 meses fechados desde o primeiro lançamento. Sem estimativa, só os fatos;
- `SHORT_HISTORY`: 3 a 5 meses. No máximo `MEDIUM`;
- `HIGH_VARIABILITY`: amplitude acima de 25% do valor central. No máximo `LOW`;
- `UNCATEGORIZED`: mais de 15% da despesa sem categoria (o mesmo limiar dos Insights). Desce um nível;
- `PARTIAL_CARD_HISTORY`: cartão com menos de 2 faturas fechadas acompanhadas. Sem estimativa de novas compras.

A confiança de uma soma é a menor confiança entre as partes estimadas.

### 6. Versionamento, evidências e o fim do mês

- `algorithmVersion = "forecast-v1"` em toda projeção e série. Uma `forecast-v2` grava ao lado e não reescreve a v1.
- Cada valor estimado traz **evidências** rastreáveis:
  - os meses de amostra e o valor de cada um;
  - os ids das ocorrências da série;
  - os ids das faturas de base.
- **Previsto × realizado:**
  - ao virar o mês, congelar a última projeção do mês que fechou e gravá-la ao lado do realizado;
  - só backend no v1: a métrica `shf.forecast.month.error` e evidência para uma `forecast-v2`;
  - a UI fica para depois.

### 7. Interface para a Sessão 7.2

- A porta **`ForecastReader`**, só de leitura (como a `HealthSnapshotReader`), oferece:
  - `cashFlow(workspace)`: os meses do §3.4, com a faixa;
  - `monthlySavingCapacity(workspace)`: a mediana mensal de `receitas recorrentes − despesas recorrentes − gasto
    variável − parcelas comprometidas` no horizonte, com faixa, confiança e evidências, sempre `ESTIMATED`;
  - `recurringSeries(workspace)`: as séries elegíveis.
- Goals e Simulation **leem** essa base e aplicam deltas `SIMULATED` por cima, sem gravar nada no Forecast.

### 8. Insight de previsão

- **D15 `BALANCE_MAY_GO_NEGATIVE`**, tipo `SPENDING`.
- **Gatilho**: o limite **baixo** do saldo total projetado nos próximos 30 dias fica abaixo de zero, com déficit de
  pelo menos R$ 100 e confiança de pelo menos `MEDIUM`.
- **Importância**:
  - `IMPORTANT` quando o valor **central** fica abaixo de zero;
  - `INFO` quando só o limite baixo fica abaixo de zero.
- **Texto**: "Seu saldo pode ficar negativo por volta de {data}." A evidência é o menor saldo, com a faixa e os
  maiores movimentos até a data.
- **Custo**: os Insights passam a ler o `ForecastReader`, mais um elo na cadeia de eventos (`insights-v3`).

### 9. Fora de escopo

- Open Finance, investimentos, rendimento e inflação.
- Periodicidades não mensais (semanal, anual).
- Feriados (ADR-0025 §14).
- Divisão por conta no fluxo de caixa.
- Previsão além do horizonte aprovado.
- Criação manual de recorrência.
- Uso de IA ou LLM.

## Alternatives

- **Ritmo linear** ("se o ritmo atual continuar"): simples, mas superestima quando há gasto concentrado no início do
  mês, como o aluguel no dia 5: R$ 5.244,82 contra o "≈ R$ 4.600" do layout.
- **Faixa por desvio-padrão ou por percentis**: com 3 a 6 amostras, P25–P75 é instável e o desvio supõe normalidade;
  o mínimo e o máximo é honesto e explicável.
- **Somente recorrências confirmadas**: projeções vazias até o usuário agir; esconde o valor do produto.
- **Média das faturas totais** como estimativa: conta as parcelas duas vezes quando somada ao comprometido.

## Consequences

- A Home, a Análise e os Cartões ganham estimativas rotuladas, sem mexer nos fatos existentes.
- Um módulo novo, `forecast`, com estado do usuário (as recorrências), que nenhum rebuild pode apagar.
- Duas lentes documentadas (competência na 3.1, caixa na 3.4), cada uma com a sua partição para não contar nada duas
  vezes.

## Security / Privacy

- Recorrências e projeções são dados financeiros do Workspace: sempre filtrados por `workspace_id`.
- Logs, métricas e eventos só com ids, contagens e meses.

## Financial Integrity

- Puro, determinístico, versionado e em `BigDecimal`.
- Fato e estimativa nunca somados sem expor as partes.
- Nenhuma regra nova de saldo, fluxo ou fatura: tudo vem das portas existentes.

## Operational Impact

Definido na parte B.

## Related Specs

- `specs/05-system-design/05.13` §5, §8, §15; `05.2`, `05.3`, `05.6`, `05.9`, `05.10`
- ADR-0006, 0013, 0015, 0016, 0017, 0021, 0025, 0026, 0029
- `design/specs/03-screens.md`, `09-dados-de-exemplo.md`

## Para a Sessão 7.2

Preenchido ao fim da parte D.

## Date

2026-10-28
