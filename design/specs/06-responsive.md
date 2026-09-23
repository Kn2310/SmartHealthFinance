# 06 · Responsividade

Referência visual: `R-Rules`, `M-*` (390), `T-Home` e `T-Transactions` (834), `D-*` (1440), `XL-Home` (1680).
O layout se adapta ao contexto — não é o desktop encolhido.

| | Mobile < 640 | Tablet 640–1023 | Desktop 1024–1439 | Large ≥ 1440 |
|---|---|---|---|---|
| **Navegação** | Bottom nav: Início, Cartões, Análise, IA, Mais | Trilho de ícones 88 px com rótulo | Sidebar 248 px | Sidebar 248 px |
| **Grid** | 4 col · gutter 16 | 8 col · 24 | 12 col · 40 | 12 col · conteúdo máx. 1320 |
| **Home** | 1 coluna: Health → Visão geral → Insight → Mudanças → Eventos → Ações | Health e Visão geral em largura total; Mudanças + Atenção em 2 colunas | Grid 5/7, depois 2 colunas e 2/1 | Coluna lateral fixa (360) com Eventos e Ações |
| **Detalhes** | Bottom sheet com alça | Drawer 480 px | Drawer 480 px | Drawer 480 px |
| **Filtros** | Chips roláveis + sheet | Busca + segmentado em largura total; filtros em sheet | Linha única | Linha única |
| **Tabelas** | Listas de 2 linhas | Colunas essenciais | Completa | Completa |
| **Gráficos** | Largura total, máx. 6 pontos, legenda abaixo | Largura total | No card, legenda ao lado do título | Idem, sem esticar além de 1320 |
| **Copilot** | Tela cheia; histórico em tela separada | Tela cheia; histórico em sheet | Conversa + coluna de histórico 300 px | Idem |
| **Toque** | ≥ 44 px; botões lg 48 | ≥ 44 px | ≥ 40 px; foco visível | Idem |
| **Tipografia** | Títulos de página em H2 (24/32); números principais 32/40 | H1 | H1 | H1 |

Regras gerais:
- No mobile não se desenha barra de status nem teclado falsos.
- A bottom nav fica acima da área segura inferior (padding inferior 22 px).
- Conteúdo com mais de uma tela de altura rola por baixo da navegação; os artboards mobile altos representam a página inteira rolada.
