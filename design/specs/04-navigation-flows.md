# 04 · Navegação e jornadas

Referência visual: `N-Map`, `N-Flows`. Links entre telas: `screens.json` (campo `linksTo`).

## Desktop — sidebar persistente

```text
Início       /home
├── Financial Health        /home/health
├── Overview → Contas       /profile/accounts, /accounts/:id
│            → Transações   /transactions
├── Insights (atenção)      /insights/:id
├── Upcoming                → faturas
└── Quick Actions           importar · copilot · objetivo
Cartões      /cards
├── Meus cartões            /cards
├── Fatura atual            /cards/:id/invoice
├── Próximas faturas        /cards/:id/invoices
├── Parcelamentos           /cards/:id/installments/:id
└── Utilização              /cards/:id
Análise      /analysis
├── Visão geral · Gastos · Receitas · Categorias · Comerciantes · Evolução
Objetivos    /goals
├── Meus objetivos · Criar objetivo (/goals/new) · Progresso e Simulações (/goals/:id)
Insights     /insights
├── Todos · Importantes · Gastos · Cartões · Saúde (filtros)
Copilot      /copilot
├── Perguntar · Sugestões · Histórico (/copilot/:id)
Perfil       /profile
└── Dados pessoais · Instituições · Contas · Conexões · Consentimentos · Segurança · Preferências
```

**Nenhuma área principal nova.** Contas vive em Perfil › Contas e no card de saldo da Home. Transações é acessada pela Home, Análise, faturas e insights.

**Camadas (não são rotas):** detalhe de transação (drawer/sheet), “Como calculamos?” (painel lateral), adicionar/editar conta (modal), toasts.

## Mobile — bottom navigation

| Aba | Destino |
|---|---|
| Início | M-Home |
| Cartões | M-Cards |
| Análise | M-Analysis |
| IA | M-Copilot (✦ no lugar do ícone) |
| Mais | M-More → Insights, Objetivos, Saúde financeira, Contas, Transações, Perfil e configurações |

Padrões: Voltar no topo esquerdo, detalhes e filtros em bottom sheet, ação primária em botão de 48 px com largura total.

## Jornadas do protótipo

### Jornada 1 · Entender e agir
`D-Welcome → D-Signup → D-Setup → D-FirstData → D-FirstOverview → D-Health → D-Transactions → D-TxnDetail → D-AnalysisCategories → D-InsightDetail → D-CreateGoal → D-GoalCreated`

Mobile: `M-Welcome → M-Setup → M-Home → M-Health → M-Transactions → M-TxnDetail → M-InsightDetail`

### Jornada 2 · Cartão → fatura → parcela → impacto
`D-Home → D-Cards → D-Invoice → D-Installment → D-Health`

Mobile: `M-Cards → M-Invoice → M-Installment`

### Jornada 3 · Copilot
`D-Home (Perguntar ao Copilot) → D-Copilot (sugestão / pergunta) → D-CopilotChat (resposta + explicação)`

Mobile: `M-Copilot → M-CopilotChat`

## Regras de navegação

- Breadcrumb em todas as telas desktop de 2º nível ou mais; botão Voltar em telas de detalhe.
- Detalhes preservam o contexto da lista (drawer/sheet em vez de nova página).
- Todo número calculado leva a uma explicação (“Como calculamos?”, ✦ Explicação ou link de detalhe).
- Todo insight leva a uma ação (ver transações, criar objetivo, perguntar ao Copilot).
