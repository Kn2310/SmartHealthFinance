# ADR-0033 — UI de Forecast nas telas existentes (M9, Sessão 7.1, parte D)

## Status

Accepted

> Decisões aprovadas em 2026-11-05:
> - gestão de recorrências numa página `/recurring`, sem item de menu, acessada pelas telas onde as estimativas
>   aparecem;
> - "Próximos eventos" da Home mostram os fatos do Overview quando o Forecast não está pronto;
> - estimativa como "≈" sem centavos, com a faixa visível e o selo "Estimado";
> - o D15 aparece como os demais insights.

## Context

O ADR-0032 entregou as leituras do Forecast e os recursos de recorrências. O design prevê estimativas em telas que
já existem:
- D-Home: "Próximos eventos (30 dias, Estimado/Confirmado)";
- D-AnalysisSpending: "projeção do mês (Estimado)";
- D-AnalysisIncome: "próxima receita prevista (Estimado)";
- D-Invoice e D-FutureInvoices: "estimativa separada".

O design não tem tela de Forecast nem de gestão de recorrências. A regra inviolável é que o frontend só exibe: nenhuma
soma, faixa ou confiança é calculada no cliente.

## Decision

### 1. Como uma estimativa aparece

- **Componente do DS para valor projetado:**
  - um `ESTIMATED` aparece como "≈ R$ 4.664", sem centavos, com sublinhado tracejado (token de estimado), selo
    "Estimado" (badge com borda tracejada) e, abaixo, "entre R$ a e R$ b";
  - um `FACT` aparece com centavos e o selo "Confirmado". Um fato parcial usa o selo "Parcial";
  - o texto alternativo é "aproximadamente R$ X, entre R$ a e R$ b, estimado" (ADR-0030 §4). O "≈" e o tracejado
    são decorativos (`aria-hidden`).
- **Confiança e base:** "confiança alta/média/baixa · pelo seu padrão dos últimos N meses", com N = os meses de base
  ou o número de evidências mensais da resposta. As limitações (`SHORT_HISTORY`, `HIGH_VARIABILITY`, ...) viram uma
  frase curta.
- **Atualização:** com `freshness.state = RECALCULATING`, aparece "Atualizando estimativas…" e o valor anterior
  continua visível.
- **Sem estimativa:** com `NOT_BUILT` ou `INSUFFICIENT_HISTORY`, só os fatos aparecem, mais uma nota curta ("As
  estimativas aparecem depois de 3 meses de histórico." ou "Estimativas em preparação.").
- Nenhuma cor literal: só tokens (ADR-0007 §13, teste de conformidade). Tudo responsivo de 375 a 1440.

### 2. Home: "Próximos eventos"

- A fonte é `/forecast/upcoming`: fatos e estimados na ordem da API, cada um com o seu selo.
  - Recorrências estimadas mostram "Recorrente · {conta}".
  - Faturas abertas mostram o fato "Parcial" e, ao lado, "≈ no fechamento".
- **Fallback aprovado:** sem snapshot (`NOT_BUILT`) ou com erro no Forecast, o card mostra os eventos de fatura do
  Overview (só fatos, como hoje), com a nota "Estimativas em preparação".
- Ação "Gerenciar recorrências" leva a `/recurring`.

### 3. Análise

- **Gastos:** "Projeção para o mês ≈ …", com faixa, a frase "pelo seu padrão dos últimos N meses" e o realizado
  (fato) visível. Isso substitui o placeholder adiado pelo ADR-0016.
- **Receitas:** "Próxima receita prevista ≈ …", com a fonte, a data, "pelo seu histórico" e a faixa. Sem receita
  recorrente, aparece "Sem receita recorrente identificada".
- As duas abas ganham o link "Ver recorrências".

### 4. Cartões

Adendo ao ADR-0029 §4: a estimativa deixa de ficar oculta.

- **D-Invoice, bloco "Próxima fatura · {mês}":** mostra duas linhas separadas, e o total só aparece como "≈" com as
  duas partes visíveis:
  - "Já comprometido (parcelas)", como fato com selo "Confirmado";
  - "Novas compras pelo seu padrão ≈", como estimativa com a faixa.
- **Fatura aberta:** ganha "≈ no fechamento", ao lado do valor parcial (fato).
- **D-FutureInvoices:**
  - por mês, o comprometido (fato) e as novas compras estimadas lado a lado;
  - no gráfico, o trecho estimado é tracejado, e há uma tabela acessível;
  - o aviso "Novas compras ainda não aparecem aqui." só continua sem estimativa (`PARTIAL_CARD_HISTORY`).

### 5. Página `/recurring`

- **Título:** "Recorrências".
- **Abas:** "Despesas" e "Receitas" (`?tab=`).
- **Seções** em cada aba:
  - "Para revisar": `DETECTED`;
  - "Confirmadas";
  - "Ignoradas", recolhida.
- **Item:** nome, canal (conta ou cartão), "todo dia {d}", valor esperado com faixa, confiança e selo de origem
  ("Detectada" ou "Ajustada por você").
  - `STALE` aparece como "Sem ocorrência recente".
  - Uma série não elegível mostra "Não entra nas estimativas".
- **Ações:**
  - "Confirmar" e "Ignorar". "Ignorar" desfaz com toast, chamando confirmar de volta;
  - "Ajustar" abre um drawer ou sheet com valor e dia e o botão "Voltar ao detectado".
  - Depois da ação aparece "Atualizando estimativas…", com o mesmo `forecastState` da resposta.
- **Estados:** loading, vazio ("Ainda não identificamos recorrências. Elas aparecem depois de 3 meses de
  histórico."), erro e responsivo.
- **Acesso:** sem item na navegação. Chega-se pela Home ("Gerenciar recorrências"), pela Análise ("Ver
  recorrências") e por um item em M-More.

### 6. D15

- Aparece como os demais insights: na lista (chip Gastos), em "Merece atenção" quando é Importante e no detalhe com
  as evidências (menor saldo, faixa e maiores movimentos).
- A ação `VIEW_UPCOMING_EVENTS` ("Ver próximos eventos") leva a `/home#events`.
- A limitação `ESTIMATED_FORECAST` vira "Baseado em estimativa".

### 7. BFF e testes

- **BFF:**
  - `GET` de `/forecast/{month,next-income,upcoming,invoices,cash-flow,status}`;
  - `GET`, `POST confirm/ignore`, `PUT` e `DELETE adjustment` de `/recurring-{expenses,incomes}`;
  - validação de parâmetros no padrão das rotas existentes.
- **Testes de unidade e componentes:** Vitest e RTL com axe em cada tela e estado, e as rotas do BFF.
- **Suíte completa** antes de cada push.
- **E2E:**
  1. o histórico com recorrências determinadas gera os próximos eventos, a projeção do mês e a estimativa de fatura,
     com os valores exatos do `forecast-v1`;
  2. confirmar e ignorar uma recorrência produz o efeito esperado;
  3. o teste roda nas 5 larguras.

### 8. Adendo (2026-11-05): detalhes da implementação

Pontos que o ADR deixava em aberto, resolvidos sem mudar nenhuma decisão:
- **Faixa das novas compras (Cartões):** a API devolve a faixa só no total (`total.range`). Como o cliente não
  calcula, "Novas compras pelo seu padrão" mostra "≈ valor" com o selo, e a faixa aparece na linha "Total
  estimado". Se for preciso ter a faixa por parte, o contrato ganha um campo aditivo.
- **Projeção do mês em outro mês:** o `/forecast/month` vale só para o mês corrente. Com outro mês escolhido na
  Análise, aparece "A projeção vale só para o mês corrente."
- **Títulos das faturas na Home:** para manter "Fechamento" e "Vencimento" e o mês da fatura, os eventos de fatura do
  Forecast são cruzados com os do Overview pelo cartão e pela data. Sem correspondência, o título fica "Fatura
  {cartão}". O valor exibido é sempre o da API.
- **Home sem cartão:** "Próximos eventos" aparece quando há recorrências na agenda, mesmo sem cartão ativo.
- **D-FutureInvoices:** os meses vêm das faturas `NEXT` e `FUTURE` do Forecast, e as parcelas vêm de Cartões. Sem
  estimativa, a tela é a de antes.

## Alternatives

- **Nova aba em Análise ou seção no Perfil:** muda as abas do design, ou fica longe de onde as estimativas aparecem.
- **Só `/forecast/upcoming`, sem fallback:** um problema no Forecast esconderia fatos que hoje aparecem.
- **"≈" sem faixa visível:** a incerteza ficaria escondida.
- **Alerta do D15 dentro de "Próximos eventos":** duplicaria o insight.

## Consequences

- As quatro telas do design ganham as estimativas sem mudar a navegação.
- Uma tela nova (`/recurring`), feita só com componentes do DS.
- O card de eventos da Home tem duas fontes (Forecast e o fallback do Overview).

## Security / Privacy

- O BFF só repassa ids opacos. Nenhum valor em URL ou log do cliente.

## Financial Integrity

- O frontend só exibe. O "≈ sem centavos" é só formatação: o valor exato vem da API, e a faixa e as partes também.

## Operational Impact

- Rotas novas no BFF e uma página nova. Nenhuma dependência nova.

## Related Specs

- `docs/adr/ADR-0030`, `ADR-0031`, `ADR-0032`, `ADR-0029` (adendo), `ADR-0016` (adendo), `ADR-0024`, `ADR-0007`
- `design/specs/01-foundations.md`, `02-components.md`, `03-screens.md`, `05-states.md`, `06-responsive.md`,
  `07-design-decisions.md`

## Para a Sessão 7.2

Preenchido ao fim desta parte.

## Date

2026-11-05
