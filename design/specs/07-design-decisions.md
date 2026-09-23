# 07 · Decisões de design

Referência visual: `O-Decisions`.

1. **Health como âncora escura.** O único bloco Deep Navy da Home é o Financial Health. Ele responde “Como estou?” em ~5 segundos e concentra o contraste; o resto fica neutro (≈ 85/15).
2. **Score nunca sozinho.** 74/100 sempre vem com variação, frase explicativa, seis dimensões com texto, período, fonte e “Como calculamos?”. Pesos não definidos pela spec aparecem como placeholder.
3. **Variação neutra por padrão.** Setas e textos em cinza; laranja só com ponto de atenção explicado. Nunca verde = bom / vermelho = ruim.
4. **Trust UX com traço.** Confirmado, Importado, Estimado (≈, tracejado azul) e Simulado (pontilhado Teal). O mesmo traço aparece em números, barras e gráficos.
5. **✦ como camada, não personagem.** A IA aparece como rótulo (✦ Insight, ✦ Explicação, ✦ Copilot), sem avatar ou robô. Respostas mostram dados usados, confiança e ações.
6. **Progressive disclosure.** Resumo → Contexto → Detalhes → Ação em todas as telas; detalhes em drawer/sheet sem perder a lista.
7. **Contas e Transações sem nova área.** Respeitando a navegação da spec: Contas em Perfil › Contas e no card de saldo; Transações pela Home, Análise, faturas e insights.
8. **Tons de texto derivados.** Teal, Success e Warning puros não passam 4,5:1 em texto pequeno; usamos `#0F6B61`, `#15803D`, `#B45309` para texto.
9. **Instituições fictícias com monograma.** Nada imita marca real; cartões são visuais genéricos.
10. **Mobile é outro layout.** Bottom nav com IA no centro, Insights e Objetivos em Mais, bottom sheets, alvos de 44–48 px, uma coluna.
11. **Gráficos só quando ajudam a decidir.** Barras mensais, linha acumulada, barras horizontais, waterfall. Sem pizza ou gauge decorativo.
12. **Placeholders visíveis.** Tudo que a spec não define aparece entre colchetes.
13. **Explicação sempre ao lado do número.** Fatura (+18%) e Copilot decompõem a diferença em fatores que somam o total (R$ 311,00 + R$ 160,00 + R$ 49,60 = R$ 520,60).
14. **Simulação separada da realidade.** Simular aporte não altera o objetivo; “Aplicar” é uma ação explícita.
