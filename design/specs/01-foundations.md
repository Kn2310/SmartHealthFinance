# 01 · Design Foundations

Referência visual: `F-Colors`, `F-Typography`, `F-Layout`. Valores de máquina: `tokens/tokens.json` e `tokens/tokens.css`.

## Cores

Filosofia: **≈ 85% neutros, 15% acentos.** Cor de destaque só com intenção: o score, a ação primária, a marca ✦.

### Marca

| Token | Hex | Uso |
|---|---|---|
| Deep Navy | `#0B1220` | Botão primário, card de Financial Health, cartão visual, toasts |
| Intelligent Teal | `#16B8A6` | Ring do score, barras de progresso, marca ✦. **Não usar como cor de texto pequeno** (contraste 2,6:1) |
| Soft Blue | `#4F7CFF` | Visualização de dados secundária (distribuição de contas) |

### Neutros

| Token | Hex | Uso |
|---|---|---|
| Background | `#F7F8FA` | Fundo da aplicação |
| Surface | `#FFFFFF` | Cards, modais, drawers, sheets |
| Text | `#0F172A` | Texto principal |
| Text secondary · derivado | `#334155` | Parágrafos de apoio |
| Muted | `#64748B` | Metadados, legendas (4,8:1 sobre branco) |
| Border · derivado | `#E6E9EF` | Contorno de cards, divisores |
| Border strong · derivado | `#D6DBE3` | Inputs, botões secundários |
| Subtle · derivado | `#F1F3F6` | Item de navegação ativo, fundo de ícones |
| Track · derivado | `#EDF0F4` | Trilho de barras e rings |

### Semânticas

As cores oficiais são usadas em ícones, barras e fundos. **Para texto** usamos tons escurecidos (≥ 4,5:1):

| Semântica | Cor oficial | Texto | Fundo (tint) |
|---|---|---|---|
| Teal / IA | `#16B8A6` | `#0F6B61` | `#E6F6F4` |
| Success | `#16A34A` | `#15803D` | `#EAF6EE` |
| Warning | `#D97706` | `#B45309` | `#FDF3E4` |
| Danger | `#DC2626` | `#B91C1C` | `#FDECEC` |
| Info | `#2563EB` | `#1D4ED8` | `#EAF1FE` |

### Regra: cor nunca é o único sinal

Variações sempre usam **ícone (tendência) + sinal + texto com referência**: `↗ +14,1% vs mesmo período de agosto`.
Variações são **neutras (cinza) por padrão**. Laranja (Warning) só quando existe um ponto de atenção explicado. Nunca verde = bom / vermelho = ruim.

## Tipografia

| Estilo | Tamanho/linha | Família · peso | Uso |
|---|---|---|---|
| Display | 40/48 | Manrope 700, −0,02em | Onboarding, capas |
| H1 | 32/40 | Manrope 700, −0,02em | Título de página |
| H2 | 24/32 | Manrope 700, −0,01em | Seções, modais |
| H3 | 20/28 | Manrope 700 | Títulos de card |
| Body L | 18/28 | Inter 400 | Respostas do Copilot |
| Body | 16/24 | Inter 400 | Texto padrão |
| Body S | 14/20 | Inter 400 | Listas, tabelas, texto de apoio |
| Caption | 12/16 | Inter 500–600 | Metadados, fontes, datas, eyebrows |

**Números:** Inter com `font-feature-settings: 'tnum'` (aplicado globalmente). Escalas numéricas: 40/48, 32/40, 24/32, 20/28, peso 600.

**Formatação monetária (pt-BR):**
- Saída: `R$ 86,40` (sem sinal, com rótulo “Saída” + ícone)
- Entrada: `+ R$ 300,00` (texto verde-escuro + ícone + rótulo “Entrada”)
- Negativo em contexto de cálculo: `− R$ 1.986,32` (sinal de menos tipográfico)
- Estimado: `≈ R$ 2.900,00` com sublinhado tracejado azul

## Espaçamento

Base 4: `4 · 8 · 12 · 16 · 20 · 24 · 32 · 40 · 48 · 64 · 80`.
Gutters: 16 (mobile), 24 (tablet), 40 (desktop). Gap entre cards: 24 desktop, 16 mobile. Padding de card: 24 desktop, 16–20 mobile.

## Raio

| Token | px | Uso |
|---|---|---|
| xs | 6 | Badges, tags de status |
| sm | 8 | Chips, filtros, mini-métricas |
| md | 12 | Tiles de ícone, alertas, inputs grandes |
| — | 10 | Botões e inputs |
| lg | 16 | Cards |
| xl | 20 | Modais, bottom sheets, AI Response |

## Grid e breakpoints

| Breakpoint | Faixa | Colunas | Gutter |
|---|---|---|---|
| Mobile | < 640 | 4 | 16 |
| Tablet | 640–1023 | 8 | 24 |
| Desktop | 1024–1439 | 12 | 40 (máx. 1440) |
| Large | ≥ 1440 | 12 | conteúdo máx. 1320 |

Estrutura desktop: sidebar 248 px + área principal com padding `28px 40px 40px`.

## Elevação

| Nível | Sombra | Uso |
|---|---|---|
| 0 | none | Superfícies no fundo |
| 1 | `0 1px 2px rgba(11,18,32,.04)` + borda 1 px | Cards |
| 2 | `0 8px 24px rgba(11,18,32,.12)` | Popover, toast, drawer |
| 3 | `0 24px 64px rgba(11,18,32,.24)` | Modal |

Overlay de modal/drawer: `rgba(11,18,32,.40)`.

## Movimento

| Tipo | Duração | Curva | Aplicação |
|---|---|---|---|
| Micro | 150–200 ms | ease-out | Hover, press, switch, checkbox, chip |
| Componente | 200–300 ms | `cubic-bezier(0.2, 0, 0, 1)` | Tabs, accordion “Como cheguei a isso”, tooltip, toast |
| Maior | 300–400 ms | `cubic-bezier(0.2, 0, 0, 1)` | Drawer, modal, bottom sheet, troca de tela |
| Dados | 300 ms | ease-out | Ring e barras crescem uma vez ao carregar; sem loops |
| Reduzido | 0 ms | `prefers-reduced-motion` | Deslocamento vira fade curto |

## Ícones

Lucide, stroke 1,75 px, tamanhos 16 / 20 / 24 / 32. Sem emojis na interface.
Ícones usados: home, credit-card, chart-column, target, lightbulb, user, search, list-filter, arrow-down-left (entrada), arrow-up-right (saída), arrow-left-right (transferência), calendar, wallet, landmark, shield-check, layers (parcelamentos), upload, download, circle-help (“Como calculamos?”), triangle-alert, circle-check, lock, cloud-off, wifi-off, entre outros.

### Linguagem de inteligência

- **✦** em Teal identifica conteúdo gerado ou interpretado: `✦ Insight`, `✦ Explicação`, `✦ Copilot`, `✦ Contexto`, `✦ Ação recomendada`.
- Nunca robô, avatar, brilho animado ou gradiente “futurista”.

### Status do dado (Trust UX)

| Status | Visual | Quando |
|---|---|---|
| Confirmado | badge verde + circle-check | Dado validado (fatura paga, parcela contratada, transferência própria) |
| Importado | badge neutro + download | Veio de OFX/CSV |
| Manual | badge neutro + lápis | Inserido pelo usuário |
| Estimado | badge azul, **borda tracejada**, “≈” | Projeções e valores parciais |
| Simulado | badge Teal, **borda pontilhada** + sliders | Resultado de simulação — nunca altera dados reais |

O mesmo traço (tracejado/pontilhado) acompanha o valor em números, barras e gráficos.

## Acessibilidade

- Contraste de texto ≥ 4,5:1 (≥ 3:1 a partir de 24 px).
- Alvos de toque ≥ 44 px no mobile (botões primários 48 px), ≥ 40 px no desktop.
- Elementos nativos: `button`, `a`, `input` + `label`; botões só com ícone levam `aria-label`.
- Foco visível: outline 2 px `#2563EB`, offset 2 px.
- Gráficos com `role="img"` e `aria-label`; sempre com o valor principal também em texto.
