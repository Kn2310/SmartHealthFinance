# ADR-0024 — UI de Insights (M7, parte D)

## Status

Accepted

> Decisões aprovadas em 2026-10-19: dispensar com **confirmação inline** ("não volta a aparecer"); "Ver insight
> relacionado" no detalhe da transação **só quando existe**; o próximo passo `REVIEW_CATEGORY` do Health leva ao
> **insight quando houver** (adendo ao ADR-0020); contagem de Importantes no **M-More e na sidebar/trilho** (escolha do
> produto, além do layout, que só mostra no M-More).

## Context

A API de Insights (ADR-0023) entrega lista, detalhe, destaques, relacionados e ações. Faltam as telas `D-Insights`,
`D-InsightDetail`, `M-Insights`, `M-InsightDetail`, os blocos "O que mudou?" e "Merece atenção" da Home
(`D-Home`/`M-Home`), "Ver insight relacionado" no `D-TxnDetail`, o link dos próximos passos do Health e o item Insights
do `M-More`, até aqui "Em breve".

## Decision

1. **BFF:** `/api/bff/insights` (lista), `/highlights`, `/related`, `/status`, `/{id}` e as ações `POST /{id}/seen`,
   `POST /{id}/dismiss`, `PUT /{id}/feedback`. O Workspace vem sempre da sessão; só parâmetros bem formados passam;
   ações só da mesma origem; falhas viram códigos estáveis (`INSIGHT_NOT_FOUND`, `INSIGHT_NOT_ACTIVE`,
   `TRANSACTION_NOT_FOUND`, `VALIDATION_FAILED` com os campos conhecidos, `UNAVAILABLE`).
2. **`/insights`:** chips como grupo de botões com `aria-pressed` e contagem (Todos, Importantes, Gastos, Saúde;
   **Cartões desabilitado "Em breve"** até o M8; Receitas só em Todos/Importantes, ADR-0021 §9), filtro na URL
   (`?filter=`), seções "Importantes" e "Outros insights". Cards = links com nome acessível (tipo, importância, frase,
   "novo"), "✦ Insight · {tipo}" como único ✦, badge Importante/Informativo com texto (nunca só cor), "Novo" para
   ativo não visto, meta "Hoje · {assunto}".
3. **`/insights/:id`:** título = a frase; tags; Resumo (números-chave); Evidências com o gráfico do DS (`MonthBars` para
   dinheiro, `ScoreLine` para score, tabela para cobertura) e a tabela "Ver dados" (ADR-0016); maiores contribuintes;
   "Como comparamos", limitações (mês em andamento etc.) e fontes com as origens; coluna lateral com Ação recomendada
   (ação primária, secundárias e os placeholders Objetivo/Copilot desabilitados "Em breve"), Confiança (3 segmentos),
   "Este insight foi útil?" (Útil/Não útil com `aria-pressed`; clicar no pressionado remove), Dispensar com
   confirmação inline e Relacionados (dimensão do Health e insights). Abrir o detalhe chama `POST /seen` (ADR-0023 §3).
4. **Home:** uma chamada a `/highlights` alimenta "O que mudou" (até 3 linhas com rótulo, valor e comparação, cada uma
   um link para o detalhe) e "Merece atenção" (até 2 cards; 1 no mobile, como o `M-Home`), com "Ver todos". Grid: depois
   da Visão geral; mobile/tablet com "Merece atenção" antes de "O que mudou"; desktop "O que mudou | Merece atenção" lado
   a lado (`D-Home`). Estados próprios: carregando, erro com nova tentativa, sem dados, histórico curto, nada relevante
   e "Atualizando…".
5. **Integrações:**
   - `D-TxnDetail`: transação `POSTED` chama `/related?transactionId`; com resultado, painel "✦ Insight relacionado"
     com a frase e o botão primário "Ver insight relacionado" para o mais prioritário; sem resultado, nada.
   - `/home/health`: com um passo `REVIEW_CATEGORY`, uma chamada a `/related?dimension=SPENDING`; se houver
     `CATEGORY_SPIKE` da mesma categoria e mês, o link vira "Ver insight" (`/insights/:id`); senão, "Ver categoria".
   - Navegação: contagem de Importantes ao lado de "Insights" na sidebar/trilho e "N importantes" no `M-More` (Insights
     deixa de ser "Em breve"); some sem Importantes ou se a chamada falhar.
   - **Adendo (2026-10-26, ADR-0028 §7):** o chip **Cartões** é habilitado, com contagem. "Registrar pagamento" leva a
     `/transactions/new?type=CARD_PAYMENT&cardId=`; "Ver cartão" e "Ver fatura" ficam desabilitados com "Em breve" até
     as rotas `/cards/...` da Parte F.
6. **Estilo:** só tokens do DS (teste de conformidade), sem estilo inline (CSP); larguras 375/768/1024/1280/1440 sem
   rolagem horizontal (E2E).

## Alternatives

- Dispensar sem confirmação (não há "desfazer" na API); "Ver insight relacionado" sempre visível e desabilitado;
  próximos passos inalterados ou com dois links; contagem só no M-More (recomendação, preterida pelo produto).
- Detalhe em drawer como o da transação: o layout é uma página (`/insights/:id`) com breadcrumb.

## Consequences

- A Home faz três chamadas independentes (Visão geral, Health, destaques); a sidebar faz uma chamada a `/highlights`
  por carga do app. O "Home API composta" do M3 continua aberto.
- O detalhe da transação e o `/home/health` fazem uma chamada a mais, só quando podem usar o resultado.

## Security / Privacy

- O BFF nunca aceita o Workspace do browser e só repassa parâmetros validados; ações exigem a mesma origem.
- Nenhum dado financeiro em logs do browser ou do BFF (só o rótulo da chamada e o status em falhas).

## Financial Integrity

- O frontend só exibe: frases, números, linhas do "O que mudou?" e ações vêm prontos do backend (ADR-0021/0023); a UI
  monta apenas as rotas das ações e formata valores.

## Operational Impact

- E2E `frontend/e2e/insights.spec.ts`: extrato CSV relativo a hoje que dispara D1, D2, D4 e D10 em qualquer dia do mês;
  verifica textos e valores exatos, detalhe, dispensa (e ausência depois), Home, transação, M-More e as 5 larguras.
  Rotas novas no aquecimento do `globalSetup`.

## Related Specs

- `design/specs/02-components.md`, `03-screens.md`, `04-navigation-flows.md`, `05-states.md`, `06-responsive.md`,
  `07-design-decisions.md`; layouts `D-Insights`, `D-InsightDetail`, `M-Insights`, `M-InsightDetail`, `D-Home`,
  `M-Home`, `D-TxnDetail`, `M-More`, `N-Map`
- `docs/adr/ADR-0016`, `ADR-0020` (adendo), `ADR-0021`, `ADR-0023`

## Date

2026-10-19
