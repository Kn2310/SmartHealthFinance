# 02 · Component Library

Referência visual: `C-Foundation`, `C-Financial`, `C-Intelligence`.
Todos os componentes têm estados consistentes (default, hover, focus, pressed, disabled, loading quando aplicável) e usam elementos nativos.

## Foundation

| Componente | Anatomia / variantes | Estados e regras |
|---|---|---|
| **Button** | Primary (Navy/branco), Secondary (branco, borda strong), Ghost, Soft, Danger (texto `#B91C1C`), White (sobre navy), Link. Tamanhos sm 36 / md 40 / lg 48. Raio 10, 14–16 px/600, ícone 18–20 px à esquerda e/ou chevron à direita | Default, hover, focus, pressed, disabled (opacidade .45), loading (spinner inline). **Um primário por área.** Mobile: lg, largura total |
| **Icon Button** | 36–44 px, raio 10, variantes secondary/ghost/soft/primary | `aria-label` obrigatório |
| **Input** | Label 13/600 acima, campo 44 px, ícone opcional à esquerda, hint 12 px | Default, focus (borda Info + ring 3 px `#EAF1FE`), erro (borda Danger + ícone + texto que diz como resolver), disabled |
| **Financial Input** | Prefixo “R$” 18 px, valor 28/600 tabular, atalhos em chips (R$ 500 / 1.000 / 5.000) | Teclado numérico, máscara pt-BR |
| **Select** | Botão 40 px com valor + chevron | Abre listbox |
| **Combobox** | Select + digitação; listbox em popover (nível 2), opção ativa em Subtle + check | Filtra ao digitar |
| **Checkbox / Radio** | 20 px, marcado em Navy; linha mínima 32 px | Input nativo oculto + visual custom |
| **Switch** | 42×24, trilho Navy (on) / `#CBD5E1` (off) | `role="switch"`, `aria-checked`; label + descrição à esquerda |
| **Date Range** | Dois selects com calendário + presets em chips (Este mês, Mês passado, 3 meses, 6 meses) | Período sempre visível no cabeçalho das telas |
| **Filter** | Chips 36 px, raio 8, ativo em Navy com contagem; chip aplicado removível (“Alimentação fora ×”) + “Limpar filtros” | Mobile: chips roláveis + bottom sheet |
| **Search** | Campo 40–44 px com lupa, placeholder descreve o que buscar | — |
| **Tabs** | Sublinhado Navy 2 px no ativo, 44 px de altura; usado em Análise e Detalhe do cartão | `aria-current="page"` |
| **Segmented control** | Trilho Subtle, item ativo branco com sombra leve | Todas / Entradas / Saídas / Transferências |
| **Breadcrumb** | 13 px, chevrons, último item em Text 600 | Desktop. No mobile vira botão Voltar |
| **Card** | Surface, borda 1 px Border, raio 16, sombra nível 1, padding 24 (mobile 16) | Pode ser link inteiro (sem links internos) |
| **Metric Card** | Rótulo 13 Muted (+ ícone) → valor 24/600 → variação/contexto | Sempre com período ou referência |
| **Badge** | 22 px, raio 6, 12/600, tons: neutral, teal, info, success, warning, danger, navy | Badge = status |
| **Tag / Chip** | 36 px, raio 8 | Tag = atributo ou filtro |
| **Avatar** | Pessoa: circular com iniciais. Instituição: monograma quadrado raio 12 | Sem logos de terceiros |
| **Progress** | Trilho Track, preenchimento Teal ou Navy, raio pill; variante com trecho tracejado (futuro/estimado) e com marcador de referência | — |
| **Divider** | 1 px Border | — |
| **Toast** | Navy, raio 12, ícone de status, texto, ação (“Desfazer”, “Ver”) | Rodapé central, 4 s, `role="status"` |
| **Alert** | Fundo tint, ícone, título 14/600, corpo, ação opcional | Tons info, warning, danger, success, neutral |
| **Tooltip** | Navy, 12/16, máx. 220 px | Para definições curtas |
| **Popover** | Surface, nível 2, título + texto + link | Para explicações com ação |
| **Skeleton** | Blocos na forma final, gradiente Track → `#F5F7FA` | Nunca spinner em blocos de conteúdo |
| **Empty** | Tile de ícone 48–64, título, explicação (o que está vazio / por quê), ação | — |
| **Error** | Tile Danger, título sem jargão, o que foi preservado, ação (Tentar novamente) | Sem códigos técnicos |
| **Modal** | 560–600 px, raio 20, nível 3, cabeçalho com título + fechar, rodapé com ações à direita | Desktop |
| **Bottom Sheet** | Largura total, raio 20 no topo, alça 40×4, fechar | Mobile: detalhes e filtros |

## Financial

| Componente | Descrição | Onde |
|---|---|---|
| **Financial Health** | Ring Teal (rotação −90°, cap arredondado), número 30% do diâmetro, “de 100”. Sempre acompanhado de variação, frase explicativa, mini-grid das 6 dimensões, fonte/período e “Como calculamos?”. Versão hero em Navy na Home | D-Home, D-Health, M-Home, M-Health |
| **Balance Card** | Saldo total 40/48 + “em 3 contas · cartões não entram no saldo” + barra de distribuição por conta com legenda % | D-Home, D-Accounts |
| **Account Card** | Monograma, instituição, tipo, saldo 24/600, status (Importado/Manual) + fonte e hora | D-Accounts |
| **Credit Card** | Visual genérico Navy (ou slate), anéis Teal sutis, instituição, nome, final 4 dígitos. Sem bandeira real | D-Cards, D-CardDetail, M-Cards |
| **Invoice Card** | Mês, vencimento, valor, badge de status (Aberta/Paga), comparação com média | D-Cards, D-Invoice |
| **Transaction Item** | Tile de categoria (tint por tipo: entrada verde, transferência azul, saída neutro) · comerciante · categoria · conta · status · valor + ícone + rótulo do tipo. Altura mínima 56 | Listas de transações |
| **Goal Card** | Ícone Teal, nome, prazo, badge de status, guardado/alvo, progresso, % e previsão (Estimado) | D-Goals, M-Goals |
| **Spending Breakdown** | Linhas: ícone, categoria, valor, barra relativa, % das despesas e variação vs média. Categoria em atenção pinta a barra em Warning | Análise, Fatura |
| **Financial Timeline** | Bloco de data (dia + mês), título, contexto, status, valor | Home (Próximos eventos) |
| **Forecast Card** | “Previsão · ritmo atual”, mês resultante, badge Estimado, progresso | Goals |
| **Financial Status** | Confirmado / Importado / Manual / Estimado / Simulado + Aberta / Paga / Ponto de atenção | Todo lugar |
| **Financial Variation** | Ícone de tendência + texto com referência. Tons: neutral (padrão), attention (Warning escuro), positive (uso raro) | Métricas |
| **Financial Indicator** | Número grande + unidade + base do cálculo (“14% · da fatura atual · R$ 480 de R$ 3.412,80”) | D-Installment |
| **Financial Comparison** | Barras horizontais lado a lado (atual × média × mês anterior) com valores | D-Invoice, D-TxnDetail |
| **Financial Range** | Usado/disponível, barra Navy com trecho tracejado para parcelas futuras, % do limite | Cartões |

### Gráficos (quando usar)

| Pergunta | Forma |
|---|---|
| Comparar meses (receitas × despesas) | Barras agrupadas; mês parcial com barra translúcida tracejada |
| Ritmo dentro do mês | Linha acumulada, mês atual sólido × anterior tracejado cinza |
| Onde foi o dinheiro | Barras horizontais ordenadas |
| Por que o saldo mudou | Waterfall horizontal (entradas → categorias → resultado) |
| Evolução do score / utilização | Linha com área leve e último ponto rotulado |
| Previsão de objetivo | Linha sólida (real) + tracejada (projeção) + linha vertical de prazo |

Sem pizza, sem gauges decorativos, máximo de 6 pontos no mobile.

## Intelligence

| Componente | Descrição |
|---|---|
| **Insight Card** | `✦ Insight · Categoria` + badge Importante/Informativo, frase humana com número e referência (“Seus gastos com alimentação fora de casa aumentaram 32% neste mês.”), apoio, meta (data), “Ver detalhes” |
| **Insight Detail** | Resumo (números-chave com período) → Evidências (gráfico + maiores contribuintes) → Explicação (como comparamos + limitações) → Ação recomendada + Confiança + Relacionados |
| **AI Response** | Sem avatar. Rótulo ✦ Copilot, resposta 18/28 com números em negrito, tabela de fatores com total, bloco “Como cheguei a isso” (recolhível), Confidence, “Dados usados” em badges, ações (primária + secundárias), copiar |
| **AI Suggestion** | Chips com ✦ e pergunta pronta, contextual aos dados do usuário |
| **Recommendation** | ✦ + título + evidência + link de ação. Sugere, não manda |
| **Explanation** | Bloco Background com `✦ Explicação` e texto curto |
| **Confidence Indicator** | 3 segmentos (Teal/Track) + “Confiança alta/média/baixa · base (ex.: 6 meses de dados)” |
| **Simulation Card** | Borda pontilhada Teal, badge Simulado, opções de valor, resultado (mês), status vs prazo, nota “não altera seu objetivo; sem rendimento” |
| **“Como calculamos?”** | Link com circle-help em Info; abre painel lateral: o quê, dimensões/regra, período e fontes, precisão, o que não entra |

### Tom de voz

Uma pessoa organizada que entende de finanças. Frases curtas, número + referência, sem julgamento.

| Evitar | Usar |
|---|---|
| Anomalia transacional detectada. | Seus gastos com alimentação fora de casa aumentaram 32% neste mês. |
| Erro 500. | Não conseguimos atualizar agora. Seus dados estão seguros. |
| Você gastou demais! | Alimentação fora está 32% acima da sua média. |
