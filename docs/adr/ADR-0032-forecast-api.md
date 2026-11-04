# ADR-0032 — API de Forecast e recorrências (M9, Sessão 7.1, parte C)

## Status

Accepted

> Decisões aprovadas em 2026-11-04:
> - recorrências em dois recursos irmãos, `/recurring-expenses` e `/recurring-incomes`, com o mesmo schema;
> - id UUID próprio da série (migration aditiva `V21`), sem a chave do comerciante na URL;
> - um endpoint de leitura por bloco do Forecast;
> - `/forecast/upcoming` devolve a agenda completa de 30 dias (fatos e estimados), sem mudar o Overview;
> - ações em rotas próprias, idempotentes e sem `Idempotency-Key`;
> - leituras sempre `200` com `state` e `freshness`;
> - o D15 (`insights-v3`) entra no backend nesta parte;
> - evidências inline e compactas.

## Context

O ADR-0030 fixou o modelo `forecast-v1`, e o ADR-0031 fixou o snapshot por eventos, a `V20`, as ações como casos de
uso e o `GET /forecast/status`. Faltava o contrato: as leituras que as telas da parte D vão consumir, o recurso de
recorrências do 05.6 e as ações sobre ele.

Restrições:
- **Fronteiras:** pelo ADR-0031 §1, nenhum módulo além de `insights` depende de `forecast`. Por isso Overview e
  Cards não podem ganhar campos de previsão.
- **Recurso:** o 05.6 lista `/recurring-expenses`, mas o modelo também tem receitas recorrentes.
- **Identificação da série:** hoje é feita por (Workspace, direção, chave do comerciante). A chave contém o nome
  normalizado do comerciante, que não deve ir para a URL nem para os logs de acesso (05.12).

## Decision

### 1. Leituras do Forecast

Todas ficam em `GET /api/v1/workspaces/{workspaceId}/forecast/...` e leem só o snapshot gravado. Nenhuma calcula
na requisição.

| Rota | Conteúdo (ADR-0030) |
|---|---|
| `/month` | §3.1: despesa, receita e resultado projetados do mês corrente, com `baseMonths` e os pendentes |
| `/next-income` | §3.2: a próxima receita prevista (fonte, data, conta e valor) |
| `/upcoming` | §3.3: a agenda de 30 dias **completa** e já ordenada, com os eventos de fatura (fatos, os mesmos do Overview) e as recorrências estimadas. A fatura aberta traz o "≈ no fechamento" ao lado |
| `/invoices?cardId=` | §3.5: a fatura aberta (fato parcial + fechamento estimado) e as futuras até o horizonte (comprometido + novas compras), por cartão. Sem `cardId`, todos os cartões |
| `/cash-flow` | §3.4: os meses do horizonte, com a faixa e o menor saldo projetado (central e limite baixo) |
| `/status` | como no ADR-0031 §9 |

- **Envelope comum:**
  - `state`, que pode ser:
    - `READY`;
    - `NO_DATA`: sem transações;
    - `INSUFFICIENT_HISTORY`: só os fatos, e as estimativas vêm nulas;
    - `NOT_BUILT`: sem snapshot, e os blocos vêm nulos;
  - `freshness {state: UP_TO_DATE | RECALCULATING | NOT_BUILT, computedAt, asOf}`;
  - `algorithmVersion` (`forecast-v1`) e `asOf`.
  - Em `RECALCULATING`, a resposta é o snapshot existente.
  - A leitura com `as_of` anterior a hoje marca o Workspace (ADR-0031 §6).
- **Valor projetado** (`ForecastValue`), em todo valor exposto:
  - `basis` (`FACT` | `ESTIMATED`), `value`, `factPart`, `estimatedPart`, `range {low, high}`, `partial`,
    `confidence`, `limitations[]` e `evidence[]`;
  - cada evidência tem a forma `{kind, month, refId, value}`;
  - dinheiro como `MoneyDto` (string);
  - nenhum texto gerado: a UI monta as frases a partir dos campos;
  - `SIMULATED` não aparece no v1.
- **Quem pode ler:**
  - a autorização vem antes de qualquer validação (ADR-0003): um não membro recebe `404 WORKSPACE_NOT_FOUND`;
  - um `cardId` que não é do Workspace recebe `404 CARD_NOT_FOUND`, igual a um cartão inexistente.
- Os schemas usam o prefixo `Forecast` para não colidir no OpenAPI (ADR-0010).

### 2. Recorrências

Dois recursos irmãos, com o mesmo schema e as mesmas ações:
- `/api/v1/workspaces/{workspaceId}/recurring-expenses`;
- `/api/v1/workspaces/{workspaceId}/recurring-incomes`.

Rotas:

| Rota | Efeito |
|---|---|
| `GET /` (`?status=`) | Lista as séries da direção, sem paginação (poucas por Workspace). Ordem: elegíveis primeiro, depois o maior valor esperado |
| `GET /{seriesId}` | Detalhe da série |
| `POST /{seriesId}/confirm` | `CONFIRMED`. Também desfaz o "ignorar" |
| `POST /{seriesId}/ignore` | `IGNORED`. A série não volta a ser sugerida |
| `PUT /{seriesId}/adjustment` | `{expectedAmount?, expectedDay?}`: os valores do usuário (pelo ADR-0031 §11, não mudam a elegibilidade) |
| `DELETE /{seriesId}/adjustment` | Volta aos valores detectados |

- **Item da lista:**
  - identificação: `id`, `direction`, `label` (nome do comerciante ou da categoria);
  - canal: `channel {kind, id}`;
  - `periodicity`;
  - valores: `expectedDay`, `expectedAmount` e `amountRange`, que são os efetivos (os do usuário, se houver);
  - `detected {expectedDay, expectedAmount, amountRange, confidence}`;
  - ajuste: `userAdjusted`;
  - estado: `status`, `origin` e `eligibility` (`ELIGIBLE` | `STALE` | `NOT_ELIGIBLE`);
  - ocorrências: `nextExpectedDate`, `realizedThisMonth`, `occurrences[]` (ids de transação);
  - `algorithmVersion`.
- **Ações:**
  - são idempotentes, sem `Idempotency-Key` (como o `dismiss` dos Insights);
  - respondem `200` com a série e `forecastState: RECALCULATING`, porque o recálculo é assíncrono (ADR-0031 §5);
  - erros:
    - `404 RECURRING_SERIES_NOT_FOUND`, também para a série de outro Workspace ou da outra direção;
    - `400 VALIDATION_FAILED` para dia fora de 1–31, valor ≤ 0 ou com mais de 2 casas, ou corpo vazio no `PUT`.
- **Id da série (`V21`):**
  - a migration `V21__add_forecast_series_id.sql` é aditiva: coluna `id uuid not null unique`, preenchida para as
    linhas existentes;
  - a chave natural continua a mesma;
  - a redetecção nunca troca o id.

### 3. Insight D15 (`insights-v3`)

- `BALANCE_MAY_GO_NEGATIVE`, tipo `SPENDING`, sobre o `ForecastReader` (ADR-0030 §8):
  - **gatilho:** o limite baixo do saldo total projetado nos próximos 30 dias fica abaixo de zero, com déficit de
    pelo menos R$ 100 e confiança de pelo menos `MEDIUM`;
  - **importância:**
    - `IMPORTANT` quando o valor central fica abaixo de zero;
    - `INFO` quando só o limite baixo fica.
- **Texto:** "Seu saldo pode ficar negativo por volta de {data}."
  - A evidência traz o menor saldo, a faixa e os maiores movimentos até a data.
  - A ação é `VIEW_UPCOMING_EVENTS` ("Ver próximos eventos", destino: Home).
- **Versão:**
  - a `insights-v3` herda `seenAt` e feedback pelo fingerprint, como a v2 (ADR-0028);
  - os D1–D14 não mudam.
- **Ciclo do insight:**
  - o insight fica preso à janela de 30 dias;
  - se deixa de ser detectado, vira `RESOLVED`.
- A UI do card é da parte D.

### 4. Testes

- Para cada rota:
  - teste de API, cobrindo estados, envelope, `basis`/partes/faixa e erros;
  - contrato OpenAPI, com snapshot e `schema.d.ts` (ADR-0010, ADR-0007 §11);
  - suíte de isolamento: leituras, lista, detalhe e cada ação contra o Workspace de outro usuário.
- Ações:
  - idempotência;
  - estado do usuário preservado depois do recálculo;
  - marcação do Workspace.
- Logs só com ids, ação e estado.
- D15: casos de tabela (gatilho, piso, confiança, importância, resolução) e IT ponta a ponta. O snapshot com saldo
  projetado negativo gera o insight.

### 5. Adendo (2026-11-04): detalhes de formato da implementação

Pontos que o contrato deixava em aberto, resolvidos sem mudar nenhuma decisão:
- **Blocos sem dados** (`NOT_BUILT`, `NO_DATA`): os escalares (`month`, `nextIncome`, `cashFlow`) vêm `null`, e as
  listas (`events`, `invoices`) vêm `[]`.
- **Formato das respostas:**
  - a lista das recorrências é `{items}`;
  - as ações respondem `{series, forecastState: "RECALCULATING"}`;
  - a série devolvida traz a elegibilidade do último snapshot até o recálculo terminar.
- **`nextExpectedDate`:** é a data esperada no mês do snapshot. Para uma série que não está no snapshot,
  `eligibility`, `realizedThisMonth` e `nextExpectedDate` vêm `null`.
- **Ajuste:**
  - a autorização vem antes de ler o corpo, então um não membro recebe `404` mesmo com valor mal formatado;
  - um valor numa moeda diferente da do Workspace é `400`.
- **Itens que referenciam uma série** (`pending`, `upcoming` e `nextIncome`) trazem o `seriesId`. A chave do
  comerciante não sai do módulo.
- **`cardId`:** a validação usa o `CreditCardRepository` de `cards.domain`, que o ArchUnit já permite.

## Alternatives

- **Recurso único `/recurring-expenses?direction=`:** o nome engana, porque as receitas ficariam dentro de
  "expenses".
- **`/recurrences`:** o nome é preciso, mas diverge do 05.6.
- **Chave composta na URL:** o nome do comerciante iria para a URL e para os logs de acesso.
- **`GET /forecast` consolidado:** payload grande para cada tela (mesmo argumento do ADR-0015).
- **Campos de previsão no Overview ou em Cards:** a fronteira do ADR-0031 §1 proíbe.
- **`PATCH` único nas ações:** mistura confirmar e editar, que são gestos distintos (ADR-0031 §11).
- **`202` sem snapshot:** diverge do Health e do Analytics.
- **D15 na parte D:** misturaria o backend dos Insights com o frontend.

## Consequences

- As telas da parte D só exibem: nenhuma regra financeira fica no cliente.
- Os eventos de fatura aparecem em dois endpoints (Overview e `/forecast/upcoming`). A regra é uma só, `CardDebts`.
- Uma migration aditiva a mais (`V21`) e uma versão nova dos Insights (`insights-v3`).

## Security / Privacy

- Rotas com id opaco: nem a URL nem os logs levam o nome do comerciante ou valores.
- Autorização por membro antes de ler ou gravar. Recursos de outro Workspace respondem como inexistentes.
- Evidências só com ids e valores do próprio Workspace.

## Financial Integrity

- O que é exibido é o que foi gravado no snapshot.
- Toda estimativa expõe as partes, a faixa e a confiança. Fato e estimativa nunca se somam sem as partes.
- As ações nunca alteram fatos (transações, faturas). Mudam só o estado da série.

## Operational Impact

- `V21` é aditiva. As rotas são novas, e nenhuma existente muda.
- Métricas: o timer `shf.forecast.query` (tags `view`, `state`) e o contador `shf.forecast.recurrence.action`
  (ADR-0031 §9).

## Related Specs

- `docs/adr/ADR-0030-forecast-model.md`, `ADR-0031-forecast-snapshot-by-events.md`, `ADR-0021`, `ADR-0028` (adendo),
  `ADR-0003`, `ADR-0010`, `ADR-0015`
- `specs/05-system-design/05.6-api-integration.md` (`/recurring-expenses`, e `/recurring-incomes` como irmão),
  `05.12`, `05.14`

## Date

2026-11-04
