# ADR-0021 — Modelo de Insights `insights-v1` (M7, parte A)

## Status

Accepted

> Decisões aprovadas em 2026-10-16: catálogo de 10 detectores, limiares, pontuação (Impact, Relevance, Confidence),
> classificação, ciclo de vida, deduplicação, versionamento, evidências e feedback como propostos. UX: chip "Cartões"
> visível e desabilitado com "Em breve" até o M8; insights de receita só em Todos/Importantes, com a tag "Receitas";
> ✦ só no rótulo "✦ Insight"; "Criar objetivo de economia" (M10) e "Perguntar ao Copilot" (M11) como placeholders
> desabilitados ("Em breve").
>
> Este ADR fixa só o **modelo** (o que se detecta, como se pontua e como o insight vive). Onde e quando a geração roda
> (sob demanda, por eventos ou agendada), a persistência e a freshness são decisões da parte B, em ADR próprio; o
> contrato da API é da parte C.

## Context

O MVP inclui Insights (`mvp.md`), com os filtros Todos, Importantes, Gastos, Cartões e Saúde (`features.md`). O
05.13 §9 diz que a qualidade de um insight considera **Impact, Relevance e Confidence** e dá três exemplos: ruim
("R$ 12,50 a mais em café"), contextual ("alimentação fora de casa +32%, ≈ R$ 280") e acionável (envolve meta, M10).
O 05.10 exige insight determinístico com evidência e qualidade de dado; o 05.14, que se explique a origem dos valores.
O design (`02-components`, `03-screens`, `D-Insights`, `D-InsightDetail`) define o card e o detalhe:
Resumo → Evidências → Explicação → Ação recomendada + Confiança + Relacionados.

As specs **não definem** quais detectores existem, limiares, fórmulas de pontuação, a regra de "Importante", ciclo de
vida nem deduplicação.

As entradas já existem: fluxo de caixa por dia, categoria e comerciante (`AnalyticsReadModel.entries`), comparação com
a média no mesmo recorte de dias, variação e decomposição (ADR-0015), fontes de receita recorrentes (ADR-0015 §6),
maiores despesas individuais (`largestExpenses`) e o snapshot do Health com score, variação, dimensões e confiança
(ADR-0017/0018). Cartões só chegam no M8, previsão no M9 e metas no M10.

## Decision

### 1. Base comum

- **Período avaliado P** (um por vez, fuso de negócio do ADR-0006 §10):
  - do dia 1 ao dia 6 do mês corrente, P = **mês anterior, fechado** (os insights dele são finalizados com o mês
    completo);
  - a partir do dia 7, P = **mês corrente, parcial** até hoje (d = dia de hoje).
  - Os primeiros dias do mês não geram comparações com poucos dias; cada insight de período vive do dia 7 de M ao
    dia 6 de M+1.
- **Base de comparação:** média mensal dos **5 meses anteriores** a P, **no mesmo recorte de dias** (ADR-0015 §2:
  `ComparisonPlan` padrão; mês parcial até o dia d compara 1–d; mês fechado compara meses cheios), com meses sem
  movimento contando zero a partir do primeiro lançamento (ADR-0015 §4). **m** = `monthsUsed`. Detectores comparativos
  exigem **m ≥ 2**. Os números batem com a Análise com `compareWith=AVERAGE`.
- **Referência R:** despesa média mensal da janela W do ADR-0017 §1 (até 6 meses fechados, zeros contam). É o
  denominador do impacto relativo.
- **Semântica única:** receita, despesa (bruta), reembolso, categoria, comerciante, fonte de receita, saldo e score vêm
  das portas e serviços de domínio existentes (`AnalyticsReadModel`, `FlowLedger`, `ComparisonPlan`, `Variation`,
  `ChangeBreakdown`, `IncomeSources`, snapshot do Health, `OverviewReadModel`). Nenhum SQL com semântica financeira
  própria; se a parte B precisar de SQL novo, ele terá teste de paridade com essas portas.
- **Dinheiro** em `Money`/`BigDecimal`; razões com 4 casas e valores derivados (médias, ticket médio) com 2 casas,
  HALF_EVEN (ADR-0015 §1).
- **Detectores puros e determinísticos** no domínio: mesma entrada, mesmos insights, na mesma ordem.

### 2. Catálogo `insights-v1`

Cada detector define entradas, condição, impacto **A** (em R$, ou em pontos nos do Health), sujeito (para a identidade,
§5), textos, tipo (filtro) e ações. "Sem categoria" (`categoryCode` nulo) nunca é sujeito de D1/D4: tem detector
próprio (D10).

| # | Código | Tipo | Dispara quando | Impacto A |
|---|---|---|---|---|
| D1 | `CATEGORY_SPIKE` | Gastos | Categoria de despesa com Δ = atual − média ≥ **R$ 100**, Δ ÷ média ≥ **20%** e Δ ÷ R ≥ **3%**; m ≥ 2. Até **3** por P (maior A). | Δ |
| D2 | `SPENDING_ABOVE_PATTERN` | Gastos | Despesa total com Δ ≥ **R$ 200** e Δ ÷ média ≥ **15%**; m ≥ 2. | Δ |
| D3 | `NEW_RECURRING_MERCHANT` | Gastos | Comerciante identificado com exatamente 1 cobrança por mês em **3 meses consecutivos** terminando em P (ou em P−1 se P é parcial e ainda não houve cobrança), valores a ±10% da mediana, **ausente nos 3 meses anteriores** à série (o histórico precisa cobri-los) e mediana ≥ **R$ 10**. | mediana mensal |
| D4 | `UNUSUAL_EXPENSE` | Gastos | Despesa individual categorizada de P (entre as 10 maiores, `largestExpenses`) com valor ≥ **R$ 250** e ≥ **3×** o ticket médio da categoria nos 6 meses fechados anteriores a P, com ≥ **5** compras nesses meses. Até **3** por P. | valor − ticket médio |
| D5 | `RECURRING_INCOME_MISSING` | Receitas | Fonte `RECURRING` (ADR-0015 §6, nos 6 meses até P−1) sem entrada em P, com P fechado ou hoje > `typicalDay` + 5 dias; esperado ≥ **R$ 200**. | esperado |
| D5 | `RECURRING_INCOME_LOWER` | Receitas | Mesma fonte, com entrada em P < **80%** do esperado e diferença ≥ **R$ 200**, nas mesmas condições de data. | esperado − recebido |
| D6 | `NEW_INCOME_SOURCE` | Receitas | Fonte com entrada em P, sem entrada nos 6 meses anteriores; Workspace com ≥ **3** meses fechados de histórico; valor ≥ **R$ 100**. | valor |
| D7 | `HEALTH_SCORE_CHANGE` | Saúde | Snapshot de P com score e variação (mesma versão do algoritmo) com \|variação\| ≥ **5** pontos. | \|variação\| (pontos) |
| D8 | `HEALTH_DIMENSION_CHANGE` | Saúde | Dimensão `OK` em P e P−1 com \|Δ nota\| ≥ **10**; só a de maior \|Δ\| (desempate: ordem dos pesos do ADR-0017 §3). | \|Δ\| (pontos) |
| D9 | `LOW_RESERVE` | Saúde | Reserve `OK` com cobertura **c < 3** meses (ADR-0017 §2). | 6 × média − reserva |
| D10 | `UNCATEGORIZED_SHARE` | Gastos | Despesa sem categoria em P ≥ **15%** da despesa de P e ≥ **R$ 200**. | valor sem categoria |

Definições:
- **Esperado** (D5) = média da fonte nos meses dos 6 até P−1 em que ela entrou (2 casas).
- **Ticket médio** (D4) = despesa da categoria ÷ compras (`transaction_count`) nos 6 meses fechados (2 casas), a mesma
  definição da Análise › Categorias.
- Fontes de receita (D5/D6) seguem a chave do ADR-0015 §6 (comerciante; sem comerciante, a categoria).
- Cobrança mensal (D3): soma do comerciante no mês com `transaction_count` = 1 naquele mês.

**Textos** (templates PT-BR preenchidos no backend; "neste mês" vira "em {mês}" quando P é fechado):

| # | Título (frase do card e do detalhe) | Apoio |
|---|---|---|
| D1 | "Seus gastos com {categoria} aumentaram {p}% neste mês, cerca de R$ {Δ≈} a mais." | "R$ {atual} até {d} de {mês}, contra média de R$ {média} no mesmo período de {mês inicial}–{mês final}." + "{Comerciante} concentra {s}% do valor." se o maior comerciante tem s ≥ 30% |
| D2 | "Seus gastos estão {p}% acima do seu padrão neste mês, cerca de R$ {Δ≈} a mais." | "{Cat1} (+R$ {x}) e {Cat2} (+R$ {y}) explicam a maior parte da diferença." (até 3 categorias com maior Δ positivo do `ChangeBreakdown` com base AVERAGE) |
| D3 | "{Comerciante} cobra cerca de R$ {v≈} todo mês desde {mês}." | "Pode ser uma assinatura nova: {n} cobranças entre R$ {mín} e R$ {máx}, perto do dia {dia}." |
| D4 | "Uma compra de R$ {v} em {categoria} ficou {x} vezes acima do seu valor médio." | "{Comerciante ou 'Comerciante não identificado'} em {dd mmm}. Seu valor médio em {categoria} é R$ {t} ({n} compras em {período})." |
| D5 missing | "Ainda não identificamos {fonte} neste mês." | "Ela costuma entrar por volta do dia {dia}, em média R$ {esperado}. Se já recebeu, importe o extrato mais recente." |
| D5 lower | "{fonte} veio cerca de R$ {Δ≈} abaixo da média neste mês." | "R$ {recebido} até agora, contra média de R$ {esperado}." |
| D6 | "Você recebeu R$ {v} de uma nova fonte: {fonte}." | "Entrada em {dd mmm}. Nenhuma receita dessa fonte nos 6 meses anteriores." |
| D7 | "Seu score {subiu\|caiu} {n} pontos em relação a {mês anterior}." | frase do resumo do snapshot (ADR-0017 §7) |
| D8 | "{Dimensão} passou de {a} para {b} em relação a {mês anterior}." | explicação da dimensão no snapshot |
| D9 | "Sua reserva cobre cerca de {c} meses de despesas." | "A referência usada no score é 6 meses. Faltam R$ {A} (média de R$ {média}/mês em {período})." |
| D10 | "{p}% dos seus gastos deste mês estão sem categoria." | "{n} transações, R$ {v}. Categorizar deixa as comparações e os insights mais precisos." |

- Nomes de dimensão em PT, como no Health (Fluxo de caixa, Gastos, Reserva, Consistência).
  **Adendo (2026-10-16, ADR-0022):** o título do D8 virou "A nota de {dimensão} passou de {a} para {b} em relação a
  {mês anterior}." ("Gastos passou…" não concorda em número); mesmo conteúdo, só a concordância.
- Nunca se usa a descrição original da transação nos textos (dado livre do usuário); só comerciante e categoria.
- Linguagem neutra (02-components, Tom de voz): "subiu/caiu", "acima/abaixo", sem julgamento nem imperativo.

**Ações** (só telas que existem; rotas do frontend atual):

| Código | Rótulo | Destino | Detectores |
|---|---|---|---|
| `VIEW_TRANSACTIONS` | "Ver as {N} transações" | `/transactions?period=CUSTOM&from&to&type=EXPENSE&category={código}` (N = compras da categoria em P) | D1 (primária) |
| `VIEW_CATEGORY` | "Ver categoria" | `/analysis/categories?month&category` | D1 |
| `VIEW_ANALYSIS` | "Ver na análise" | `/analysis?month` | D2 (primária) |
| `VIEW_MERCHANTS` | "Ver comerciantes" | `/analysis/merchants?month` | D3 (primária) |
| `VIEW_TRANSACTION` | "Ver transação" | `/transactions/{id}` | D4 (primária) |
| `IMPORT_STATEMENT` | "Importar extrato" | `/import` | D5 missing (primária) |
| `VIEW_INCOME` | "Ver receitas" | `/analysis/income?month` | D5, D6 |
| `VIEW_HEALTH` | "Ver saúde" | `/home/health` | D7, D8, D9 (primária) |
| `VIEW_ACCOUNTS` | "Ver contas" | `/accounts` | D9 |
| `CATEGORIZE` | "Categorizar transações" | `/transactions?category=none` | D10 (primária) |
| `CREATE_SAVINGS_GOAL` | "Criar objetivo de economia" | — (`available = false`, "Em breve") | D1, D2 |
| `ASK_COPILOT` | "Perguntar ao Copilot" | — (`available = false`, "Em breve") | D1, D2 |

- O backend devolve as ações com código e parâmetros; o frontend monta a rota. Placeholders não têm destino e passam a
  existir quando o M10/M11 entrarem (nova versão das ações, sem mudar os detectores).
- **Ação recomendada** (bloco de texto do detalhe): D1 com comerciante de s ≥ 30% → "Revisar {comerciante}:
  {s}% do gasto da categoria (R$ {valor} em {n} compras)."; senão "Revisar {categoria}". Os demais detectores usam o
  rótulo da ação primária. Sugere, não manda (02-components, Recommendation).

### 3. Pontuação

**Impact.**
- **Piso global:** A ≥ **R$ 50** **e** A ÷ R ≥ **1%** (se R = 0, só o piso absoluto). Não se aplica a D3 (que tem piso
  próprio e nunca é Importante), D7 e D8 (em pontos). O "R$ 12,50 em café" nunca passa.
- **Pontos I (0–100):** curva sobre s = A ÷ R: 0 → 0 · 2% → 20 · 5% → 50 · 10% → 80 · ≥ 20% → 100 (interpolação
  linear como no ADR-0017 §1; R = 0 → I = 20). D7/D8, sobre os pontos: 5 → 40 · 10 → 70 · ≥ 20 → 100.

**Relevance Rv (0–100):** começa em **60**, limitada a 0–100:
- **+15** persistente: o mesmo detector e sujeito dispararam no período anterior (qualquer estado);
- **−30** semelhante dispensado: mesmo detector e sujeito com um insight `DISMISSED` nos últimos 90 dias (outro período);
- **−15** feedback: algum "Não útil" no mesmo detector nos últimos 90 dias (aplicado uma vez).
- Essencial versus discricionário **não entra** no v1: o catálogo (ADR-0012) não tem esse atributo e criá-lo é decisão
  de produto.

**Confidence (alta / média / baixa)**, com uc = participação da despesa sem categoria em P e na base:
- **alta:** m ≥ 5, uc ≤ 10% e (P fechado ou d ≥ 15);
- **média:** m ≥ 3 e uc ≤ 25%;
- **baixa:** demais casos.
- D4 usa o número de meses fechados com compras da categoria no lugar de m; D7/D8/D9 usam a confiança do snapshot
  (ADR-0017 §4); D10 é sempre média (o próprio insight é sobre qualidade de dado).
- Texto: "Confiança {nível} · {m} meses completos de histórico" (Confidence Indicator do DS).

**Importante versus Informativo.** Importante se **todas**:
- detector elegível: D1, D2, D4, D5, D7 (só com \|variação\| ≥ 10) e D9 (só com c < 1);
- I ≥ 50, confiança diferente de baixa e Rv ≥ 50.

D3, D6, D8 e D10 são sempre Informativos.

**Ordenação:** importância → **prioridade** = arredondar(0,6 · I + 0,4 · Rv) (inteiro, HALF_EVEN), desc → confiança
(alta primeiro) → `updatedAt` desc → fingerprint. É a mesma ordem da lista, da Home e de um futuro corte do plano Free.

### 4. Arredondamento dos textos

- Percentual do título: inteiro, HALF_EVEN, calculado da razão exata (Δ ÷ base × 100), não do percentual de 1 casa.
- "Cerca de R$ {v≈}": múltiplo de **10** abaixo de R$ 1.000, de **50** de R$ 1.000 a R$ 9.999,99, de **100** a partir de
  R$ 10.000, HALF_EVEN. Como A ≥ R$ 50, nunca aparece "R$ 0".
- "{x} vezes" (D4) e cobertura "{c} meses" (D9): 1 casa, HALF_EVEN, vírgula decimal.
- Valores sem "cerca de" são exatos, com centavos (formato pt-BR). Todos os casos com testes de tabela.

### 5. Identidade, ciclo de vida e deduplicação

- **Fingerprint** = SHA-256 de `detector | workspaceId | chave do período | sujeito`, **sem a versão**:
  - chave do período = P (`YYYY-MM`); D3 usa o mês de início da série (não repete todo mês); D9 não tem período e o
    sujeito é a faixa de cobertura (`<1`, `1-2`, `2-3`): dispensar esconde até a faixa mudar;
  - sujeito = código da categoria (D1), `TOTAL` (D2), chave do comerciante (D3), `transactionId` (D4), chave da fonte
    (D5/D6), `SCORE` (D7), código da dimensão (D8), `RESERVE` (D9), `UNCATEGORIZED` (D10).
  - Um insight por (Workspace, fingerprint, versão).
- **Estados:** `ACTIVE`, `DISMISSED`, `RESOLVED`, `EXPIRED`, mais `seenAt` (visto); "Novo" = `ACTIVE` sem `seenAt`.
  - **Recalcular** o mesmo fingerprint atualiza o mesmo insight (números, textos, evidências, pontuação, `updatedAt`,
    `revision + 1`). Se passa de Informativo para Importante, `seenAt` é limpo (volta a ser "Novo").
  - **`RESOLVED`:** a condição deixou de valer num recálculo do mesmo P (ex.: recategorizou, a receita entrou, a
    transação foi cancelada). Se voltar a valer no mesmo P, volta a `ACTIVE`.
  - **`DISMISSED`:** ação do usuário, definitiva; o mesmo fingerprint **nunca** é ressuscitado, em nenhuma versão.
  - **`EXPIRED`:** P deixou de ser avaliado (dia 7 do mês seguinte) ou a versão foi substituída (§6). D3 e D9 expiram
    quando a condição deixa de valer (viram `RESOLVED`) ou após 90 dias sem disparar.
    **Adendo (2026-10-16, ADR-0022 §4):** toda geração reavalia D3/D9 e um `ACTIVE` não detectado vira `RESOLVED` na
    hora; os "90 dias sem disparar" ficam cobertos por essa regra, sem estado `STALE`.
  - Visíveis na lista: `ACTIVE`. `RESOLVED`, `EXPIRED` e `DISMISSED` ficam guardados para relevância e auditoria.
- **Retenção:** insights não visíveis são apagados 13 meses depois de `updatedAt` (confirmado em 2026-10-16: expurgo diário, ADR-0022 §6).

### 6. Versionamento

- `algorithmVersion = "insights-v1"` acompanha **todo** insight persistido e toda resposta.
- Qualquer mudança de detector, limiar, curva, pontuação, regra de importância ou texto gera nova versão
  (`insights-v2`…).
- Na troca de versão: os insights `ACTIVE` do P corrente da versão antiga viram `EXPIRED` (`VERSION_SUPERSEDED`) e a
  nova versão gera os seus; o estado `DISMISSED` é herdado pelo fingerprint; insights de períodos passados não são
  reescritos sem decisão registrada em ADR.

### 7. Evidências e explicação

Cada insight guarda, com dinheiro e razões como **strings decimais**:
- **Números-chave** `{rótulo, valor, período from/to}` (ex.: "Setembro (1–22) R$ 1.284,00", "Média mensal abr–ago
  (1–22) R$ 973,00", "Diferença + R$ 311,00");
- **Série mensal** de 6 meses do valor comparado, com o mês parcial marcado (D1, D2, D3, D5, D6; D7/D8 usam a evolução
  do snapshot);
- **Maiores contribuintes** (até 5): comerciantes com compras, valor e participação (D1), categorias com Δ (D2),
  cobranças (D3), a transação e o ticket médio (D4);
- **Como comparamos:** template por detector (ex.: "Comparamos o que você gastou em {categoria} entre 1 e {d} de
  {mês} com a média do mesmo período nos {m} meses anteriores ({mês inicial}–{mês final}).");
- **Limitações** (códigos com texto): `PARTIAL_MONTH` ("{mês} ainda não terminou; o valor pode mudar até o dia
  {último}"), `SHORT_HISTORY` ("a média usa só {m} meses"), `UNCATEGORIZED` ("{uc}% dos gastos estão sem categoria"),
  `ESTIMATED_SCORE` (score estimado do Health), `IMPORT_LAG` (D5: "pode ser só um extrato ainda não importado");
- **Fontes:** a porta e o período de cada número (`analytics.categories`, `analytics.spending`,
  `analytics.income-sources`, `analytics.merchants`, `transactions.largest-expenses`, `health.snapshot`,
  `overview.account-balances`) e as origens dos dados (OFX, CSV, manual), como no Health. Todo número exibido é
  rastreável até a porta que o produziu, para o mesmo período da Análise ou do Health.
- **Relacionados** (até 3): outros insights visíveis do mesmo P com a mesma categoria, comerciante ou fonte, D2 ↔ D1 da
  maior categoria, e a dimensão do Health afetada (Spending para D1/D2, Cash Flow para D5, Reserve para D9).

### 8. Feedback do usuário

- **Dispensar:** idempotente; `DISMISSED` e −30 de relevância para o mesmo detector e sujeito por 90 dias (§3).
- **Útil / Não útil:** uma escolha por insight, que pode ser trocada (vale a última). "Não útil" dá −15 ao detector por
  90 dias; "Útil" no v1 só é registrado (métrica de qualidade), sem efeito na pontuação.
- **Visto:** marcado ao abrir o detalhe; idempotente.

### 9. Apresentação (consequências para as partes C e D)

- **✦** só no rótulo "✦ Insight" do card e do detalhe (01-foundations: conteúdo "gerado ou interpretado"); Ação
  recomendada, Explicação e Próximos passos sem ✦. O "Como geramos?" diz que os insights são regras fixas sobre os
  dados do usuário, sem IA.
- **Filtros:** Gastos (D1–D4, D10) e Saúde (D7–D9); insights de **receita** (D5, D6) têm tipo `INCOME`, a tag
  "Receitas" e aparecem só em Todos/Importantes; **Cartões** visível e desabilitado com "Em breve" (estado
  Unavailable, 05-states) até o M8.
- O mês em andamento e a confiança sempre aparecem no detalhe; estimativa nunca é apresentada como fato.

### 10. Fora desta fase

- Detectores de Cartões (M8), de previsão (M9) e de metas (M10); queda de gasto e "assinaturas estáveis".
- "Insights limitados" no Free (05.4.1): não implementado; a ordenação do §3 permite um corte por entitlement na
  leitura sem mudar o modelo.
- Consentimento "gerar insights" (`D-ProfileConsents`): não existe módulo de consentimento; fica para quando ele existir.

## Alternatives

- **Período:** sempre o mês corrente (comparações com 1–3 dias no início do mês geram ruído); mês corrente e anterior
  ao mesmo tempo (insights duplicados do mesmo assunto em dois meses).
- **Base:** comparar o mês parcial com meses cheios, como o layout (o mês corrente pareceria sempre menor; diverge do
  ADR-0015 §2).
- **Pontuação:** só limiares fixos em R$ (ignora a escala de cada usuário); impacto relativo à renda (indefinido sem
  receita e instável com receita variável).
- **Fingerprint com versão:** uma nova versão ressuscitaria insights dispensados.
- **✦:** como no layout (sugere IA onde não há; inconsistente com o ADR-0017 §7) ou nenhum (diverge do DS).
- **Receitas:** chip novo (muda a UX da spec), dentro de Saúde (mistura conceitos) ou fora do v1 (perde a "Nova
  receita" do "O que mudou?").
- **Objetivo/Copilot:** omitir as ações (recomendado na proposta); aprovado o placeholder desabilitado, fiel ao layout.

## Consequences

- Insights reproduzíveis e explicáveis: cada um tem detector, limiar, números com período, fonte e versão.
- Pisos absoluto e relativo evitam o insight trivial; fingerprint, persistência e respeito à dispensa evitam o
  repetitivo.
- Os números do layout (32%, R$ 1.284,00 vs R$ 973,00) são ilustrativos; os do `insights-v1` usam o mesmo recorte de
  dias e diferem dele. O E2E da parte D usa um fixture próprio que dispara pelo menos dois detectores com valores exatos.
- A geração depende de um relógio de negócio (regra dos 7 dias); testes e E2E precisam controlar a data ou montar o
  fixture relativo a hoje.
- Até o M8, os insights de Saúde refletem um score com no máximo 70% do peso (ADR-0017 §3).

## Security / Privacy

- Detecção só sobre dados do próprio Workspace, pelas portas que já filtram por Workspace.
- Textos são templates preenchidos no backend, sem a descrição livre das transações. Nenhum dado financeiro, nome de
  comerciante ou categoria em logs, métricas ou traces (05.9/05.12); fingerprint é um hash sem dado legível.
- Nenhuma IA ou LLM envolvido.

## Financial Integrity

- Uma única semântica de fluxo, comparação, saldo e score (ADR-0006 §9, ADR-0014/0015, ADR-0017); os Insights não
  redefinem nada.
- Detectores puros, `BigDecimal` em tudo, arredondamentos definidos e testados; mês parcial e confiança sempre
  sinalizados.

## Operational Impact

- Nenhum até a parte B, que decide geração, persistência, consumidor, rebuild, runbook e freshness, e terá ADR próprio
  (métrica "insight generation", 05.9).
- O "Como geramos?" pode expor o catálogo, limiares e pesos direto das constantes do código, como o método do Health.

## Related Specs

- `specs/01-product/mvp.md`, `features.md`, `principles.md`; `specs/03-ux/ux.md`, `core-user-flows.md` (Insight)
- `specs/05-system-design/05.13-consolidated-system-design.md` (§6, §9, §14), `05.2`, `05.3`, `05.4`, `05.9`, `05.10`,
  `05.12`, `05.14`, `05.4.1-monetization.md`
- `design/specs/01-foundations.md`, `02-components.md`, `03-screens.md`, `04-navigation-flows.md`, `05-states.md`,
  `07-design-decisions.md`; layouts `D-Insights`, `D-InsightDetail`, `M-Insights`, `M-InsightDetail`, `D-Home`,
  `M-Home`, `D-TxnDetail`
- `docs/adr/ADR-0006` (§9, §10), `ADR-0012`, `ADR-0014`, `ADR-0015` (§1, §2, §4, §5, §6), `ADR-0017` (§1–§4, §7 e
  adendo), `ADR-0018`

## Date

2026-10-16
