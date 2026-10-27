# ADR-0020 — UI do Financial Health (M6, parte E)

## Status

Accepted

> Decisões aprovadas em 2026-10-16: card "Como estou?" sempre visível na Home (inclusive sem score); card sempre do
> mês corrente, independente do seletor de período; "Detalhes" de cada dimensão expandem no próprio card; rótulos e
> destinos dos próximos passos; tokens `on-dark` adicionados ao Design System em `design/tokens`.

## Context

As partes C e D (ADR-0018/0019) entregaram o snapshot e a API. Faltava a UI: o card "Como estou?" da Home (D-Home,
M-Home), a página `/home/health` (D-Health, M-Health), o drawer "Como calculamos?" (D-HealthMethod) e o score na aba
Evolução da Análise (D-AnalysisEvolution). Os layouts cobrem a forma; alguns comportamentos não estavam definidos, e o
card Navy usa cores sobre fundo escuro que não existiam como tokens.

## Decision

1. **BFF:** `/api/bff/health/{summary|evolution|method|status}` repassa uma chamada ao backend com o Workspace da
   sessão; só `month` (YYYY-MM) e `months` (1–12) passam; falhas viram códigos estáveis (mesma tradução da Análise).
2. **Card "Como estou?"** (único bloco Navy, 07 §1): ring, manchete de variação, frase do backend, mini-grid das 6
   dimensões (Credit/Debt "Em breve"), "Entender meu resultado", base e atualização, "Como calculamos?".
   - Sempre visível: `NO_DATA` explica que o score aparece depois do primeiro mês fechado; `INSUFFICIENT_DATA` mostra as
     dimensões disponíveis sem ring; estimado com "≈" e traço tracejado (Trust UX); `RECALCULATING` mostra
     "Atualizando…" sobre o último snapshot; erro sem jargão com nova tentativa.
   - Sempre o **mês corrente**: o seletor de período da Home só afeta a Visão geral.
   - Grid: mobile e tablet em largura total, primeiro bloco; a partir de 1024, 5/7 com o Health à esquerda e saldo +
     fluxo empilhados à direita (06-responsive).
3. **`/home/health`:** resumo (ring 180 px no desktop, variação neutra, frase, confiança, cobertura) com a linha de
   evolução de 6 meses; fontes (período, OFX/CSV/manual, atualização, mês em andamento, estimado, recalculando);
   dimensões 3×2 (2 no tablet, 1 no mobile) com barra, explicação, "Ponto de atenção" e **"Detalhes" que expandem as
   evidências no próprio card** (botão com `aria-expanded`); fatores com ícones neutros + / −; próximos passos sem ✦.
4. **Próximos passos → telas existentes:** `BUILD_RESERVE` "Ver contas" (`/accounts`) · `ADD_RESERVE_ACCOUNT`
   "Adicionar conta" (`/accounts/new`) · `REVIEW_CATEGORY` "Ver categoria" (`/analysis/categories?month&category`) ·
   `CATEGORIZE_TRANSACTIONS` "Categorizar transações" (`/transactions?category=none`) · `REVIEW_MONTH` "Ver análise do
   mês" (`/analysis?month`).
   - **Adendo (2026-10-19, ADR-0024 §5):** `REVIEW_CATEGORY` leva a "Ver insight" (`/insights/:id`) quando existe a alta
     daquela categoria no mesmo mês (`CATEGORY_SPIKE` ativo); senão, continua "Ver categoria".
   - **Adendo (2026-10-26, ADR-0027 §4):** `PAY_OVERDUE_INVOICE` "Ver fatura" e `REVIEW_CARD_UTILIZATION` "Ver
     cartão" aparecem desabilitados com "Em breve" até as telas de cartões (Parte F). Credit e Debt deixam de mostrar
     "Em breve" no mini-grid quando estão `OK`; sem cartão, continuam com o motivo do backend.
   - **Adendo (2026-10-27, ADR-0029 §9):** com as telas de cartões, `PAY_OVERDUE_INVOICE` leva a
     `/cards/{cardId}/invoices/{month}` (o mês de vencimento do passo) e `REVIEW_CARD_UTILIZATION` a `/cards/{cardId}`.
     Sem `cardId` (ou sem mês, na fatura), o passo continua sem link.
5. **"Como calculamos?":** `<dialog>` modal nativo, 520 px à direita (bottom sheet no mobile), montado na primeira
   abertura e mantido; abrir e fechar só chamam `showModal()`/`close()`. Foco no botão Fechar ao abrir, preso no
   diálogo, Esc e clique no fundo fecham e o foco volta ao gatilho. O conteúdo vem de `/health/method` e só carrega ao
   abrir.
6. **Análise › Evolução:** coluna "Score" na tabela mês a mês e card "Saúde financeira no período" com a linha do score
   e "Ver saúde", pela chamada a `/health/evolution` com o mesmo `month` e número de meses. Falha do Health não esconde
   os números da Análise.
7. **Gráficos no DS (ADR-0016):** `ScoreRing` (arco em atributos SVG, Teal; estimado em Soft Blue tracejado) e
   `ScoreLine` (meses estimados/em andamento tracejados com ponto vazado), ambos com alternativa textual (nome
   acessível / tabela "Ver dados"); sem estilo inline (CSP).
8. **Tokens `on-dark`** em `design/tokens/tokens.css` e `tokens.json` (fonte de verdade, sincronizados por
   `scripts/sync-design.mjs`) e documentados em `design/specs/01-foundations.md`: `text` `#FFFFFF`,
   `text-secondary` `#CBD5E1`, `muted` `#94A3B8`, `link` `#93C5FD`, `surface` branco 6%, `track` branco 12% — os
   valores do layout D-Home. O `Skeleton` ganhou o tom `dark` (05-states: skeleton translúcido no card Navy).

## Alternatives

- Ocultar o card sem score; card seguindo o seletor de período; "Detalhes" em drawer; cores só com tokens existentes
  (contraste baixo do muted sobre Navy e desvio do layout).
- Desmontar o `<dialog>` ao fechar: no Chrome o foco ia para o `body` (o close watcher fecha o modal antes do
  efeito); manter montado deixa o navegador devolver o foco.

## Consequences

- A Home faz duas chamadas (Visão geral e Health), independentes; o card mostra seus próprios estados.
- A aba Evolução faz duas chamadas.
- O DS ganha uma família de cores para superfícies escuras, reutilizável (toasts, tooltips).

## Security / Privacy

- O BFF mantém o Workspace da sessão e só repassa parâmetros validados; nenhum dado financeiro em logs do browser.

## Financial Integrity

- O frontend só exibe: score, variação, textos e números vêm do snapshot do backend; nenhuma regra de cálculo no
  browser. Valores monetários exibidos a partir das strings decimais do backend.

## Operational Impact

- E2E `frontend/e2e/health.spec.ts` (valores exatos do `health-v1`, drawer, Evolução e 5 larguras); rotas novas no
  aquecimento do `globalSetup`.

## Related Specs

- `design/specs/03-screens.md`, `05-states.md`, `06-responsive.md`, `07-design-decisions.md`, `01-foundations.md`
- `docs/adr/ADR-0016`, `ADR-0017`, `ADR-0018`, `ADR-0019`

## Date

2026-10-16
