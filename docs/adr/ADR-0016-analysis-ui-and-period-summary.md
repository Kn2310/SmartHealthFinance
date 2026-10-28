# ADR-0016 — UI de Análise e resumo do período em D-Transactions (M5, parte E)

## Status

Accepted

> Decisões aprovadas em 2026-10-13: gráficos em SVG próprio no Design System (o shadcn/ui charts foi pedido, mas
> conflita com o ADR-0007 e com a CSP; a alternativa foi apresentada e aprovada); endpoint de resumo em Transactions
> para as métricas do topo de D-Transactions; "Por que meu dinheiro mudou?" em D-Transactions só com filtro de mês
> inteiro; cabeçalho da Análise com os 12 últimos meses e três bases de comparação.

## Context

A parte E entrega as telas `/analysis/*` (layouts `D-Analysis*` e `M-Analysis`) sobre a API do ADR-0015 e fecha as
pendências de D-Transactions (backlog M2): métricas do topo do período filtrado (Entradas, Saídas, Transferências,
Resultado) e "Por que meu dinheiro mudou?". O frontend não tinha padrão de gráficos. O ADR-0007 rejeitou Tailwind e
bibliotecas de componentes, e a CSP de produção não permite estilos inline (`style-src 'self'` + nonce).

## Decision

### 1. Gráficos: SVG próprio no Design System

- Quatro componentes em `components/charts` (design/specs/02-components, "Gráficos"): `MonthBars` (barras agrupadas por
  mês, mês parcial translúcido e tracejado), `CumulativeLine` (acumulado do mês × anterior tracejado), `HorizontalBars`
  (onde foi o dinheiro) e `Waterfall` (entradas → categorias → resultado).
- Geometria só em atributos SVG (`x`, `y`, `width`, `height`, `viewBox`) e cores em classes CSS Module com tokens: nada de
  atributo `style`, compatível com a CSP (teste garante). Renderiza igual no servidor e no cliente (sem medir a tela).
- Os valores viram `number` só para calcular pixels (`geometry.ts`); todo valor exibido usa o `MoneyDto` do backend.
- Acessibilidade: o SVG é `aria-hidden`; os dados ficam numa tabela ("Ver dados de …", `details`) ou na própria lista
  textual (barras horizontais e waterfall). Sem pizza nem gauge.
- Rejeitados: shadcn/ui charts (exige Tailwind e Recharts; revogaria parte do ADR-0007 e exigiria afrouxar a CSP),
  Recharts direto (dependência de ~100 KB e estilos inline) e visx (dependência nova para o mesmo trabalho).

### 2. Telas de Análise

- Rotas `/analysis`, `/analysis/spending`, `/analysis/income`, `/analysis/categories`, `/analysis/merchants` e
  `/analysis/evolution`; abas como links (chips roláveis no mobile), uma chamada ao BFF por aba.
- Cabeçalho: "Mês" (os 12 meses até o corrente, o atual marcado como parcial) e "Comparar com" (mês anterior, média de
  3 meses, média de 6 meses), tudo na URL (`month`, `compare`) e preservado entre abas. O subtítulo segue o layout:
  "Setembro de 2026 (1 a 22) comparado a 1 a 22 de agosto".
- Parâmetros de aba na URL: `category` (detalhe selecionado), `sort` (comerciantes), `months` (evolução).
- Estados: carregando, erro com nova tentativa, mês inválido, `NO_DATA` (convite a lançar/importar),
  `NO_ACTIVITY_IN_PERIOD` (zeros reais), freshness `UPDATING`/`NOT_BUILT` e média curta (`LIMITED`) ou ausente.
- A busca de comerciantes só filtra o que está na tela (não recalcula nada).
- Fora do escopo, omitidos: "Estimado" (M9), score (M6), destaques ✦ e explicação em texto (M7/M11).
  **Adendo (2026-10-28, ADR-0030):** o M9 preenche "Projeção para o mês (Estimado)" em Gastos e "Próxima receita
  prevista (Estimado)" em Receitas. O texto passa de "Se o ritmo atual continuar" para "pelo seu padrão dos últimos N
  meses", com faixa e confiança.

### 3. Resumo do período em D-Transactions

- `GET /api/v1/workspaces/{workspaceId}/transactions/summary` (módulo Transactions), com os **mesmos filtros e
  validações da listagem** (`from`, `to`, `type`, `accountId`, `q`, `categoryCode`, `uncategorized`), sobre a fonte da
  verdade (consistência imediata, como a lista):
  - valores só de POSTED, com a semântica do ADR-0006 §9 (`Transaction.cashFlowRole()`): `income`, `expense` bruta,
    `refunds` em linha própria, `net = income + refunds − expense`, `transfers` (quantidade e volume, informativos);
  - sem filtro de conta: receitas, despesas e reembolsos só de contas ativas incluídas no total (como o Overview);
    transferências de todas as contas;
  - com filtro de conta: os valores daquela conta (mesmo fora do total ou arquivada) e as transferências que a envolvem;
  - o filtro de status não se aplica aos valores; `postedCount` e `pendingCount` informam as quantidades.
- Teste de paridade (`JdbcTransactionSummaryReaderIT`) contra a referência do domínio em 11 combinações de filtros; a
  rota está na suíte de isolamento.
- "Por que meu dinheiro mudou?" aparece em D-Transactions só quando o filtro é um mês inteiro (este mês ou o anterior)
  sem outros filtros; usa a composição do `/analytics/overview` daquele mês. Nos demais casos, um link para a análise.

### 4. Contrato

- `schema.d.ts` (ADR-0007 §11) ganha as respostas da Análise e do resumo; o teste de contrato confere com o snapshot.
- Correção da parte D: as rotas de Análise passam a declarar `@ApiResponse(200)`, sem o qual o springdoc omitia os
  schemas de resposta do OpenAPI (só a documentação mudou).

## Consequences

- Positivas: nenhuma dependência nova; CSP e ADR-0007 intactos; gráficos acessíveis por construção; o topo de
  D-Transactions bate com a lista (mesmos filtros, mesma fonte).
- Negativas: gráficos sem tooltip/animação; o "Por que meu dinheiro mudou?" de D-Transactions só existe para meses
  inteiros; a Análise é eventualmente consistente (o E2E recarrega até refletir), enquanto o resumo da lista é imediato.

## Security / Privacy

- O resumo não tem dado novo além da lista; autorização antes da validação; isolamento testado.
- A UI não registra nada; o BFF só repassa campos de validação conhecidos.

## Financial Integrity

- Toda conta é feita no backend; o frontend só formata (e converte para `number` apenas a geometria dos gráficos).
- Paridade SQL × domínio do resumo e E2E com aritmética exata em centavos (`bigint`).

## Related Specs

- `design/specs/02-components.md` (Gráficos), `03-screens.md` (ANALYSIS, D-Transactions), `05-states.md`,
  `06-responsive.md`, `07-design-decisions.md`
- `docs/adr/ADR-0006`, `ADR-0007`, `ADR-0012`–`ADR-0015`

## Date

2026-10-13
