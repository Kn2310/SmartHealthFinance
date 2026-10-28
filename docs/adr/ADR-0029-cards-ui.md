# ADR-0029 — Telas de Cartões (M8, Sessão 6.2 parte F)

## Status

Accepted

> Decisões aprovadas em 2026-10-27:
> - evolução da utilização por **endpoint novo** sobre a `CardPortfolio`;
> - "✦ Explicação" da fatura **reaproveitando o D11**, sem contrato novo; "Perguntar ao Copilot" "Em breve";
> - estimativa da próxima fatura (M9) **oculta**;
> - "por categoria" na fatura **adiado**;
> - texto **curto** para o início do acompanhamento;
> - "% da fatura atual" do parcelamento por **campo aditivo** no backend; "% da renda" sai;
> - edição, arquivamento e estorno no cartão **entram** nesta parte;
> - "Fonte" do cartão e média das faturas no detalhe **omitidas**.
>
> Os ADRs 0025–0028 e os adendos do ADR-0009 não mudam.

## Context

O M8 entregou o modelo e a API de cartões (ADR-0025/0026), a importação de fatura (ADR-0009 §19–§30), o `health-v2`
(ADR-0027) e o `insights-v2` (ADR-0028). Faltavam as telas do design (D-Cards, D-CardDetail, D-Invoice,
D-FutureInvoices, D-Installment e as versões mobile) e as lacunas de UI da 6.1: o filtro "Conta ou cartão", "Ver
fatura" e "Ver cartão" no detalhe da transação, edição e arquivamento do cartão e o estorno no cartão.

Os layouts mostram números que a API não tinha ("era 49%" e a evolução abr–set; "14% da fatura atual"), números que
seriam regra financeira no cliente (a média das faturas, a fatura "por categoria") e números que são previsão (a
"estimativa pelo seu padrão", M9). A regra inviolável é que o frontend só exibe.

## Decision

### 1. Rotas e telas

| Rota | Tela |
|---|---|
| `/cards` | D-Cards / M-Cards |
| `/cards/new` (`?from=cards` volta ao cartão criado) | cadastro (ADR-0026 §10) |
| `/cards/:id` (`?tab=invoices\|installments`) | D-CardDetail: Visão geral, Faturas e Parcelamentos |
| `/cards/:id/edit` | edição, arquivamento e reativação |
| `/cards/:id/invoice` e `/cards/:id/invoices/:dueMonth` | D-Invoice / M-Invoice (atual ou de um mês de vencimento) |
| `/cards/:id/invoices` | D-FutureInvoices |
| `/cards/:id/installments/:transactionId` | D-Installment / M-Installment |

- Id que não é UUID nem chega ao BFF: é o mesmo "não encontrado" do cartão de outro Workspace.
- Todos os estados do `05-states`: carregando (esqueleto estático), primeira vez, erro sem jargão com "Tentar
  novamente" e parcial (um bloco falha e o resto continua).
- O BFF ganha os `GET` de resumo, cartão, faturas (lista, atual, futuras, do mês e itens paginados), parcelamentos e
  utilização (com o histórico), e o `PUT`, `archive` e `reactivate` com `rejectCrossOrigin`. Workspace da sessão,
  `no-store`, só códigos estáveis (`CARD_NOT_FOUND`, `INVOICE_NOT_FOUND`, `TRANSACTION_NOT_FOUND`, `VALIDATION_FAILED`,
  `CONFLICT`, `CARD_ARCHIVED`). Mês de vencimento malformado → `VALIDATION_FAILED` `dueMonth` `INVALID`, depois da sessão.

### 2. Evolução da utilização

- Endpoint aditivo `GET /cards/{id}/utilization/history` (adendo ao ADR-0026 §11): o último dia de cada mês anterior e
  hoje, pelo razão do ADR-0025 na data, com o limite atual.
- O D-CardDetail mostra "era {x}% no fim de {mês}" com o ponto do fim do mês anterior (o mesmo texto do D12) e a linha
  da evolução com a tabela "Ver dados". Sem histórico (primeiro mês acompanhado), só a utilização de hoje.
- Alternativas rejeitadas: a série do D12 (só existe com utilização ≥ 50% e some quando o insight é dispensado) e
  ocultar a evolução.

### 3. "✦ Explicação" da fatura

- A tela da fatura procura, em `GET /insights?type=CARDS`, o D11 (`INVOICE_ABOVE_PATTERN`) cuja ação primária
  `VIEW_INVOICE` tem o `cardId` e o `dueMonth` da fatura e lê o detalhe dele: números-chave (fatura, média,
  diferença), fatores (que já somam a diferença, ADR-0028 §2) e confiança. Texto fixo: "Comparação com as suas faturas
  anteriores, sem IA."
- Sem D11 para a fatura, o bloco e a comparação não aparecem. Nada de média calculada no cliente.
- "Perguntar ao Copilot" aparece como "Em breve" (M11).
- Alternativa rejeitada nesta parte: filtro `cardId`/`dueMonth` em `/insights/related` (contrato novo).

### 4. Estimativa e início do acompanhamento

- **Estimativa (M9) oculta:** a próxima fatura mostra só "Já comprometido (parcelas)" e "Novas compras ainda não
  aparecem aqui." As faturas futuras mostram só o comprometido, com o mesmo aviso (ADR-0025 §10).
  **Adendo (2026-10-28, ADR-0030 §3.5):** a estimativa deixa de ser oculta na parte D da Sessão 7.1. Ela aparece
  visualmente separada do comprometido ("Novas compras pelo seu padrão ≈", Estimado, com faixa). O aviso "Novas
  compras ainda não aparecem aqui" só continua quando não há estimativa (`PARTIAL_CARD_HISTORY`).
- **Início do acompanhamento (ADR-0025 §15), texto curto:**
  - na fatura cujo mês de vencimento é o `trackingStartDueMonth` do cartão: "Primeira fatura acompanhada pelo app.
    Faturas e pagamentos anteriores não entram aqui.";
  - parcela `BEFORE_TRACKING`: rótulo "Antes do acompanhamento", sem nota.
  - A data exata do corte de pagamentos não é exposta pela API; o texto não cita data.

### 5. Parcelamento

- Andamento em blocos: paga (Navy), nesta fatura (Teal), futura (tracejada), cancelada e antes do acompanhamento.
- **Impacto:** "{percent}% da fatura atual" vem do campo aditivo `currentInvoiceShare` (adendo ao ADR-0026 §11); a
  "utilização sem parcelas futuras" vem de `/utilization`. O "≈ % da renda" do layout sai: a renda média é do Health, e
  o compromisso na renda já é o Debt.
- "✦ Na sua saúde financeira": o parcelamento é considerado em Credit e Debt, com link para `/home/health`. Sem notas.
- "Limite reservado" com `reservedLimit` do backend.
- "Juros" do layout não aparece: não há campo (ADR-0025 §6, só "Informado no arquivo" no futuro).

### 6. Fatura "por categoria"

- **Adiada.** Agregar os itens paginados no cliente seria regra financeira no frontend. Quando entrar, será um
  endpoint próprio com a regra de agrupamento do D11 (parcelamentos e estornos como grupos). Registrado no backlog.

### 7. Edição, arquivamento e estorno

- `/cards/:id/edit` reaproveita o formulário do cadastro (só os 4 últimos dígitos); o limite volta como texto pt-BR.
  Conflito de versão e cartão arquivado explicam e oferecem recarregar.
- Arquivar pede confirmação na própria tela; o cartão arquivado mostra "Reativar cartão".
- **Estorno no cartão:** novo tipo "Estorno no cartão" (`CARD_CREDIT`) em `/transactions/new`, sobre o
  `POST /cards/{id}/credits` existente. O detalhe de uma compra no cartão oferece "Registrar estorno", já com o cartão e
  o vínculo (`refundOf`). O cartão do estorno é escolhido entre os ativos.

### 8. Detalhe do cartão e da transação

- O D-CardDetail **não mostra** a "Fonte" do cartão nem a média das faturas (não há dado no backend). A
  fatura mostra se as datas vieram do arquivo ou do ciclo (`datesSource`).
- O detalhe da transação de cartão ganha o bloco "Cartão e fatura": "Ver fatura" leva à fatura da parcela atual (ou da
  primeira acompanhada), lida do parcelamento no backend; "Ver parcelamento" quando há mais de uma parcela; "Ver
  cartão". "Pago com" e "Fatura do cartão" viram links para o cartão.
- `/transactions` ganha o filtro único "Conta ou cartão" (`accountId` ou `cardId`, nunca os dois).

### 9. Destinos que estavam "Em breve"

- Insights: `VIEW_CARD` → `/cards/{cardId}`; `VIEW_INVOICE` → `/cards/{cardId}/invoices/{dueMonth}` (adendo ao
  ADR-0024).
- Health: `PAY_OVERDUE_INVOICE` → `/cards/{cardId}/invoices/{month}`; `REVIEW_CARD_UTILIZATION` → `/cards/{cardId}`
  (adendo ao ADR-0020).
- O item "Cartões" da navegação passa a ter destino.

### 10. Estilo

- Visual genérico do cartão (07-design-decisions §9): só apelido, instituição e os 4 últimos dígitos.
- Financial Range: em uso sólido e parcelas futuras tracejadas; a geometria só posiciona as faixas com os valores do
  backend.
- Só tokens do DS, breakpoints 640/1024/1440, sem estilo inline (CSP). ✦ só nos rótulos que o design define
  ("Insight", "Explicação", "Na sua saúde financeira").
- `calculationVersion` aparece como "Calculado pelo app · regras {versão}" onde há valor derivado.

## Alternatives

- Ler a evolução do snapshot do Health: o snapshot guarda a utilização agregada, não a de cada cartão.
- Calcular "% da fatura atual" no cliente com os valores do backend: rejeitado (regra financeira no frontend).
- Mostrar a estimativa como "Em breve": rejeitado pelo produto (ruído e promessa do M9).
- Tela de estorno própria em `/cards`: rejeitada; o formulário de transações já tem cartão, valor, data e vínculo.

## Consequences

- O M8 fecha com todas as telas do design, menos "por categoria" e a estimativa (M9).
- Dois campos de contrato novos, ambos aditivos e calculados no domínio de Cards.
- A tela da fatura depende do `insights-v2` para a explicação: sem insight, ela continua completa com os fatos.

## Security / Privacy

- Só os 4 últimos dígitos trafegam e aparecem; nunca número completo, CVV ou validade (os formulários não têm o campo e
  o BFF remonta o corpo).
- URLs só com ids, mês de vencimento e `from=cards`/`tab`; nenhum valor ou descrição em query.
- BFF com Workspace da sessão, `rejectCrossOrigin` nas mudanças e só códigos estáveis; logs sem dados do cartão.

## Financial Integrity

- O frontend só exibe: fatura, estado, alocação, utilização, parcelas, composição, percentuais e o histórico vêm prontos
  do backend. Dinheiro como `MoneyDto` string em `<Money>`; nenhum `parseFloat` em valor.
- Sem dupla contagem: a dívida fica em "Faturas a pagar" e nas telas de cartões, nunca no saldo total.
- Estimativa nunca aparece como fato.

## Operational Impact

- E2E `frontend/e2e/cards-frontend.spec.ts` com datas relativas a hoje: cadastro → compra parcelada → `/cards` → fatura
  → parcelamento → próximas faturas → pagamento → filtro → estados; as 5 larguras sem rolagem horizontal. Rotas novas
  no aquecimento do `globalSetup`.
- Sem migration e sem dependência nova.

## Related Specs

- `design/specs/02-components.md`, `03-screens.md` (CARDS, D-Transactions, D-TxnDetail), `04-navigation-flows.md`,
  `05-states.md`, `06-responsive.md`, `07-design-decisions.md` (§4, §9, §12, §13), `09-dados-de-exemplo.md`
- `docs/adr/ADR-0020`, `ADR-0024`, `ADR-0025`, `ADR-0026`, `ADR-0027`, `ADR-0028`

## Date

2026-10-27
