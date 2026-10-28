# ADR-0015 — API de Analytics (M5, parte D)

## Status

Accepted

> Decisões aprovadas em 2026-10-13: uma rota por aba, aninhada por Workspace; período = mês civil com duas bases de
> comparação no mesmo recorte de dias; despesa bruta e reembolso em linha própria; "Por que meu dinheiro mudou?" =
> composição do período + decomposição da variação; "Estável" = |variação| &lt; 5%; média a partir do primeiro
> lançamento, com zeros contando; fonte de receita recorrente = 3 ou mais dos últimos 6 meses.

## Context

As abas de Análise (`design/specs/03-screens.md`, layouts `D-Analysis*`) mostram totais, variações "vs agosto" e
"vs média de abr–ago", um "Por que meu dinheiro mudou?", maiores gastos, fontes de receita, categorias, comerciantes e
evolução. A projeção do ADR-0014 fornece os agregados; faltava definir as rotas, o período, as bases de comparação e
as regras de variação, composição e recorrência, sem duplicar a semântica do ADR-0006 §9 e sem IA.

Fora do escopo (outros marcos): projeção do mês e próxima receita prevista ("Estimado", M9 Forecast); score e linha
do score (M6 Health); destaques ✦, explicação em texto e Insights (M7/M11).

**Adendo (2026-10-28, ADR-0030 §3.1–§3.2):** a projeção do mês e a próxima receita prevista são do módulo `forecast`,
que lê `AnalyticsReadModel.entries` e reaproveita a chave de fonte de `IncomeSources`. O contrato de Analytics não
muda, e o `RECURRING` daqui continua uma heurística descritiva, distinta das recorrências do Forecast.

## Decision

### 1. Cálculo

- Todas as queries carregam, numa leitura da projeção, as linhas do fluxo de caixa (`FlowEntry`: dia × papel ×
  categoria × comerciante; só INCOME, EXPENSE e REFUND de contas ativas incluídas no total) do intervalo inteiro de que
  precisam (período, bases e histórico), e calculam no domínio (`FlowLedger`, `CashFlowComposition`,
  `ChangeBreakdown`, `IncomeSources`). O frontend só formata.
- Dinheiro em `Money`/BigDecimal. Divisões só onde a regra pede, sempre com **2 casas, HALF_EVEN** (médias, média por
  dia, ticket médio). Percentuais com **1 casa, HALF_EVEN**, trafegando como string.
- "Maiores gastos" lê despesas individuais de `transactions` (mesma regra de contas), não da projeção.
- Teste de paridade (`JdbcAnalyticsProjectionIT`) cobre também `entries`, `firstActivity` e `largestExpenses`.

### 2. Período e bases de comparação

- `month=YYYY-MM` (padrão: mês corrente no fuso de negócio do ADR-0006 §10). O mês corrente é **parcial** até hoje;
  mês futuro → `400 month FUTURE`.
- Duas bases, sempre calculadas e **no mesmo recorte de dias** (com o mês parcial até o dia 22, as bases vão de 1 a 22,
  limitadas ao tamanho de cada mês; com o mês cheio, meses cheios):
  - `previous`: o mês anterior;
  - `average`: média mensal dos N meses anteriores (`averageMonths`, 1–12, padrão 5).
- `compareWith=PREVIOUS|AVERAGE` (padrão PREVIOUS) escolhe a base da decomposição; as duas variações vêm em toda
  métrica (`vsPrevious`, `vsAverage`).
- Diverge dos números do layout, que comparam o mês parcial com meses cheios; o recorte igual evita que o mês corrente
  pareça sempre menor que a média.

### 3. Variação

- `difference = atual − base` (exata); `percent = difference ÷ |base| × 100`; `trend`:
  - `STABLE` se |difference| × 100 &lt; 5 × |base| (sem arredondar);
  - `UP`/`DOWN` acima disso; `NEW` com base zero e atual positivo; `GONE` com base positiva e atual zero.
- `trend` só descreve a direção; se é bom ou ruim depende do que se compara e fica com a UI.

### 4. Histórico insuficiente e estados

- A média usa só os meses a partir do mês do primeiro lançamento do Workspace; dentro deles, mês sem movimento conta
  como zero. `comparison.average.monthsUsed` informa quantos.
  - `state`: `AVAILABLE` (3 ou mais), `LIMITED` (1–2) ou `INSUFFICIENT_DATA` (nenhum, variações nulas).
  - `previous` é nulo se o mês anterior é anterior ao primeiro lançamento.
- `state` da resposta: `NO_DATA` (nenhuma movimentação no fluxo de caixa; blocos de dados nulos),
  `NO_ACTIVITY_IN_PERIOD` (zeros reais) ou `READY`. `period.partial` indica o mês parcial. Toda resposta traz a
  freshness da projeção (ADR-0014 §6).

### 5. "Por que meu dinheiro mudou?" (determinístico, sem texto gerado)

- **Composição do período** (`composition`): entradas (receitas + reembolsos), saídas por categoria (as 4 maiores,
  "Sem categoria" como linha própria quando estiver entre elas, e as demais somadas em `OTHERS`) e o resultado.
  Fecha exatamente: `inflows − Σ outflows = net`.
- **Decomposição da variação** (`changes`) contra a base escolhida: Δ receitas, Δ reembolsos e Δ de cada categoria de
  despesa (impacto com o sinal invertido), cada categoria com os 3 comerciantes que mais contribuíram. Fecha
  exatamente: `Σ impact = netDelta`. Com a média, o resultado-base é a soma dos componentes médios arredondados (pode
  diferir em centavos da média do resultado calculada à parte, nunca da soma dos itens). Nula sem base.

### 6. Regras por aba

- **Reembolso:** despesa sempre **bruta** (como o Overview, ADR-0006 §9); cada categoria traz `refunds` e o resumo traz o
  total de reembolsos. O resultado continua receitas + reembolsos − despesas.
- **Gastos:** média por dia = despesa ÷ dias decorridos; acumulado diário do mês e do mês anterior inteiro; 5 maiores
  despesas; categorias.
- **Receitas:** fonte = comerciante (sem comerciante, a categoria). `RECURRING` se houve receita dela em 3 ou mais dos 6
  meses até o período (inclusive), com `typicalDay` = dia do mês mais frequente (empate: o menor); senão `OCCASIONAL`.
  Média mensal = média da base. 6 meses.
- **Categorias:** despesas do mês com participação, contagem, reembolsos e variações. **Detalhe** de qualquer categoria
  do catálogo (despesa ou receita): valor, compras, ticket médio, 6 meses e 5 maiores comerciantes; código fora do
  catálogo → `400 categoryCode INVALID`.
- **Comerciantes:** ranking por `AMOUNT` (padrão) ou `COUNT`, categoria principal (a de maior valor), compras, total,
  variações (a média do comerciante conta zeros) e última compra. Despesas sem comerciante ficam fora do ranking e
  vêm em `unidentified`.
- **Evolução:** `months` (1–12, padrão 6) até o mês analisado, com receitas, despesas, reembolsos e resultado; o mês
  parcial é marcado.

### 7. API

`GET /api/v1/workspaces/{workspaceId}/analytics/{overview|spending|income|categories|categories/{categoryCode}|merchants|evolution}`,
mais o `status` do ADR-0014.
- Autorização antes de qualquer validação (ADR-0003): não membro → `404 WORKSPACE_NOT_FOUND` mesmo com parâmetro
  inválido.
- Erros: `400 VALIDATION_FAILED` (`month` INVALID/FUTURE, `compareWith` INVALID, `averageMonths` OUT_OF_RANGE,
  `sort` INVALID, `months` OUT_OF_RANGE, `categoryCode` INVALID).
- Schemas com prefixo `Analysis` para não colidir no OpenAPI (ADR-0010).
- Observabilidade: `Timer shf.analytics.query` (tags `view`, `state`) e log `Analytics served workspaceId view state`,
  sem valores, nomes ou códigos de categoria (teste).

## Alternatives

- **Endpoint consolidado:** payload grande; abas carregariam dados de todas.
- **Comparar o mês parcial com meses cheios (como o layout):** o mês corrente pareceria sempre menor que a média.
- **Período livre (from/to):** menos natural para média mensal e evolução; pode vir depois sem mudar as rotas.
- **Reembolso líquido por categoria:** categoria poderia ficar negativa e divergiria do Overview.
- **Só composição ou só variação:** o layout pede as duas leituras.
- **Limiar de 10% ou sem classificação:** perde o "−8%" do layout ou joga a regra para o frontend.
- **Média só dos meses em que o item aparece / N fixo:** inflaria gastos esporádicos / subestimaria usuários novos.

## Consequences

- Positivas: números determinísticos e auditáveis, testados com os valores de `09-dados-de-exemplo` (+4,6%, +14,1%,
  R$ 174,83 e R$ 153,28 por dia, 43% da renda não gasta); a UI só formata.
- Negativas: os percentuais divergem dos números de exemplo do layout onde ele compara parcial com cheio; a média com
  poucos meses é sinalizada, não escondida; "Recorrente" é uma heurística simples (sem previsão).

## Security / Privacy

- Sempre por Workspace; as 7 rotas estão na suíte `WorkspaceIsolationIT`.
- Logs e métricas sem valores, descrições, nomes de comerciante ou categorias (teste no `AnalyticsApiIT`).
- Respostas trazem descrições e nomes de comerciante do próprio usuário, como `/transactions`.

## Financial Integrity

- Mesma semântica do ADR-0006 §9 (via projeção do ADR-0014 e `CashFlowRole`); só POSTED; transferência e ajuste fora.
- Composição e decomposição fecham exatamente (testes de propriedade com arredondamento da média).

## Operational Impact

- Sem migration nem dependência nova; snapshot OpenAPI atualizado.

## Related Specs

- `design/specs/03-screens.md` (ANALYSIS), `design/layouts/html/D-Analysis*.html`, `design/specs/09-dados-de-exemplo.md`
- `docs/adr/ADR-0006-overview-module.md` (§9, §10), `ADR-0014-analytics-read-model.md`, `ADR-0013`, `ADR-0010`

## Date

2026-10-13
