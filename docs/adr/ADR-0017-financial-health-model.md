# ADR-0017 — Modelo e algoritmo do Financial Health `health-v1` (M6, parte B)

## Status

Accepted

> Decisões aprovadas em 2026-10-14: reserva = contas `SAVINGS` ativas (mesmo fora do total); Credit e Debt
> `NOT_APPLICABLE` até o M8, com renormalização e cobertura visível; pesos 25/20/15/15/15/10; fórmulas, curvas,
> limiares, confiança, frase, fatores e próximos passos (sem ✦) como propostos. Em 2026-10-15: Cash Flow sem receita
> com despesa vale S(r) = 0; Spending exige ao menos 1 mês na média.
>
> Este ADR fixa só o **modelo** (o que se calcula e como). Onde e quando o cálculo roda (sob demanda, snapshot por
> eventos ou snapshot mensal) é decisão da parte C, em ADR próprio.

## Context

A Home não tem o bloco "Como estou?" (Financial Health), que é a âncora visual da Home (design/specs/07 §1–2:
"Score nunca sozinho"). O `mvp.md` define seis dimensões (Cash Flow, Spending, Credit, Debt, Reserve, Consistency) e
exige que o score seja explicável. O `D-HealthMethod` lista o que o painel "Como calculamos?" mostra, mas deixa a
ponderação `[a documentar]`. As specs não definem fórmulas, pesos, faixas, mínimo de histórico nem o tratamento de
dimensões sem dados.

As entradas já existem: fluxo de caixa mensal, por categoria e por fonte de receita na projeção de Analytics
(ADR-0014/0015) e saldo por conta no Overview (ADR-0006). Os estornos importados já podem virar `REFUND`
(adendo de 2026-10-14 ao ADR-0009 §9), então não inflam mais as receitas. Cartões e dívidas só chegam no M8.

## Decision

### 1. Base comum

- **Mês de referência M:** por padrão, o mês corrente, parcial até hoje (fuso de negócio do ADR-0006 §10). Na
  evolução, M é cada mês fechado, calculado com os dados até o último dia dele.
- **Data-base:** hoje no mês corrente; o último dia de M na evolução.
- **Janela W:** os até **6 últimos meses fechados** até a data-base (M, se fechado; senão, até M−1), começando no mês do
  primeiro lançamento do Workspace (`AnalyticsReadModel.firstActivity`). Dentro de W, mês sem movimento conta como zero
  (mesma regra do ADR-0015 §4). **n** = número de meses em W.
- **Semântica única:** receitas, despesas, reembolsos e resultado são os do ADR-0006 §9 (só `POSTED`, contas ativas
  incluídas no total, transferências e ajustes fora, despesa bruta, resultado = receitas + reembolsos − despesas), lidos
  das portas existentes: `AnalyticsReadModel.monthly`, `byCategory` e `entries` (fontes de receita do ADR-0015 §6) e
  `OverviewReadModel.accountFigures` para saldos. O Health não cria SQL com semântica financeira própria; se a parte C
  precisar de SQL novo, ele terá teste de paridade com essas portas.
- **Dinheiro** em `Money`/`BigDecimal`. Razões intermediárias (r, p, Δ, E, y, uc) com 4 casas (HALF_EVEN); valores monetários
  derivados (médias, faltas) com 2 casas (HALF_EVEN), como no ADR-0015 §1.
- **Curvas:** interpolação linear entre os pontos da tabela; abaixo do primeiro ponto vale a nota do primeiro, acima do
  último vale a do último.
- **Arredondamento:** a nota de cada dimensão é arredondada para inteiro (0–100, HALF_EVEN). O score geral é calculado
  a partir das **notas inteiras exibidas**, para que quem lê consiga refazer a conta com os números da tela.

### 2. Dimensões

Status de cada dimensão: `OK`, `INSUFFICIENT_DATA` (com motivo, ex.: "precisa de 3 meses fechados") ou
`NOT_APPLICABLE`.

#### Cash Flow — mínimo n ≥ 1

- Taxa de sobra **r** = Σ resultado em W ÷ Σ receitas em W.
- **p** = meses de W com resultado ≥ 0 ÷ n.
- Nota = 0,7 · S(r) + 0,3 · 100 · p.
- S(r): −10% → 0 · 0% → 30 · 10% → 60 · 20% → 80 · ≥ 40% → 100.
- Sem receita em W: com despesa, S(r) = 0 (p segue a regra normal); sem receita nem despesa, `INSUFFICIENT_DATA`.
- Explicação: "Receitas e reembolsos cobriram as despesas em {p·n} de {n} meses ({período}). Sobra média de
  {R$}/mês ({r}% da receita)."
- Ponto de atenção: p < 1 ou r < 10%.

#### Spending — mínimo: ao menos 1 mês na média

- M contra a média dos **5 meses anteriores** a M, **no mesmo recorte de dias** (ADR-0015 §2: mês parcial até o dia d
  compara 1–d; mês fechado compara meses cheios), com despesa **bruta**. Os números batem com a aba Gastos/Categorias da
  Análise com `compareWith=AVERAGE`.
- **Δ** = (despesa de M − média) ÷ média.
- **E** = Σ por categoria de max(0, despesa da categoria em M − média da categoria) ÷ média total de despesas.
  "Sem categoria" conta como uma categoria, como na Análise.
- Nota = 0,5 · S(Δ) + 0,5 · S(E).
- S(Δ): ≤ 0% → 100 · +10% → 75 · +25% → 40 · ≥ +50% → 0.
- S(E): 0% → 100 · 10% → 70 · 25% → 30 · ≥ 40% → 0.
- `INSUFFICIENT_DATA` se a média tem 0 meses (`average.state = INSUFFICIENT_DATA` do ADR-0015 §4) ou é zero. Com 1–2
  meses (`LIMITED`) a dimensão é `OK`; o score geral já aparece como estimado (§4).
- Explicação: "Gasto {Δ}% vs sua média (1 a {d} de {mês}). {Categoria} está {x}% acima da média (+{R$})."
- Ponto de atenção: alguma categoria contribui com ≥ 5 pontos percentuais de E.

#### Reserve — mínimo: n ≥ 1 e ao menos uma conta de reserva

- **Reserva** = soma dos saldos das contas `SAVINGS` **ativas** na data-base, **mesmo as fora do saldo total**
  (`includedInTotal = false`). Saldo derivado pela semântica do ADR-0005/0006 (`accountFigures`).
- Cobertura **c** = reserva ÷ média mensal de despesas em W (zeros contam), com 2 casas (HALF_EVEN).
- Curva: 0 → 0 · 1 → 45 · 3 → 80 · ≥ 6 → 100. Reserva ≤ 0 dá nota 0.
- `INSUFFICIENT_DATA` sem conta `SAVINGS` ativa (motivo e próximo passo "Indique onde fica sua reserva") ou com média de
  despesas zero.
- Explicação: "Sua reserva cobre ≈ {c} meses da média de despesas ({R$}/mês, {período}). Referência: 6 meses."
- Ponto de atenção: c < 3.

#### Consistency — mínimo n ≥ 3

- Mês **regular** = receita **e** despesa do mês a até ±15% das respectivas medianas em W.
- **k** = meses regulares; **y** = % da receita de W vinda de fontes `RECURRING` (ADR-0015 §6).
- Nota = 0,7 · 100 · k ÷ n + 0,3 · y (linear).
- Explicação: "Receitas e despesas ficaram dentro do seu padrão em {k} de {n} meses; {y}% da receita vem de fontes
  recorrentes."
- Sem ponto de atenção no v1.

#### Credit e Debt — `NOT_APPLICABLE` até o M8

- Explicação: "Disponível quando houver cartões e parcelamentos."
- Sem nota inventada. Quando o M8 entrar, as dimensões ganham fórmula e o algoritmo vira `health-v2` (§5).

### 3. Score geral, pesos e cobertura

- **Pesos:** Cash Flow 25 · Reserve 20 · Spending 15 · Credit 15 · Debt 15 · Consistency 10 (aparecem no
  "Como calculamos?").
- **Score** = Σ (peso × nota) ÷ Σ (pesos das dimensões `OK`), sobre as notas inteiras, arredondado para inteiro
  (HALF_EVEN). É a renormalização sobre as dimensões disponíveis.
- **Cobertura:** dimensões `OK` e a fração do peso que elas representam, ex.: "4 de 6 dimensões · 70% do peso".
- **Mínimo:** Cash Flow `OK` e pelo menos mais uma dimensão `OK`. Abaixo disso, o resumo é `INSUFFICIENT_DATA` (sem
  score) e mostra só as dimensões disponíveis.
- **Variação** = score(M) − score(M−1), mesmo algoritmo e mesma versão; nula se M−1 não tem score. É o
  "+3 vs agosto" do layout.

### 4. Confiança e precisão

- **Estimado:** n < 3. A resposta marca `estimated = true` e a UI mostra "score estimado" com o tracejado de estimativa.
- **Confiança** (uc = parcela do valor gasto em W sem categoria):
  - **alta:** n ≥ 6 e uc ≤ 10%;
  - **média:** n ≥ 3 e uc ≤ 25%;
  - **baixa:** demais casos.
- O número de contas aparece só como contexto ("3 contas"); não muda a confiança.
- **Mês em andamento:** `partial = true`, com o aviso "{mês} ainda está em andamento".

### 5. Versionamento

- `algorithmVersion = "health-v1"` acompanha **todo** resultado (resumo, dimensões, evolução, método) e é guardado com
  qualquer resultado persistido.
- Qualquer mudança de curva, peso, limiar, entrada ou regra de status gera nova versão (`health-v2`…). O histórico
  calculado com uma versão não é reescrito sem decisão registrada em ADR.

### 6. Evidências

- Cada dimensão traz a lista de evidências `{rótulo, valor, período from/to, fonte}` com os números usados na nota.
- Fontes: `analytics.monthly`, `analytics.categories`, `analytics.income-sources`, `overview.account-balances`. Os
  valores são os mesmos exibidos em Análise e Contas para o mesmo período, rastreáveis até a porta que os produziu.

### 7. Frase, fatores e próximos passos (templates determinísticos, sem IA)

- **Frase do resumo:** as até 2 dimensões com nota ≥ 80 "sustentam o score"; as até 2 com nota < 70 "são os pontos que
  mais pesam". Sem nenhuma de um lado, a parte correspondente é omitida.
- **Fatores:** até 3 de cada lado. "Sustentando o resultado": nota ≥ 80. "Pesando no resultado": nota < 70 ou com
  ponto de atenção. Ordem: peso × |nota − 70|, desempate pela ordem dos pesos. Cada fator usa o template da dimensão
  com os números reais, com ícones neutros + / − e sem cor de julgamento.
- **Próximos passos:** até 3, nesta prioridade, só para telas que existem; sem simulação nem prazo (isso é Forecast,
  M9):
  1. reserva < 6 meses: "Faltam {R$} para 6 meses de despesas" → **Contas**;
  2. sem conta de reserva: "Indique onde fica sua reserva" → **Adicionar conta**;
  3. excesso numa categoria (ponto de atenção do Spending): "{Categoria} {x}% acima da média" → **Análise › Categorias**
     com a categoria selecionada;
  4. uc > 10%: "Categorizar transações" → **Transações** filtradas por sem categoria;
  5. mês com resultado negativo em W: "Veja o que mudou em {mês}" → **Análise** daquele mês.
  - Objetivos (M7) e Insights (M10) são omitidos até existirem.
  - Os passos **não usam ✦**: ✦ é o rótulo de IA (design/specs/07 §5) e aqui não há IA. Diverge do layout `D-Health`.

### 8. O que não entra

Investimentos, seguros, empréstimos complexos (conforme `D-HealthMethod`), cartões e parcelamentos (até o M8),
transações `PENDING`, transferências entre contas próprias, ajustes e contas arquivadas. Contas fora do total entram
**apenas** como reserva (`SAVINGS`).

### 9. Números do layout

Os números do layout (82/64/71/88/68/79 → 74) são ilustrativos e não são reproduzíveis com dados reais. O E2E da parte E
verifica os valores exatos do `health-v1` num fixture próprio, com histórico suficiente.

## Alternatives

- **Reserva:** só `SAVINGS` incluídas no total (penaliza quem tira a reserva do total); novo atributo `isReserve` na
  conta (preciso, mas muda o contrato de Accounts e aumenta o escopo); todo o saldo do total (o salário recém-caído
  vira "reserva" e superestima).
- **Credit/Debt:** Debt v1 pelo uso de saldo negativo (regra fraca, substituída no M8); nota neutra 50 (inventa dado e
  apresenta estimativa como fato).
- **Pesos:** iguais (Consistency pesaria tanto quanto Cash Flow); 30/25/20/10/10/5 (menos mudança quando o M8 entrar,
  mas subvaloriza Credit/Debt no v2).
- **Cash Flow sem receita:** `INSUFFICIENT_DATA` (esconderia um fluxo negativo real).
- **Spending:** exigir 3 meses na média (mais robusto, mas a Home ficaria até 3 meses só com Cash Flow e Reserve).
- **Score sobre notas fracionárias:** mais preciso, mas o usuário não consegue refazer a conta com os números da tela.

## Consequences

- Score explicável e reproduzível: cada nota tem fórmula, evidência e template; o geral se refaz com as notas exibidas.
- Até o M8 o score cobre no máximo 70% do peso, com a cobertura sempre visível. A chegada de Credit/Debt muda a base
  e exige `health-v2`.
- Reserva em conta corrente ou digital não conta; o próximo passo orienta a cadastrar uma conta `SAVINGS`.
- Um usuário que só registrou despesas recebe Cash Flow baixo; é o retrato real dos dados informados.
- Divergência deliberada do layout: próximos passos sem ✦ e sem links para Objetivos/Insights.

## Security / Privacy

- Cálculo só sobre dados do próprio Workspace, pelas portas que já filtram por Workspace.
- Textos são templates preenchidos no backend; nenhum dado financeiro em logs, métricas ou traces (05.9/05.12).
- Nenhuma IA ou LLM envolvido.

## Financial Integrity

- Uma única semântica de fluxo e saldo (ADR-0005/0006 §9 via ADR-0014/0015); o Health não redefine receita, despesa,
  reembolso ou saldo.
- Cálculo puro e determinístico no domínio, `BigDecimal` em tudo, arredondamentos definidos e testáveis.
- Score sempre versionado; estimativa (`estimated`) e mês parcial (`partial`) sempre sinalizados.

## Operational Impact

- Nenhum impacto até a parte C, que decide estratégia de cálculo, persistência e freshness (05.9: freshness do Health
  ≤ 5 min) e terá ADR próprio.
- O endpoint de método (parte D) expõe pesos, curvas, mínimos e exclusões direto deste ADR, para o drawer
  "Como calculamos?".

## Related Specs

- `specs/01-product/mvp.md` (Financial Health), `principles.md`
- `specs/05-system-design/05.13-consolidated-system-design.md` (§7), `05.9-observability-operations.md`,
  `05.12-lgpd-privacy-data-governance.md`
- `design/specs/03-screens.md` (HOME, FINANCIAL HEALTH), `07-design-decisions.md` (§1, §2, §5)
- `docs/adr/ADR-0004`, `ADR-0005`, `ADR-0006` (§9, §10), `ADR-0009` (§9 e adendo), `ADR-0014`, `ADR-0015` (§2, §4, §6)

## Date

2026-10-15
