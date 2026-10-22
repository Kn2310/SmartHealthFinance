# ADR-0025 — Modelo de Cartões de crédito (M8, Sessão 6.1 parte A)

## Status

Accepted

> Decisões aprovadas em 2026-10-22:
> - **compras = transações com `card_id`**, alternativo a `account_id` (opção A);
> - **compra parcelada entra pelo valor total na data da compra**;
> - **dívida do cartão fora do saldo total**, num bloco próprio "Faturas a pagar", sem saldo líquido;
> - **compra no dia do fechamento vai para a próxima fatura**.
>
> O restante (ciclo, estados, centavos, estorno, pagamento, utilização, eventos) foi aprovado como proposto.
>
> Este ADR fixa o **modelo**. O contrato da API de cartões e faturas fica no ADR-0026 (parte B), e o das compras, dos
> parcelamentos e dos pagamentos na parte C. Importação de fatura, integrações (Overview, Analytics, Health,
> Insights) e as telas de cartões são da Sessão 6.2 (§14).

## Context

O MVP inclui Cartões (`mvp.md`; `features.md`: Meus cartões, Fatura atual, Próximas faturas, Parcelamentos,
Utilização). As specs citam `CreditCard`, `Invoice` e `Installment` como conceitos do domínio (05.2), "installments ≠
recurring" (05.13 §5), os casos de uso `CreateCreditCard`/`PayInvoice` e as queries `GetCreditCard`/`GetInvoice` (05.3),
e os recursos `/cards /invoices /installments` (05.6). O design define as telas (D-Cards, D-CardDetail, D-Invoice,
D-FutureInvoices, D-Installment), o componente de saldo ("cartões não entram no saldo", `02-components`), a regra de
D-Transactions ("compras no cartão entram pela data da compra; transferências entre suas contas não contam como
gasto") e os dados do cartão Aurora (`09-dados-de-exemplo`).

Os ADRs anteriores deixaram ganchos:
- **ADR-0004 §5:** "Cartões ficam em Cards";
- **ADR-0005 §4/§6:** transferência é uma linha com origem e destino, e há o reembolso rastreável;
- **ADR-0006 §9:** transferência nunca é receita nem despesa;
- **ADR-0009 §5:** `CCSTMTRS` é rejeitado até o M8;
- **ADR-0017:** Credit e Debt estão `NOT_APPLICABLE`, à espera do `health-v2`;
- **ADR-0021/0024:** o chip "Cartões" está "Em breve".

Nenhum documento define:
- o modelo de dados;
- o ciclo da fatura;
- o tratamento contábil da compra e do pagamento;
- os parcelamentos e a regra de centavos;
- o estorno;
- a utilização;
- o efeito da dívida no saldo.

## Decision

### 1. Módulo `cards` e agregado `CreditCard`

- **Novo módulo `cards`**, a jusante do Financial Core: `identity → accounts → transactions → cards`.
  - Cards cria compras, estornos e pagamentos **somente por casos de uso de Transactions**, como a Ingestion faz
    (ADR-0009 §1). Nunca escreve na tabela `transactions`.
  - Transactions conhece o cartão só como uma referência opaca (`card_id`). A integridade por Workspace é garantida
    pela FK composta no banco.
  - ArchUnit:
    - Identity, Accounts e Transactions nunca dependem de `cards`;
    - `cards` não usa adapters de outros módulos;
    - Overview, Analytics, Health e Insights podem ler as portas de Cards (6.2).
- **`CreditCard`** (agregado), rota aninhada por Workspace e regra do 404 uniforme (ADR-0003/0004). Campos:
  - **apelido**: obrigatório, até 100 caracteres;
  - **instituição**: texto livre opcional, como `institution_name` do ADR-0004 §3;
  - **bandeira**: `VISA`, `MASTERCARD`, `ELO`, `AMEX`, `HIPERCARD` ou `OTHER`. A UI usa visual genérico, sem marca
    real (`07-design-decisions` §9);
  - **`last4`**: exatamente 4 dígitos, com `check` no banco;
  - **limite**: `Money` > 0, em BRL;
  - **dia de fechamento e dia de vencimento**: 1–31, diferentes entre si;
  - **conta de pagamento padrão**: opcional, ativa e do mesmo Workspace (FK composta). Só sugere a origem no
    formulário de pagamento;
  - **status** `ACTIVE`/`ARCHIVED`;
  - `version`: lock otimista, `409 CONFLICT`.
- **Nunca** armazenar número completo, CVV, validade ou senha.
  - Não existem colunas para esses dados.
  - A API rejeita qualquer campo com mais de 4 dígitos no `last4`.
  - Logs só com `cardId` e `workspaceId`.
- **Arquivar em vez de excluir** (ADR-0004 §6).
  - O cartão arquivado não recebe compras nem pagamentos novos.
  - Estornar ou cancelar lançamentos existentes continua possível.
  - Pode ser reativado.

### 2. Compras de cartão são transações com `card_id` (decisão central)

- **`transactions` ganha `card_id`**, com FK composta `(card_id, workspace_id)` para `credit_cards`.
  - `account_id` passa a ser nulo quando há cartão.
  - `check`: **exatamente um** entre `account_id` e `card_id`.
- **Tipos aceitos com `card_id`:**
  - `EXPENSE` (compra);
  - `REFUND` (estorno ou crédito na fatura).
  - `INCOME` e `ADJUSTMENT` no cartão estão fora do MVP (§14).
- **Pagamento de fatura** = `TRANSFER` com `account_id` (origem) e o novo `destination_card_id` (FK composta).
  - `check`: `destination_account_id` e `destination_card_id` são mutuamente exclusivos, e só existem em `TRANSFER`.
- **Status das compras:** nascem `POSTED`. `PENDING` no cartão fica fora do MVP. `reverse` segue o ADR-0005 §7 (§8
  abaixo).
- Os fatos continuam imutáveis (ADR-0005 §8). Categoria, comerciante e regras automáticas valem igual para compras
  no cartão.
- **Efeito:** a compra não altera o saldo de nenhuma conta.
  - `Transaction.balanceEffectOn(accountId)` e `originEffect` ganham o caso do cartão: compra e estorno são `NONE`
    para contas.
  - A transferência para cartão debita a origem e não credita nenhuma conta.
  - O `CashFlowRole` não muda: compra = `EXPENSE`, estorno = `REFUND`, pagamento = `TRANSFER`.

### 3. Competência × caixa (sem dupla contagem)

| Fato | Saldo das contas | Fluxo de caixa da Home e Análise | Fatura |
|---|---|---|---|
| Compra à vista (`EXPENSE` + `card_id`) | não muda | despesa na data da compra | entra na fatura do ciclo |
| Compra parcelada (`EXPENSE` + `card_id`, valor total) | não muda | **despesa total na data da compra** | uma parcela por fatura (§6) |
| Estorno no cartão (`REFUND` + `card_id`) | não muda | reembolso na data do estorno (ADR-0006 §9) | crédito (§8) |
| Pagamento (`TRANSFER` conta → cartão) | debita a conta | **só em `transfers`** (informativo) | abate o saldo em aberto (§7) |

- A compra é contada **uma vez**, na data da compra. O pagamento nunca é despesa.
- No exemplo do notebook, a despesa de R$ 4.800 entra em julho (12 jul). As faturas de agosto a maio só distribuem
  o valor. Consequência aceita: o mês da compra mostra o pico do gasto.
- O fluxo de caixa passa a considerar contas ativas incluídas no total (como hoje) **e cartões ativos**. O cartão
  arquivado sai do fluxo, como a conta arquivada (ADR-0006 §8/§9).

### 4. Saldo e Overview (decisão agora, implementação na 6.2)

- **Saldo total = só contas**, como hoje (ADR-0006 §8) e como o design diz ("cartões não entram no saldo").
  - A compra não muda o saldo total.
  - O pagamento reduz o saldo total, porque o dinheiro saiu das contas.
- **A dívida do cartão aparece num bloco separado, "Faturas a pagar":** total em aberto dos cartões ativos e próximo
  vencimento. Não há "saldo líquido".
- Adendo correspondente no ADR-0006.

### 5. Fatura: ciclo, datas, estados e valor

- **Período da fatura:** `[fechamento anterior, fechamento)`. A compra feita **no dia do fechamento vai para a
  próxima fatura**.
- **Fechamento do mês M:** dia de fechamento do cartão; se o mês não tiver esse dia, o último dia do mês (31 → 30 set;
  29/30/31 → 28 ou 29 fev).
- **Vencimento:**
  - no mesmo mês do fechamento se o dia de vencimento for maior que o de fechamento; senão, no mês seguinte;
  - dia limitado ao último dia do mês;
  - exemplos: Aurora (fecha 28, vence 05) → fecha 28 set e vence 05 out; Horizonte (fecha 05, vence 12) → fecha 05 out
    e vence 12 out.
- **Nome e chave:** a fatura é identificada pelo **mês do vencimento** ("Fatura de outubro"), com
  `unique (card_id, due_month)`.
- **Fins de semana e feriados:**
  - o fechamento **não** é ajustado;
  - o **vencimento efetivo** (usado só para `OVERDUE`) passa de sábado ou domingo para a segunda-feira;
  - feriados não são considerados, porque não há fonte no sistema (§14). A data exibida continua a nominal;
  - quando a importação (6.2) trouxer as datas reais de uma fatura, elas substituem as calculadas **daquela** fatura.
- **Persistência:** a tabela `card_invoices` guarda `period_start`, `closing_date`, `due_date`, `minimum_payment`
  (nulo; só preenchido "Informado no arquivo", 6.2) e a origem das datas (`CALCULATED`, depois `IMPORTED`).
  - **Não há coluna de valor.**
  - A linha é criada quando a primeira parcela é atribuída. Uma fatura sem lançamentos existe virtualmente, com valor
    zero.
- **Mudança de dia de fechamento ou vencimento:**
  - vale para as faturas ainda **não fechadas**;
  - na mesma transação de banco, as datas delas são recalculadas e as parcelas abertas e futuras reatribuídas;
  - faturas fechadas ficam congeladas.
- **Valor (sempre derivado):** Σ parcelas não canceladas de compras `POSTED` − créditos atribuídos (§8).
- **Estados** (derivados de "hoje" no fuso de negócio, ADR-0006 §10; não persistidos):
  - `FUTURE`: o período ainda não começou (só parcelas contratadas);
  - `OPEN`: período em curso. **"Parcial, pode mudar até o fechamento."** Um pagamento antecipado não muda o estado;
  - fechada e não vencida: `CLOSED` (nada pago), `PARTIALLY_PAID` (0 < pago < valor) ou `PAID` (pago ≥ valor, ou
    valor ≤ 0);
  - `OVERDUE`: passou do vencimento efetivo e pago < valor.
- **Versão das regras:** `invoice-v1`, devolvida nas respostas e gravada nas parcelas. Mudar a regra exige versão
  nova e recálculo explícito.

### 6. Parcelamentos

- Toda compra no cartão tem um plano de **n parcelas**, de 1 a 48 (à vista = 1).
  - Cada parcela vale pelo menos R$ 0,01.
  - O total pago já inclui eventuais juros. "Juros" só é exibido quando vier "Informado no arquivo" (6.2).
- **Centavos:**
  - base = total ÷ n, truncado em centavos;
  - resto = total − base × n;
  - **o resto vai para a 1ª parcela**;
  - exemplos: 100,00 em 3× → 33,34 + 33,33 + 33,33; 4.800 em 10× → 10 × 480,00.
- A parcela *k* cai na fatura do ciclo da compra deslocada *k*−1 meses.
  - Notebook (12 jul, Aurora): parcela 1 em agosto, 3 em outubro, 10 em maio/2027.
- **Tabela `card_installments`:** transação, número, total de parcelas, valor, valor cancelado, fatura e versão.
  - Gerada na mesma transação de banco da compra.
  - Recalculável de forma determinística a partir da compra, dos estornos vinculados e do cartão.
- **Status da parcela** (derivado): `PAID` (fatura `PAID`), `CURRENT` (fatura `OPEN`, `CLOSED`, `PARTIALLY_PAID` ou
  `OVERDUE`), `FUTURE` (fatura `FUTURE`) ou `CANCELLED` (consumida por estorno, §8).
- **Limite reservado:** Σ das parcelas em faturas `FUTURE`. O limite é liberado a cada pagamento (§9).
- Parcelamento (compra em n vezes) e recorrência (assinatura) continuam sendo conceitos distintos (05.13 §5).

### 7. Pagamento de fatura

- **Registro manual, total ou parcial**, como `TRANSFER` conta → cartão, com `Idempotency-Key` obrigatória
  (ADR-0005 §11).
  - Exige conta e cartão ativos.
  - Valor > 0. Pagar acima do saldo é permitido, e o excesso vira crédito.
- **Alocação determinística**, independente da ordem dos pagamentos:
  1. começa com P = Σ pagamentos `POSTED` do cartão;
  2. percorre as faturas não futuras em ordem de vencimento;
  3. fatura com valor ≤ 0 soma o crédito dela a P;
  4. fatura com valor > 0 recebe min(valor, P), e esse valor sai de P;
  5. o que sobrar no fim é **crédito do cartão**, que abate as próximas faturas.
- **Pagamento real (débito no banco) está fora do MVP:** o sistema só registra o que o usuário informou ou importou.
- **Conciliação (6.2):** o débito "PAG FATURA" importado no extrato da conta nasce `EXPENSE`.
  - Uma ação "marcar como pagamento de fatura", no padrão do `mark-as-refund` (ADR-0009 §9), estorna a despesa e cria
    a `TRANSFER` para o cartão.
  - O casamento com um pagamento manual já registrado depende de decisão (§14).

### 8. Estornos, créditos e correções

- **Estorno do banco** = `REFUND` com `card_id`.
  - Opcionalmente vinculado à compra (`refundOfTransactionId`), com as regras do ADR-0005 §6: compra `EXPENSE` `POSTED`
    do mesmo Workspace, soma ≤ original e lock no original.
  - "Marcar como reembolso" (ADR-0009 §9) vale também para um crédito importado no cartão (6.2).
- **Atribuição do crédito:**
  - Sem vínculo, ou com compra à vista: o crédito entra na fatura do ciclo da data do estorno.
  - Com vínculo a uma compra parcelada: o crédito **consome primeiro as parcelas ainda não faturadas**, cuja fatura
    começa depois da data do estorno, **da última para a primeira**. Essas parcelas viram `CANCELLED` (total ou
    parcialmente). O restante vira crédito na fatura do ciclo da data do estorno.
  - Exemplo: estorno total do notebook em 22 set cancela as parcelas 4–10 (R$ 3.360) e credita R$ 1.440 na fatura de
    outubro (as parcelas 1–3 já faturadas).
- **`reverse` da compra** (ADR-0005 §7) é a correção de um lançamento errado.
  - A compra sai de todas as faturas, inclusive das fechadas, porque nunca existiu.
  - A trilha continua no histórico. Se a fatura estava paga, o excesso vira crédito (§7).
- `reverse` do estorno recalcula as parcelas da compra vinculada.

### 9. Utilização

- **Utilizado** = max(0, Σ valor de todas as faturas, inclusive futuras − Σ pagamentos).
  - Na prática: saldo em aberto das faturas fechadas + fatura aberta + parcelas futuras.
  - **Disponível** = limite − utilizado, podendo ser negativo quando o limite é excedido.
- **Utilização** = utilizado ÷ limite, como percentual inteiro (`HALF_EVEN`), sempre acompanhado dos valores de
  composição: fatura atual, parcelas futuras e saldo de faturas anteriores.
  - Aurora: (3.412,80 + 3.360,00) ÷ 12.000 = 56,44% → **56%**.
  - Sem parcelas futuras: 3.412,80 ÷ 12.000 → **28%**.
- A evolução histórica da utilização ("era 49% em agosto") depende de snapshot e fica para a 6.2 (§14).

### 10. Faturas futuras

- Mostram **só o valor já comprometido** (parcelas contratadas), como fato.
- A "estimativa pelo seu padrão" (D-Invoice, D-FutureInvoices) é previsão (M9) e nunca é somada ao comprometido.

### 11. Eventos (outbox, v1, idempotentes)

- **Ciclo de vida do cartão:** `card.created`, `card.updated` (limite, dias ou conta padrão), `card.archived` e
  `card.reactivated`. Payload: só `cardId`. O consumidor relê a fonte.
- **Compras, estornos e pagamentos:** os `transaction.*` existentes (ADR-0014 §2), com `transactionId` e
  `occurredOn`, sem campos novos. Quem precisa do cartão relê a transação.
- **`invoice.closed`**, com `cardId`, `invoiceId` e `dueMonth`.
  - Gravado por um job diário, no fuso de negócio, para as faturas com lançamentos cujo fechamento já passou.
  - Idempotente pela marca `closed_notified_at` na própria fatura, gravada na mesma transação da outbox.
  - Consumidores previstos (6.2): Insights (fatura acima do padrão) e Health (Credit).
- Os consumidores seguem o padrão inbox + retry + DLQ (ADR-0009 §15, ADR-0014 §4).

### 12. Determinismo, auditoria e testes

- **Cálculo no backend:** ciclo, atribuição, parcelas, estado, alocação de pagamentos e utilização são funções puras
  do domínio de Cards. O frontend só exibe.
- **Paridade SQL:** todo SQL novo de fatura ou utilização tem teste de paridade contra uma referência em memória
  (padrão do ADR-0006 §3).
- **Testes de tabela com os valores exatos do Aurora**, mais as bordas:
  - dia 31 e fevereiro (inclusive bissexto);
  - virada de ano;
  - compra no dia do fechamento;
  - vencimento no fim de semana;
  - centavos;
  - estorno parcial e total de parcelado;
  - pagamento parcial, excedente e atrasado.

### 13. Entrega nesta sessão

- **Parte B:** módulo `cards`, `CreditCard`, regras de ciclo, `card_invoices` e `card_installments`, leitura de
  faturas, fatura atual, faturas futuras comprometidas e utilização, eventos `card.*`, `invoice.closed` e API
  (ADR-0026).
- **Parte C:**
  - colunas `card_id` e `destination_card_id` em `transactions`;
  - compra à vista e parcelada, estorno e pagamento;
  - a adaptação **mínima** do SQL do Overview e da projeção de Analytics, para que as compras no cartão entrem em
    `expense` e os pagamentos em `transfers` (sem isso a compra sumiria dos gastos);
  - revisão de todo SQL que hoje junta `transactions` com `accounts`;
  - UI de transferência e reembolso (pendência do M2), formulários de compra e pagamento, E2E.

### 14. Pendente para a Sessão 6.2

**Já decidido aqui, a implementar:**
- bloco "Faturas a pagar" no Overview (§4);
- filtro "Conta ou cartão" e "pago com / fatura vinculada" no detalhe da transação;
- telas `/cards`, fatura, faturas futuras e parcelamento;
- chip "Cartões" nos Insights;
- consumidores de `card.*` e `invoice.closed`;
- datas reais e pagamento mínimo vindos do arquivo (§5).

**Precisa de decisão:**
- formato do CSV de fatura e leitura de `CCSTMTRS` (o ADR-0009 §5 deixa de rejeitar); dedupe por cartão;
- detecção de parcela no extrato ("PARC 03/10"): como reconstruir a compra original (data e total) e o que fazer
  quando a 1ª parcela não está no arquivo;
- linhas "PAGAMENTO RECEBIDO" do extrato do cartão e conciliação/dedupe com o pagamento manual e com o débito
  importado da conta;
- saldo devedor inicial ao cadastrar um cartão que já tem fatura em aberto (hoje: lançar as compras);
- feriados no vencimento efetivo (fonte do calendário);
- fórmulas de Credit (utilização) e Debt (parcelas comprometidas × renda) do `health-v2`;
- detectores de cartão nos Insights (fatura acima do padrão, utilização alta, parcelamento novo);
- histórico de utilização (snapshot mensal) para "era 49% em agosto";
- `INCOME`/`ADJUSTMENT` no cartão (anuidade, IOF, juros importados) e `PENDING` no cartão.

## Alternatives

- **Cartão como conta passiva** (`Account` do tipo `CREDIT_CARD`): rejeitada.
  - A seu favor: saldo negativo como dívida, e transferência e importação por `accountId` sem mudança.
  - Contra: contradiz o ADR-0004 §5 e mistura limite e ciclo com conta. Toda lista de contas, todo seletor, o saldo
    total e o fluxo de caixa precisariam incluir ou excluir cartões caso a caso.
- **Compras como entidade própria de `cards`:** rejeitada.
  - Overview, Analytics, Health e Insights teriam de unir duas fontes.
  - Categorização, comerciantes, reembolso e dedupe ficariam duplicados.
  - O risco de dupla contagem seria alto.
- **Cada parcela como despesa no mês da fatura:** rejeitada (decisão aprovada).
  - Criaria fatos futuros e mudaria a data de competência.
  - Complicaria o estorno e contrariaria "compras no cartão entram pela data da compra".
- **Dívida subtraída do saldo total, ou "saldo líquido" secundário:** rejeitadas (decisão aprovada). O componente de
  saldo do design diz que cartões não entram no saldo.
- **Fechamento inclusivo** (compra no dia do fechamento na fatura que fecha): rejeitado (decisão aprovada).
- **Valor da fatura persistido:** rejeitado. Seria uma segunda fonte de verdade; o valor é sempre derivado.
- **Resto dos centavos na última parcela:** rejeitado. A prática dos emissores e a leitura mais simples ("a primeira
  é a maior") favorecem a 1ª.
- **Pagamento vinculado a uma fatura escolhida pelo usuário:** rejeitado no MVP. A alocação pela ordem de vencimento
  é determinística, cobre pagamento parcial, excedente e importação, e não depende de escolha.
- **Ajuste de vencimento por feriados agora:** adiado, por não haver fonte de calendário confiável.

## Consequences

- **Positivas:**
  - uma lista única de transações, com categorias, comerciantes, reembolso, idempotência e eventos reaproveitados;
  - nenhuma dupla contagem: compra é despesa, pagamento é transferência;
  - fatura, parcelas e utilização derivadas e reproduzíveis, com paridade testada;
  - dados de cartão mínimos (só os 4 últimos dígitos).
- **Negativas:**
  - `transactions.account_id` passa a aceitar nulo, e todo SQL que junta com `accounts` precisa ser revisto (Overview,
    Analytics, resumo, Health, Insights). É a principal fonte de risco da parte C;
  - a projeção de Analytics ganha o cartão no grão (`RebuildAnalyticsProjection.VERSION` + 1);
  - o mês da compra parcelada mostra o pico do gasto;
  - arquivar um cartão tira as compras dele do fluxo, como já acontece com contas;
  - vencimento em feriado pode aparecer como `OVERDUE` por um dia útil (§14).

## Security / Privacy

- Sem número completo, CVV, validade ou senha: não há coluna, a API recusa e há `check` de 4 dígitos.
- Logs só com `cardId`, `invoiceId` e `workspaceId`. Nunca apelido, `last4`, limite ou valores (há teste).
- Isolamento:
  - FKs compostas `(card_id, workspace_id)` em `transactions`, `card_invoices` e `card_installments`;
  - FK composta da conta padrão;
  - cartão ou fatura alheios → `404` idêntico ao inexistente;
  - endpoints novos entram no `WorkspaceIsolationIT`.

## Financial Integrity

- `Money`/`NUMERIC(19,4)`, sem float/double.
- Parcelas com regra de centavos explícita: a soma das parcelas é **sempre** igual ao total (`check` + teste de
  propriedade).
- Valor da fatura, estado e utilização são sempre derivados de transações `POSTED`. Nenhum saldo de cartão
  persistido.
- Estorno nunca excede a compra (ADR-0005 §6).
- Mudanças de regra são versionadas (`invoice-v1`).
- Estimativas nunca entram em fatura nem em utilização.

## Operational Impact

- **Migrations aditivas:** `credit_cards`, `card_invoices`, `card_installments`; colunas `card_id` e
  `destination_card_id` em `transactions`; ampliação dos `check`.
- **Job diário** do `invoice.closed`; métricas e traces conforme 05.9:
  - `shf.cards.*` (cartões, compras, pagamentos);
  - duração das leituras de fatura;
  - idade do último `invoice.closed`.
- **Rebuild do Analytics** (versão 3) na parte C, com o runbook existente.
- Sem dependência nova.

## Related Specs

- `specs/01-product/mvp.md`, `features.md`, `jobs-to-be-done.md`, `principles.md`
- `specs/03-ux/ux.md`, `core-user-flows.md` (Credit Card)
- `specs/05-system-design/05.2`, `05.3`, `05.4`, `05.6`, `05.7`, `05.9`, `05.12`, `05.13`, `05.14`
- `design/specs/02-components.md`, `03-screens.md` (CARDS, D-TxnDetail), `05-states.md`, `09-dados-de-exemplo.md`
- `docs/adr/ADR-0004`, `ADR-0005`, `ADR-0006`, `ADR-0009`, `ADR-0014`, `ADR-0017`, `ADR-0021`, `ADR-0024`

## Date

2026-10-22
