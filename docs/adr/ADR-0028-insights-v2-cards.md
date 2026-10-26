# ADR-0028 — Insights `insights-v2`: detectores de Cartões (M8, Sessão 6.2 parte E)

## Status

Accepted

> Decisões aprovadas em 2026-10-26:
> - três detectores de Cartões: **D11 fatura acima do padrão**, **D12 utilização alta** e **D14 fatura vencendo ou
>   vencida**. O novo parcelamento (D13) foi **rejeitado** e o código fica reservado;
> - a troca de versão **herda visto e feedback** pelo fingerprint, além da dispensa;
> - chip "Cartões" habilitado;
> - "Ver cartão" e "Ver fatura" saem prontos do backend e aparecem como "Em breve" no frontend até as telas de
>   cartões (Parte F).
>
> Os detectores D1–D10, limiares, pontuação, textos e ciclo de vida do ADR-0021 não mudam.

## Context

O ADR-0021 deixou os detectores de Cartões para o M8 e o chip "Cartões" desabilitado. O design mostra dois insights de
cartão: "Sua fatura Aurora está 18% acima do seu padrão" (Importante, com a explicação dos R$ 520,60 em três fatores)
e "A utilização do Aurora passou de 49% para 56%" (Informativo), além da linha de utilização no "O que mudou?" da
Home. O ADR-0027 criou a porta `CardPortfolio`, que dá a posição dos cartões em qualquer data.

## Decision

### 1. Entrada

- `InsightInputLoader` passa a ler a porta `CardPortfolio` (ADR-0027 §1): a posição de hoje dos cartões ativos e a do
  último dia de cada um dos 6 meses anteriores (série da utilização).
- ArchUnit: `insights` pode ler `cards.application.port` e `cards.domain` (adendo ao ADR-0022 §1).
- Novo tipo **`CARDS`** ("Cartões"). Ações novas: `VIEW_INVOICE` ("Ver fatura", `cardId` + mês de vencimento),
  `VIEW_CARD` ("Ver cartão", `cardId`) e `RECORD_CARD_PAYMENT` ("Registrar pagamento", `cardId`).
- Os textos usam o **apelido do cartão** (escolhido pelo usuário) e comerciante/categoria. Nunca `last4`, descrição da
  transação ou valores em logs.

### 2. D11 `INVOICE_ABOVE_PATTERN` (Cartões, pode ser Importante)

- **Fatura em foco**, por cartão ativo acompanhado:
  - a última fatura fechada, até o vencimento efetivo dela (o momento de decidir o pagamento);
  - depois disso, a fatura aberta, a partir do **7º dia do ciclo** (como a regra dos 7 dias do ADR-0021 §1), com o
    valor "até agora".
- **Base:** média das até **3 faturas acompanhadas anteriores** à fatura em foco, com lançamentos, 2 casas
  HALF_EVEN. Exige **m ≥ 2**.
- **Dispara:** Δ = valor − média ≥ **R$ 100** e Δ ÷ média ≥ **15%**. Impacto A = Δ (piso global do ADR-0021 §3).
- **Explicação determinística por fatores** (07-design-decisions §13: os fatores somam o total):
  - grupos: compras à vista **por categoria**; **cada parcelamento** como grupo próprio; **estornos** como um grupo;
  - valor do grupo em cada fatura = parcelas (já descontado o estorno) − créditos aplicados;
  - Δ do grupo = valor na fatura em foco − média do grupo nas faturas da base (zeros contam, 2 casas);
  - os 3 maiores Δ positivos são os fatores; **"Outros"** fecha a conta, inclusive os centavos de arredondamento, para
    que Σ fatores = Δ exatamente;
  - rótulo do parcelamento: "Parcela de {comerciante}" (sem comerciante, "Parcela em {categoria}"), com "{k}/{n}".
  - Aurora: +R$ 520,60 = Alimentação fora +R$ 311,00 + Parcela de Loja Tech (3/10) +R$ 160,00 (R$ 480,00 vs média
    R$ 320,00) + Transporte +R$ 49,60.
- **Textos:**
  - aberta: "Sua fatura do {apelido} está {p}% acima do seu padrão até agora, cerca de R$ {Δ≈} a mais." / "R$ {valor}
    até {dd mmm}, contra média de R$ {média} nas últimas {m} faturas."
  - fechada: "A fatura de {mês} do {apelido} fechou {p}% acima do seu padrão, cerca de R$ {Δ≈} a mais." / "R$ {valor},
    contra média de R$ {média} nas {m} faturas anteriores ({mês inicial}–{mês final})."
  - Ação recomendada: "Revisar {fator 1}: +R$ {x} vs a média."
- **Confiança:** alta com m = 3 e fatura fechada; média nos demais casos com fatura fechada ou aberta há ≥ 15 dias;
  baixa na aberta com menos de 15 dias.
- **Evidências:** valor, média e diferença; série das até 6 últimas faturas (a aberta marcada como parcial); fatores
  (contribuintes do tipo `FACTOR`); "Como comparamos"; limitações `PARTIAL_INVOICE` e `SHORT_HISTORY`; fonte
  `cards.invoices`.
- **Ações:** `VIEW_INVOICE` (primária) e `VIEW_CARD`.
- **"O que mudou?":** "Fatura do {apelido}" · "R$ {valor} até {dd mmm}" (ou "R$ {valor} em {mês}") · "+{p}% vs média
  das últimas {m}".

### 3. D12 `CARD_UTILIZATION_HIGH` (Cartões, Importante só com U ≥ 80%)

- Por cartão ativo acompanhado, hoje: **U ≥ 50%** (utilização do ADR-0025 §9, com parcelas futuras).
- Impacto A = utilizado − 50% do limite (piso global).
- **Fingerprint sem período**, com a faixa no sujeito (`50-79`, `80-99`, `100+`), como o D9: dispensar esconde até
  a faixa mudar; deixa de valer → `RESOLVED`.
- **Textos:** "A utilização do {apelido} está em {U}% do limite, incluindo parcelas futuras." (sem parcelas futuras,
  "…em {U}% do limite.") / "Era {x}% no fim de {mês}. Sem as parcelas futuras, seria {y}%." (cada frase só quando se
  aplica).
- **Confiança** alta (fatos registrados) com a limitação `CARD_PAYMENTS` ("Considera os pagamentos registrados ou
  importados até hoje.").
- **Evidências:** utilizado, limite, utilização, fatura atual, faturas anteriores em aberto e parcelas futuras; série
  da utilização no fim dos 6 meses anteriores e hoje (`PERCENT`); fonte `cards.utilization`.
- **Ações:** `VIEW_CARD` (primária) e `VIEW_HEALTH`.
- **"O que mudou?":** "Utilização do {apelido}" · "{U}% do limite" · "era {x}% em {mês}" (sem histórico, "incluindo
  parcelas futuras").

### 4. D14 `INVOICE_DUE` (Cartões, Importante só vencida)

- Fatura fechada com saldo em aberto:
  - **vence em até 5 dias** (hoje ≤ vencimento efetivo e vencimento − hoje ≤ 5): Informativo;
  - **vencida** (hoje > vencimento efetivo): elegível a Importante.
- Sujeito = cartão + situação (`DUE_SOON` ou `OVERDUE`), período = mês de vencimento: dispensar o aviso de
  vencimento não esconde o de atraso.
- Impacto A = saldo em aberto (piso global).
- **Textos:** "A fatura de {mês} do {apelido} vence em {dd mmm}: R$ {x} em aberto." / "A fatura de {mês} do {apelido}
  venceu em {dd mmm} com R$ {x} em aberto." Apoio: "Se você já pagou, registre o pagamento ou marque o débito do
  extrato como pagamento de fatura."
- **Confiança** alta, com a limitação `CARD_PAYMENTS`.
- **Ações:** `RECORD_CARD_PAYMENT` (primária, `/transactions/new` com o pagamento de fatura do cartão) e
  `VIEW_INVOICE`.
- Não entra no "O que mudou?".

### 5. Ciclo de vida dos insights de fatura

- D11 e D14 ficam presos à **fatura** (período = mês de vencimento), não ao mês avaliado P.
- Não detectado: se a fatura ainda está em foco (D11) ou fechada com saldo (D14) → `RESOLVED`; senão → `EXPIRED`
  (`PERIOD_ENDED`).
- D12 segue a regra dos detectores sem período (D3/D9): não detectado → `RESOLVED`.
- Relacionados: os três apontam para a dimensão **Credit** do Health (ADR-0027).

### 6. Versão e herança

- `algorithmVersion = "insights-v2"`. Pela regra do ADR-0021 §6, os `ACTIVE` da v1 viram `EXPIRED`
  (`VERSION_SUPERSEDED`) e a v2 gera os seus.
- **Adendo ao ADR-0021 §6 e ao ADR-0022 §4:** o insight novo herda, do mais recente de outra versão com o mesmo
  fingerprint, **`seenAt` e o feedback** (com a data), além da dispensa que já era herdada. A troca de versão não
  faz nada reaparecer como "Novo" nem perde o "útil / não útil".
- Rebuild: o runner `missing` marca todos os Workspaces sem geração na v2. O estado do usuário nunca é apagado
  (ADR-0022 §7).

### 7. API e UI

- `GET /insights?type=CARDS` passa a devolver itens; a contagem `cards` vem com `available = true`. O formato não
  muda (o campo já existia).
- `typeLabel` "Cartões".
- "O que mudou?" inclui D11 e D12 (adendo ao ADR-0023 §5).
- Frontend: chip habilitado. `RECORD_CARD_PAYMENT` leva a `/transactions/new?kind=card-payment&cardId=`.
  `VIEW_CARD` e `VIEW_INVOICE` aparecem desabilitados com "Em breve" até as rotas `/cards/...` da Parte F (adendo ao
  ADR-0024).

## Alternatives

- **D13 novo parcelamento:** proposto e rejeitado pelo produto.
- **Comparar só faturas fechadas:** mais conservador, mas o aviso chegaria depois do fechamento e o design mostra a
  fatura em andamento. A fatura aberta entra com "até agora" e confiança reduzida.
- **Duas faturas em foco ao mesmo tempo:** duplicaria o assunto no mesmo cartão.
- **Detectores de cartão versionados à parte (sem `insights-v2`):** o reconciliador trabalha com uma versão por
  geração. Rejeitada.
- **Troca de versão sem herança:** todos os insights voltariam como "Novo" e perderiam o feedback. Rejeitada.

## Consequences

- Insights de cartão aparecem em Todos, Importantes e no chip Cartões, e na Home.
- Um débito de fatura ainda não conciliado aparece como fatura em aberto: o D14 orienta a conciliar.
- Até a Parte F, as ações de cartão e fatura ficam visíveis e desabilitadas.

## Security / Privacy

- Leituras só do Workspace gerado. Logs só com ids, contagens e códigos de detector.

## Financial Integrity

- Números de fatura e utilização vêm do razão de Cards (ADR-0025), sem regra própria.
- Os fatores do D11 somam exatamente a diferença; a fatura aberta é sempre marcada como parcial.

## Operational Impact

- Migration `V19__allow_card_insights.sql`, só aditiva: o `check` de `insights.type` passa a aceitar `CARDS`. Os
  códigos novos de detector e ação cabem nas colunas de texto existentes.
- Rebuild `missing` dos Insights na primeira inicialização; runbook `insights-rebuild.md` atualizado.

## Related Specs

- `docs/adr/ADR-0021`, `ADR-0022`, `ADR-0023`, `ADR-0024`, `ADR-0025`, `ADR-0027`
- `design/specs/03-screens.md` (D-Insights, D-Cards, D-Invoice, D-Home), `07-design-decisions.md` §13,
  `09-dados-de-exemplo.md`

## Date

2026-10-26
