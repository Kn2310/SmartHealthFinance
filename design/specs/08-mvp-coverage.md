# 08 · Cobertura do MVP

Referência visual: `O-Coverage`, `O-Inventory`.

| Requisito do brief | Status | Artboards |
|---|---|---|
| Home: Como estou? / O que mudou? / O que merece atenção? / O que posso fazer agora? | ✅ | D-Home, M-Home, T-Home, XL-Home |
| Home: saldo, receitas, despesas, mudanças, insights, próximos eventos, ações | ✅ | D-Home |
| Accounts: lista, detalhe, saldo, instituição, movimentações | ✅ | D-Accounts, D-AccountDetail |
| Accounts: estado vazio, adicionar, editar | ✅ | D-AccountsEmpty, D-AddAccount, D-EditAccount |
| Transactions: lista, filtros, busca, categorias, comerciantes, entrada/saída/transferência | ✅ | D-Transactions, M-Transactions, T-Transactions |
| Transactions: detalhe | ✅ | D-TxnDetail, M-TxnDetail |
| Transactions: loading, empty, error | ✅ | S-Catalog, D-TxnEmpty, D-TxnError |
| Ver por que meu dinheiro mudou | ✅ | D-Transactions, D-Analysis |
| Cards: overview (cartão, limite, utilização, fatura atual, próxima, status) | ✅ | D-Cards, M-Cards |
| Invoice: fatura atual, valor, vencimento, transações, parcelas, próxima | ✅ | D-Invoice, D-FutureInvoices, M-Invoice |
| Card detail: utilização, evolução, faturas, parcelamentos | ✅ | D-CardDetail, D-Installment |
| Financial Health: score, dimensões, explicação, evolução, fatores, atenção, próximos passos | ✅ | D-Health, M-Health |
| “Como calculamos?” | ✅ | D-HealthMethod + links em todas as métricas calculadas |
| Insights: lista, categorias, importância, card, detalhe, evidências, explicação, ação | ✅ | D-Insights, D-InsightDetail, M-Insights, M-InsightDetail |
| Analysis: visão geral, gastos, receitas, categorias, comerciantes, evolução | ✅ | D-Analysis … D-AnalysisEvolution, M-Analysis |
| Goals: lista, criação, detalhe, progresso, contribuição, previsão, simulação | ✅ | D-Goals, D-CreateGoal, D-GoalDetail, D-GoalCreated, M-Goals |
| Copilot: entrada, sugestões, respostas, dados, explicação, confiança, ações | ✅ | D-Copilot, D-CopilotChat, M-Copilot, M-CopilotChat |
| Copilot: limite, loading, erro, empty | ✅ | D-CopilotStates |
| Navigation: sidebar desktop (7) e bottom nav mobile (5) | ✅ | Todas as telas, N-Map |
| Profile: dados pessoais, instituições, contas, conexões, consentimentos, segurança, preferências | ✅ | D-Profile … D-ProfilePreferences |
| Onboarding: Welcome → Conta → Setup → Primeiros dados → Primeiro panorama | ✅ | D-Welcome, D-Login, D-Signup, D-Setup, D-FirstData, D-FirstOverview, M-Welcome, M-Setup |
| States: Loading, Empty, Error, Success, Locked/Premium, Unavailable | ✅ | S-Catalog + telas de estado |
| States da UX spec: Partial, Stale, Permission denied, Offline | ✅ | S-Catalog |
| Responsivo: Mobile, Tablet, Desktop, Large | ✅ | M-*, T-*, D-*, XL-Home, R-Rules |
| Protótipo: 3 jornadas | ✅ | N-Flows (desktop e mobile) |
| Foundations, tokens | ✅ | F-Colors, F-Typography, F-Layout, tokens/ |
| Component library (Foundation 30, Financial 15, Intelligence 9) | ✅ | C-Foundation, C-Financial, C-Intelligence |

## Em aberto (definição de produto)

- Nomes e limites de planos e quais recursos são premium.
- Metodologia de ponderação do score.
- Faixas de interpretação do score (não criadas para não inventar regra).
- Regras de senha, canal de suporte, pagamento mínimo e juros (vindos do arquivo).
