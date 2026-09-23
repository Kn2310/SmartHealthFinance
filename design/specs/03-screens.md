# 03 · Telas

Cada tela segue **Resumo → Contexto → Detalhes → Ação**. Imagem em `layouts/png/<id>.png`, HTML em `layouts/html/<id>.html`.
Estrutura desktop comum: sidebar (248 px) com 7 itens, bloco de sincronização (“Atualizado há 12 min · fontes”), usuário e chip “Dados de exemplo”; cabeçalho com breadcrumb, H1, subtítulo com **período**, e ações à direita.

---

## AUTH

### D-Welcome · `/welcome`
- **Objetivo:** apresentar a proposta de valor e levar ao primeiro valor rapidamente.
- **Layout:** painel Navy à esquerda (600 px) com a promessa em Display (“Entenda seu dinheiro. Antecipe suas decisões. Cuide da sua saúde financeira.”) e ilustração abstrata de fluxo; formulário à direita (400 px).
- **Ações:** Criar conta (primária) → D-Signup · Já tenho conta → D-Login · Explorar com dados de exemplo → D-Setup.
- **Confiança:** nota sobre uso dos dados e controle de consentimentos.

### D-Login · `/login`
E-mail, senha, “Manter conectado”, “Esqueci minha senha”, Entrar → D-Home, link para Criar conta.

### D-Signup · `/signup`
Passo 1 de 3. Nome (como prefere ser chamada), e-mail, senha (`[Regras de senha a definir]`), aceite de Termos e Privacidade. Criar conta → D-Setup.

## ONBOARDING

### D-Setup · `/onboarding/setup`
- Stepper 3 passos (Conta ✓ · Configuração · Primeiros dados).
- “Como você quer começar?”: Importar extrato (Recomendado) · Adicionar manualmente · Explorar com dados de exemplo (option cards de rádio).
- “Quais produtos você usa hoje?”: checkboxes.
- “Leva cerca de 2 minutos”, Pular por enquanto → D-Home, Continuar → D-FirstData.

### D-FirstData · `/onboarding/data`
Dropzone OFX/CSV; arquivo importado (✓ 142 transações · 01 abr a 22 set) e arquivo em progresso (68%, cancelar). Alerta: “Categorizamos 131 de 142 transações… nada aqui é definitivo.” Ver meu panorama → D-FirstOverview.

### D-FirstOverview · `/home` (primeiro acesso)
Home com banner de sucesso: “Seu primeiro panorama está pronto”, revisar categorias, fechar.

## HOME

### D-Home · `/home`
Responde em ~5 segundos:

| Pergunta | Bloco |
|---|---|
| Como estou? | **Financial Health** (card Navy, 5/12): ring 74, “Seu score subiu 3 pontos desde agosto”, frase de contexto, grid das 6 dimensões, “Entender meu resultado” → D-Health, “Como calculamos?” |
| Quanto tenho? | **Visão geral** (7/12): saldo total R$ 18.420,35, distribuição por conta, Receitas / Despesas / Resultado com variação e “Por que mudou?” → D-Transactions |
| O que mudou? | **O que mudou**: 3 linhas (alimentação +32%, nova receita freelance, utilização do cartão 56%) |
| O que merece atenção? | **Merece atenção**: 2 insight cards importantes, “Ver todos” |
| O que posso fazer agora? | **Próximos eventos** (30 dias, com status Estimado/Confirmado) + **Ações rápidas** (Importar extrato, Adicionar conta, Perguntar ao Copilot, Criar objetivo) |

Cabeçalho: eyebrow com data, saudação, período comparado, seletor de período, Importar extrato.

## FINANCIAL HEALTH

### D-Health · `/home/health`
1. **Resumo:** ring 180 px, 74/100, +3 vs agosto, frase que cita as dimensões que sustentam e as que pesam, Confidence (alta · 6 meses, 3 contas, 2 cartões), gráfico de evolução abr–set.
2. **Fontes:** período, fontes (OFX, CSV, manual), atualização, aviso “setembro em andamento”.
3. **Dimensões (3×2):** Cash Flow 82, Spending 64, Credit 71, Debt 88, Reserve 68, Consistency 79 — cada uma com nome PT, barra, explicação e “Ponto de atenção” quando aplicável.
   - Reserve: “Sua reserva cobre ≈ 2,4 meses da média de despesas. Referência: 6 meses.”
4. **Principais fatores:** “Sustentando o resultado” × “Pesando no resultado” (ícones neutros + / −, sem cor de julgamento).
5. **Próximos passos:** 3 recomendações ✦ com evidência e link (reserva → objetivos; alimentação → insight; utilização → cartão).

### D-HealthMethod · painel “Como calculamos?”
Drawer 520 px: o que é o score (compara você com você mesmo), as 6 dimensões e o que cada uma observa, ponderação `[a documentar]`, período e fontes, precisão (score estimado com < 3 meses), o que não entra (investimentos, seguros, empréstimos complexos).

## ACCOUNTS

### D-Accounts · `/profile/accounts` (também via card de saldo da Home)
Saldo total em 22 set, contagem de contas/instituições/última importação, barra de distribuição, alerta “Como calculamos o saldo total”, grid de Account Cards, atalho para Cartões. Ação: Adicionar conta.

### D-AccountDetail · `/accounts/:id`
Voltar, breadcrumb, Editar / Importar extrato. Saldo em 22 set (Importado · OFX 09:14), Entradas e Saídas do mês (com saldo em 1º set), lista de movimentações com busca e filtro Todas/Entradas/Saídas.

### D-AddAccount · modal
Três opções (OFX recomendado, CSV, Manual), dropzone, aviso de indisponibilidade de Open Finance (estado Unavailable). Cancelar / Continuar.

### D-EditAccount · modal
Nome, Instituição, Tipo, switch “Incluir no saldo total”, Arquivar conta (destrutivo, histórico mantido). Cancelar / Salvar alterações.

## TRANSACTIONS

### D-Transactions · `/transactions`
- Métricas: Entradas, Saídas, Transferências, Resultado.
- **Por que meu dinheiro mudou?** Waterfall (Entradas +6.800 → Moradia → Alimentação fora → Mercado → Transporte → Assinaturas e outros → Resultado +2.953,80) + ✦ Explicação + nota de regra (compras no cartão pela data da compra; transferências entre contas próprias não são gasto).
- Filtros: busca, segmentado por tipo, Categoria, Conta ou cartão, Status.
- Lista agrupada por dia (“22 set · terça”), Carregar mais.

### D-TxnDetail · drawer 480 px (mobile: bottom sheet)
Comerciante, data/hora, valor, tipo, status + origem, dados (categoria editável, pago com, fatura vinculada, descrição original em monoespaçada), ✦ Contexto (comparação da categoria vs média com mini-barras; total no comerciante), ações: Ver insight relacionado (primária), Ver na análise, Alterar categoria.

## CARDS

### D-Cards · `/cards`
Métricas (limite total, utilizado incl. parcelas, faturas abertas), insight ✦ “fatura 18% acima do seu padrão”, um bloco por cartão (visual · fatura atual com vencimento/fechamento e status · utilização com Financial Range e compromisso da próxima fatura), resumo de parcelamentos.

### D-CardDetail · `/cards/:id`
Tabs (Visão geral, Faturas, Parcelamentos). Card visual + limite/fechamento/vencimento/fonte. Utilização 56% (era 49%), range com trecho tracejado de parcelas futuras, evolução abr–set. Faturas dos últimos 4 meses (média jul–set). Parcelamentos ativos e concluídos.

### D-Invoice · `/cards/:id/invoice`
Valor até agora (parcial, pode mudar até o fechamento), vencimento, fechamento, pagamento mínimo `[Informado no arquivo]`, comparação com média e mês anterior (+18%). ✦ Explicação dos R$ 520,60 (3 fatores) + confiança + Perguntar ao Copilot. Transações da fatura (parcelamento destacado → D-Installment), por categoria, próxima fatura (comprometido confirmado + estimativa).

### D-FutureInvoices · `/cards/:id/invoices`
Barras de valor já comprometido por mês (nov/26 a jun/27), alerta de que novas compras não aparecem (estimativa separada), tabela por mês com parcela e status.

### D-Installment · `/cards/:id/installments/:id`
Andamento em 10 blocos (pagas Navy, atual Teal, futuras tracejadas), dados da compra. **Impacto financeiro:** 14% da fatura atual · ≈ 7% da renda mensal · 28% de utilização sem parcelas futuras. Linha do limite reservado ao longo dos meses. ✦ Na sua saúde financeira: considerado em Credit e Debt.

## ANALYSIS

Tabs: Visão geral · Gastos · Receitas · Categorias · Comerciantes · Evolução. Período e “Comparar com” no cabeçalho.

| Tela | Conteúdo |
|---|---|
| **D-Analysis** `/analysis` | Receitas, Despesas, Resultado; barras de 6 meses (set parcial tracejado); Por que meu dinheiro mudou?; principais categorias; principais mudanças |
| **D-AnalysisSpending** `/analysis/spending` | Total, média por dia, projeção do mês (Estimado); gasto acumulado set × ago com leitura em texto; maiores gastos; categorias |
| **D-AnalysisIncome** `/analysis/income` | Receitas do mês, média, próxima receita prevista (Estimado); fontes (salário recorrente, freelance pontual); receitas por mês |
| **D-AnalysisCategories** `/analysis/categories` | Lista completa (valor/variação) + detalhe da categoria selecionada (histórico, compras, ticket médio, maior comerciante, ações) |
| **D-AnalysisMerchants** `/analysis/merchants` | Destaque ✦; tabela ranqueada: comerciante, categoria, compras, total, variação vs média |
| **D-AnalysisEvolution** `/analysis/evolution` | Barras de 6 meses; tabela mês a mês (receitas, despesas, resultado, score); linha do score |

## INSIGHTS

### D-Insights · `/insights`
Filtros Todos (6) · Importantes (2) · Gastos · Cartões · Saúde. Seções “Importantes” e “Outros insights”.

### D-InsightDetail · `/insights/:id`
Título = a frase do insight. Tags (Insight, Importante, Gastos, data). Resumo (set, média, diferença), Evidências (barras mensais + onde foi o gasto), Explicação + alerta “setembro ainda não terminou” + fontes. Coluna lateral: **Ação recomendada** (Ver as 25 transações · Criar objetivo de economia · Perguntar ao Copilot), Confiança, Relacionados.

## GOALS

| Tela | Conteúdo |
|---|---|
| **D-Goals** `/goals` | Métricas (guardado, aportes/mês, quantos no ritmo), sugestão ✦ de simulação, Goal Cards, aportes recentes |
| **D-GoalDetail** `/goals/:id` | Progresso (R$ 4.300 de 12.000, 36%), prazo, aporte, previsão (Estimado) e status “1 mês após o prazo”; gráfico real + projeção + linha de prazo; histórico de aportes. **Simulation Card interativo** (R$ 700 / 780 / 900 → ago/jul/jun 2027) com “Aplicar ao objetivo” |
| **D-CreateGoal** `/goals/new` | Origem (“Criado a partir do insight…”), nome, valor alvo, já guardado, prazo, aporte mensal, conta de origem; prévia Simulada ao vivo (mês, dentro do prazo, aportes necessários, peso na renda) |
| **D-GoalCreated** | Lista com o novo objetivo em primeiro + toast de sucesso (“Ver objetivo”) |

## COPILOT

### D-Copilot · `/copilot`
✦ Financial Copilot, pergunta “O que você quer entender sobre seu dinheiro?”, escopo de dados (abr–set) e aviso “as decisões são suas”; campo de pergunta; sugestões; o que o Copilot faz / não faz. Coluna direita: Nova pergunta, Histórico, uso no mês (`[LIMITE DO PLANO]`).

### D-CopilotChat · `/copilot/:id`
Pergunta do usuário (bolha neutra à direita) → **AI Response** (sem avatar): resumo com números, tabela de fatores com total, “Como cheguei a isso”, aviso de fatura parcial, Confiança, Dados usados, ações (Ver fatura, Ver alimentação fora, Ver parcelamento, Copiar). Sugestões de continuação, campo de pergunta e aviso “O Copilot pode errar. Confira os dados usados.”

## PROFILE

Layout comum: sub-navegação à esquerda (avatar, 7 seções, Sair) + conteúdo até 760 px.

| Tela | Conteúdo |
|---|---|
| **D-Profile** `/profile` | Nome, como prefere ser chamada, e-mail, celular |
| **D-ProfileInstitutions** | Instituições com contas/cartões, fonte e última importação |
| **Contas** | → D-Accounts |
| **D-ProfileConnections** | Importação de arquivos (ativa), Entrada manual (ativa), Open Finance (Em breve · estado Unavailable) |
| **D-ProfileConsents** | Switches com data de concessão: gerar insights, Copilot usar dados, guardar histórico do Copilot; alerta sobre revogação |
| **D-ProfileSecurity** | Senha, verificação em duas etapas, sessões ativas |
| **D-ProfilePreferences** | Idioma, moeda, período padrão das análises, base de comparação |

## MOBILE (390 px)

| Tela | Notas |
|---|---|
| M-Welcome, M-Setup | Tela cheia Navy; setup com barra de progresso e botões de 48 px no rodapé |
| M-Home | Health hero → Visão geral → Insight → O que mudou → Próximos eventos → Ações rápidas em 4 tiles |
| M-Health | Ring centralizado, evolução, dimensões empilhadas, próximos passos |
| M-Transactions / M-TxnDetail | Busca, chips, resumo + atalho “Por que meu dinheiro mudou?”; detalhe em bottom sheet |
| M-Cards / M-Invoice / M-Installment | Cartão visual em largura total, fatura com ✦ explicação, impacto em 3 indicadores |
| M-Analysis | Chips no lugar de tabs, barras de 6 meses, por que mudou, mudanças |
| M-More | Perfil, Insights (badge), Objetivos, Saúde, Contas, Transações e itens de Perfil |
| M-Insights / M-InsightDetail | Cards compactos; detalhe com ação em botões de largura total |
| M-Goals | Resumo, goal cards, sugestão de simulação, Criar objetivo |
| M-Copilot / M-CopilotChat | Sugestões em lista de 52 px, histórico, campo fixo acima da navegação |

## TABLET E LARGE

- **T-Home / T-Transactions (834):** sidebar em trilho de ícones (88 px), blocos em largura total, 2 colunas onde cabe.
- **XL-Home (1680):** conteúdo limitado a 1320 px; coluna direita fixa (360) com Próximos eventos e Ações rápidas.
