# ADR-0027 — Financial Health `health-v2`: Credit e Debt com cartões (M8, Sessão 6.2 parte E)

## Status

Accepted

> Decisões aprovadas em 2026-10-26:
> - **Credit** = utilização **agregada** dos cartões ativos (com parcelas futuras) + pontualidade das faturas;
> - **Debt** = peso mensal das parcelas na renda + horizonte do compromisso;
> - utilização histórica **recalculada pelos fatos** (sem snapshot novo);
> - o Health passa a ouvir `card.*` e `invoice.closed` na fila que já existe.
>
> Pesos, curvas das outras dimensões, renormalização, confiança e templates do ADR-0017 não mudam.

## Context

O ADR-0017 deixou Credit e Debt `NOT_APPLICABLE` até o M8, com o score renormalizado sobre no máximo 70% do peso.
O M8 entregou cartões, faturas, parcelas, estornos, pagamentos e utilização (ADR-0025/0026), além do início do
acompanhamento (ADR-0025 §15). O `D-HealthMethod` descreve Credit como "utilização do limite e pagamento das faturas"
e Debt como "parcelamentos e compromissos futuros". O `09-dados-de-exemplo` traz Credit 71 ("utilização de 56% do
Aurora Platinum, incluindo R$ 3.360 de parcelas futuras") e Debt 88 ("único compromisso: notebook, 7 parcelas
restantes").

Faltava:
- a fórmula das duas dimensões;
- como ler os cartões numa data-base passada (os 12 meses fechados do snapshot, ADR-0018 §4);
- o que dispara o recálculo quando só o cartão muda (limite, arquivamento, início do acompanhamento).

## Decision

### 1. Posição dos cartões numa data (porta de Cards)

- **Nova porta só de leitura `cards.application.port.CardPortfolio`**, implementada no próprio módulo `cards` pelo
  `CardLedgerLoader`. Ela devolve, para cada data pedida, a posição dos **cartões ativos hoje** cujo acompanhamento já
  começou nessa data (início do período da primeira fatura acompanhada ≤ data).
- **Posição numa data d** = o razão do ADR-0025 calculado com "hoje" = d e só com os fatos até d: parcelas de compras
  com data ≤ d, estornos com data ≤ d e pagamentos com data ≤ d. A regra é a mesma da fatura e da utilização; nada é
  recalculado com regra própria.
- **Pontualidade de uma fatura:** o razão calculado na data do **vencimento efetivo** dela. A fatura foi paga em dia
  se o saldo em aberto ali é zero.
- **Limite, apelido e conjunto de cartões são os atuais**, como as contas de reserva na evolução (ADR-0018 §4). O
  sistema não guarda o histórico do limite. Documentado no método.
- Uma leitura das faturas e uma dos fatos por Workspace; as posições de todas as datas saem da memória (sem N+1).
- **ArchUnit:** Overview, Health e Insights podem ler `cards.application.port` e `cards.domain` (ADR-0025 §1). Cards
  continua sem depender de nenhum deles.

### 2. Credit

- **Aplicável** com ao menos um cartão ativo acompanhado na data-base. Sem isso, `NOT_APPLICABLE` com o motivo
  "Disponível quando houver cartões".
- **U** (utilização agregada) = Σ utilizado ÷ Σ limite dos cartões considerados, em percentual com 2 casas
  (HALF_EVEN). O utilizado é o do ADR-0025 §9: faturas em aberto, fatura atual e parcelas futuras. Parcelas
  `BEFORE_TRACKING` ficam fora, porque o razão já as exclui.
- **p** (pontualidade) = faturas acompanhadas com valor > 0 e **vencimento efetivo nos 6 meses até a data-base**
  (`(d − 6 meses, d]`) pagas integralmente até o vencimento efetivo ÷ total dessas faturas.
- **Nota** = 0,7 · S(U) + 0,3 · 100 · p. Sem nenhuma fatura vencida na janela, **Nota = S(U)**.
- **S(U):** 0% → 100 · 30% → 80 · 60% → 55 · 90% → 20 · ≥ 100% → 0.
- **Ponto de atenção:** U ≥ 50%, algum cartão com utilização ≥ 80%, p < 1 ou alguma fatura vencida na data-base.
- **Explicação:**
  - um cartão: "Utilização de {U}% do limite do {apelido}, incluindo {R$} de parcelas futuras."
  - vários: "Utilização de {U}% do limite dos seus {n} cartões ({R$ utilizado} de {R$ limite}), incluindo {R$} de
    parcelas futuras."
  - sem parcelas futuras, a parte "incluindo…" sai;
  - com faturas vencidas na janela: "{k} de {n} faturas pagas integralmente até o vencimento."
- **Evidências:** utilizado, limite, utilização, parcelas futuras, faturas pagas em dia e faturas vencidas na janela
  (fonte `cards.utilization` e `cards.invoices`).
- **Aurora** (só ele): U = 56,44% → S = 57,97 → 0,7 · 57,97 + 0,3 · 100 = **71**. Com o Horizonte (fixture completo),
  U = 43,30% → **78**: o design cita só o Aurora; a decisão aprovada é a utilização agregada.

### 3. Debt

- **Aplicável** nas mesmas condições do Credit.
- **Renda média** = receitas (`INCOME`) da janela W do ADR-0017 §1 ÷ n (zeros contam), 2 casas.
- **b** = parcelas já comprometidas na fatura **seguinte à atual** de cada cartão ÷ renda média, em percentual com 2
  casas.
- **h** = parcelas futuras (faturas que ainda não começaram) ÷ renda média, em meses de renda com 2 casas.
- **Nota** = 0,7 · S(b) + 0,3 · S(h).
  - S(b): 0% → 100 · 10% → 84 · 20% → 60 · 35% → 25 · ≥ 50% → 0.
  - S(h): 0 → 100 · 1 → 78 · 3 → 40 · ≥ 6 → 0.
- **Sem parcelas futuras nem comprometidas:** nota 100, "Nenhum parcelamento em andamento nos seus cartões." Vale
  mesmo sem janela.
- **Com parcelas e renda média zero:** S(b) = S(h) = 0 (mesmo precedente do Cash Flow, ADR-0017). Com parcelas e
  janela vazia: `INSUFFICIENT_DATA` ("Precisa de pelo menos 1 mês fechado").
- **Ponto de atenção:** b ≥ 15% ou h ≥ 3.
- **Explicação:**
  - um parcelamento: "Único compromisso: {rótulo}, {k} parcelas restantes ({R$} até {mês}). Próxima fatura: {R$}
    ({b}% da renda média)."
  - vários: "{n} parcelamentos em andamento: {R$} na próxima fatura ({b}% da renda média) e {R$} em parcelas futuras."
  - **rótulo** = comerciante; sem comerciante, a categoria; sem as duas, "compra parcelada". Nunca a descrição da
    transação (regra do ADR-0021 §2).
  - "Parcelas restantes" são as das faturas que ainda não começaram, como no `09-dados-de-exemplo`.
- **Aurora:** renda média abr–ago R$ 6.560,00; b = 480 ÷ 6.560 = 7,32% → 88,29; h = 3.360 ÷ 6.560 = 0,51 → 88,78;
  nota = **88**.

### 4. Score, cobertura, fatores e próximos passos

- Pesos, renormalização, mínimo (Cash Flow + mais uma), variação, estimado e confiança: os do ADR-0017.
- Com cartões, a cobertura pode chegar a 6 de 6 dimensões (100% do peso).
- Fatores e frase do resumo usam as novas dimensões como as demais (nota ≥ 80 sustenta; < 70 ou ponto de atenção
  pesa).
- **Próximos passos novos** (até 3 no total, sem ✦):
  1. `PAY_OVERDUE_INVOICE`, **primeira prioridade**: "Fatura de {mês} do {apelido} vencida" com "{R$} em aberto desde
     {dd mmm}." → **Ver fatura** (`cardId`, mês de vencimento). Só a mais antiga.
  2. depois do `REVIEW_CATEGORY`: `REVIEW_CARD_UTILIZATION` quando o ponto de atenção do Credit vem da utilização:
     "Acompanhar a utilização do {apelido}" com "Sem as parcelas futuras, a utilização seria de {x}%." (ou "{U}% do
     limite em uso.") → **Ver cartão**. O cartão de maior utilização.
  - `NextStep` ganha `cardId` (aditivo na API).
- **Contexto:** `activeCards` (aditivo), para "3 contas e 2 cartões".

### 5. Versão, rebuild e histórico

- `algorithmVersion = "health-v2"`.
- **Linhas `health-v1` não são apagadas nem reescritas** (ADR-0018 §4). O runner `missing` grava a v2 de todos os
  Workspaces na primeira inicialização (13 meses, calculados com a regra nova). A API lê a versão atual, então a
  evolução passa a mostrar a v2.
- O histórico "era 49% em agosto" sai dos fatos (§1): o snapshot de cada mês fechado guarda a utilização daquele mês
  na evidência do Credit, e os Insights calculam a do mês anterior pela mesma porta.

### 6. Gatilhos

- A fila `shf.health.snapshot` ganha os bindings **`card.created`, `card.updated`, `card.archived`,
  `card.reactivated` e `invoice.closed`** (adendo ao ADR-0018 §2). O consumidor só marca o Workspace, com o mesmo
  debounce, inbox, retry e DLQ.
- Compras, estornos, pagamentos e conciliações já chegam por `transaction.*` → `analytics.projection-updated`.
- O job diário (00:05) continua cobrindo a mudança de estado das faturas com o calendário.
- **Sem fila nova.** Os Insights continuam encadeados no Health (ADR-0022) e recebem as mudanças de cartão pelo
  `health.snapshot-updated`.

### 7. Método ("Como calculamos?")

- Credit e Debt passam a "aplicar": fórmula, curvas, mínimo e fontes vêm das constantes do cálculo.
- A exclusão "cartões e parcelamentos por enquanto" sai.
- Nota nova: "A utilização dos meses anteriores usa o limite atual dos cartões."

## Alternatives

- **Utilização do cartão mais usado:** reproduz o 71 do design, mas penaliza quem concentra gastos num cartão com
  limite total folgado. Rejeitada.
- **Só utilização no Credit:** ignora o pagamento das faturas que o método cita. Rejeitada.
- **Só o peso mensal no Debt:** trata igual 480 por 7 meses e 480 por 40 meses. Rejeitada.
- **Snapshot mensal da utilização:** guardaria o limite da época, mas só teria dados a partir de agora e exige
  outro job. Rejeitada.
- **Consumidor próprio de `invoice.closed`:** fila e DLQ novas para um efeito que é só marcar o Workspace.
  Rejeitada.
- **Debt aplicável a todos (sem cartão = 100):** subiria o score de quem não usa cartão sem nenhum dado novo.
  Rejeitada.

## Consequences

- Quem tem cartão ganha duas dimensões. O score muda de base, por isso a versão nova e a evolução recalculada em v2.
- O Health passa a ler Cards. Cards continua sem depender do Health.
- A evolução usa o limite atual. Mudar o limite muda a utilização dos meses passados (documentado).
- Um pagamento não registrado (débito "PAG FATURA" ainda como despesa) aparece como fatura não paga. O Insight D14
  (ADR-0028) orienta a conciliar.

## Security / Privacy

- A porta só lê o Workspace pedido. Payloads dos eventos continuam só com ids.
- Textos usam apelido e comerciante. Nunca descrição, `last4` ou valores em logs, métricas ou traces.

## Financial Integrity

- Uma única regra de fatura, alocação e utilização (ADR-0025): o Health reusa o razão de Cards numa data.
- `BigDecimal` em tudo; razões com 2 casas em percentual (4 em fração), notas inteiras HALF_EVEN.
- Nenhum fato futuro, nenhuma estimativa. Parcelas antes do acompanhamento não são dívida aberta.
- Teste de paridade: o snapshot gravado é igual ao cálculo direto sobre as portas, com cartões.

## Operational Impact

- Sem migration. Bindings novos na fila existente.
- Primeira inicialização: rebuild `missing` do Health em todos os Workspaces com transações, seguido da geração dos
  Insights (`health-rebuild.md`, `insights-rebuild.md`).

## Related Specs

- `docs/adr/ADR-0017`, `ADR-0018`, `ADR-0019`, `ADR-0020`, `ADR-0025` (§3, §9, §15), `ADR-0026`
- `design/specs/03-screens.md` (D-Health, D-HealthMethod), `09-dados-de-exemplo.md`
- `specs/01-product/mvp.md` (Financial Health), `specs/05-system-design/05.7`, `05.9`, `05.13`

## Date

2026-10-26
