# ADR-0023 — API de Insights (M7, parte C)

## Status

Accepted

> Contrato aprovado em 2026-10-17, com quatro decisões: "O que mudou?" vem **dos insights** (não da decomposição do
> ADR-0015); rótulo do card e linha do "O que mudou?" **gravados na geração**; "visto" por **POST explícito** (o GET
> não escreve); o detalhe mostra as **origens dos dados** pela porta do Overview.

## Context

A parte B (ADR-0022) grava os insights `insights-v1` (ADR-0021) com estado do usuário (visto, dispensado, feedback) e
expõe só `GET .../insights/status`. A parte D precisa de: lista com chips e contagens (`D-Insights`), detalhe com
evidências, explicação, ação recomendada, confiança e relacionados (`D-InsightDetail`), os blocos "Merece atenção" e
"O que mudou?" da Home (`D-Home`/`M-Home`), "Ver insight relacionado" no detalhe da transação (`D-TxnDetail`), o badge
de Insights no `M-More` e as ações dispensar, visto e "útil / não útil".

## Decision

### 1. Rotas (todas sob `/api/v1/workspaces/{workspaceId}/insights`)

| Rota | Conteúdo |
|---|---|
| `GET /insights?importance&type&state&page&pageSize` | Lista na ordem do ADR-0021 §3. `importance` = `IMPORTANT`/`INFORMATIVE`; `type` = `SPENDING`/`INCOME`/`HEALTH` ou `CARDS` (aceito, sempre vazio até o M8); `state` = `ACTIVE` (padrão) ou `DISMISSED`; `page` a partir de 0; `pageSize` 1–50 (padrão 20). **Contagens dos chips** sempre sobre os ativos, sem filtro: `{all, important, spending, income, health, cards: {count: 0, available: false}}`. |
| `GET /insights/{id}` | Detalhe em **qualquer estado** (um link antigo não quebra): o item, ação recomendada, números-chave, série (`kind` + pontos), contribuintes, "como comparamos", limitações, fontes (porta e período), **origens dos dados** (`CSV`/`MANUAL`/`OFX`), ações (com `available`), até 3 relacionados e a dimensão do Health afetada com a nota do snapshot. |
| `POST /insights/{id}/seen` | Visto; idempotente (a primeira data fica). |
| `POST /insights/{id}/dismiss` | `ACTIVE` → `DISMISSED`; repetir não muda nada; `RESOLVED`/`EXPIRED` → `409 INSIGHT_NOT_ACTIVE`. |
| `PUT /insights/{id}/feedback` `{value}` | `USEFUL`, `NOT_USEFUL` ou `null` (remove); vale o último; repetir não muda nada. |
| `GET /insights/highlights` | Home: `attention` (até 2 Importantes), `changes` (até 3 linhas do "O que mudou?") e `importantCount` (badge do `M-More`). |
| `GET /insights/related?transactionId=` ou `?dimension=` | Até 3 ativos: da transação (a compra atípica dela, a alta da categoria dela no mesmo mês ou o comerciante recorrente dela) ou da dimensão do Health. |
| `GET /insights/status` | Freshness (ADR-0022 §8). |

- Autorização do membro antes de validar; Workspace alheio e inexistente respondem igual (`404 WORKSPACE_NOT_FOUND`).
  Insight de outro Workspace = inexistente (`404 INSIGHT_NOT_FOUND`); transação de outro Workspace em `related` =
  inexistente (`404 TRANSACTION_NOT_FOUND`). Todas as rotas na suíte de isolamento.
- Validação: `400 VALIDATION_FAILED` com `importance`/`type`/`state`/`dimension`/`transactionId`/`value` `INVALID`,
  `page`/`pageSize` `OUT_OF_RANGE`; `related` exige exatamente um de `transactionId` e `dimension`.
- `operationId` explícito em todas as rotas novas (não renumera os existentes, ADR-0010).

### 2. Estados e freshness

- `state` da lista e dos destaques: `NO_DATA` (Workspace sem lançamentos), `INSUFFICIENT_HISTORY` (nenhum insight
  ativo e menos de 2 meses antes do período avaliado: os comparativos ainda não podem disparar) ou `READY` (inclui a
  lista vazia, que é o estado vazio comum).
- `freshness` (`UP_TO_DATE`/`UPDATING`/`NOT_BUILT`) e `algorithmVersion` em toda resposta; em `UPDATING` a resposta traz
  o que já está gravado. A leitura nunca gera insight; a freshness pode marcar o Workspace (ADR-0022 §8).

### 3. Ações do usuário

- Idempotentes pelo estado, sem `Idempotency-Key` (como `post`/`cancel`/`reverse` das transações).
- Bloqueiam a linha do Workspace em `insights_workspaces` (o mesmo lock da geração): uma geração concorrente nunca
  sobrescreve visto, dispensa ou feedback com uma leitura velha. Não disparam nova geração; o efeito de dispensa e de
  "não útil" na relevância vale na próxima geração (ADR-0021 §3/§8).
- "Visto" só pelo `POST`; o `GET` do detalhe não escreve (prefetch, retry ou crawler não marcam nada).

### 4. Formato

- Convenções dos ADRs 0015/0019: dinheiro em `MoneyDto` (casas da moeda), números das evidências como string decimal
  com a unidade (`kind`: `MONEY`, `PERCENT`, `COUNT`, `MONTHS`, `POINTS`, `RATIO`), enums como string.
- Item: `id`, `detector`, `type` e `typeLabel` ("Gastos", "Receitas", "Saúde"), `importance`, `status`, `title`,
  `support`, `subjectLabel` (meta do card; sem ele, o rótulo do tipo), `period {month, partial}` (`month` nulo nos sem
  período), `createdAt`, `updatedAt`, `seen`, `confidence`, `feedback`, `primaryAction`.
- Origens dos dados: `OverviewReadModel.dataSources` (ADR-0019 §5) no intervalo coberto pelas fontes do insight,
  lido na requisição.
- Relacionados no detalhe: ativos do mesmo período (ou sem período) com categoria, comerciante ou fonte em comum
  (cobre D2 ↔ D1 pela categoria da maior alta), na ordem da lista.

### 5. "O que mudou?" e rótulo do card (adendo ao ADR-0021)

- "O que mudou?" = até 3 insights **ativos de mudança** (D1, D2, D3, D5, D6), na ordem da lista, Importantes ou não.
  Sem nenhum, o bloco mostra o estado vazio. Respeita o piso de impacto e a dispensa; cada linha leva ao detalhe.
- Os detectores passam a gravar no conteúdo `subjectLabel` e, nos de mudança, `changeLine {label, value, detail}`,
  com os mesmos números do insight (ex.: "Alimentação fora" · "R$ 1.284,00 até 22 set" · "+32% vs média de abr–ago").
  Não muda detecção, limiar, pontuação nem frase: continua `insights-v1`. Insights gravados antes ganham os campos na
  próxima geração (o job diário do Health regenera todos), com `revision + 1`.

### 6. Fronteiras (adendo ao ADR-0022 §1)

- `insights` passa a ler também `overview.application.port` e `overview.domain` (origens dos dados) e o
  `TransactionRepository` de `transactions.domain` (para `related?transactionId`). Continua proibido usar casos de
  uso, serviços de aplicação ou adapters de outros módulos (ArchUnit).

## Alternatives

- **"O que mudou?" pela decomposição do ADR-0015:** sempre teria conteúdo, mas mostraria variações triviais,
  ignoraria nova receita e assinatura e duplicaria o "Por que mudou?" da Visão geral. **Híbrido:** duas regras no
  mesmo bloco.
- **Rótulos montados na leitura:** sem regeneração, mas a fonte de receita (D5/D6) não tem nome guardado e o texto
  sairia de fora dos detectores.
- **Visto no GET do detalhe:** uma chamada a menos, mas uma leitura passaria a escrever.
- **Sem origens dos dados:** fronteira inalterada, mas perde a linha "25 transações importadas · OFX" do layout.
- **Transições com `Idempotency-Key`:** desnecessário para mudanças de estado idempotentes por natureza.

## Consequences

- A Home faz três chamadas independentes (Visão geral, Health, destaques de Insights); cada bloco tem seus estados.
  O "Home API composta" do M3 continua aberto.
- O link "Ver as N transações" conta só as contas incluídas no total (a projeção); a lista de transações não tem
  esse filtro, então o número diverge só quando há conta de despesa fora do total. Documentado.
- "Insights limitados" no Free (05.4.1) continua fora; a ordem fixa permite o corte na leitura depois.

## Security / Privacy

- Leitura e escrita só no Workspace do membro, com a mesma resposta para alheio e inexistente; isolamento testado
  (Workspace alheio, transação alheia e insight alheio).
- Logs das ações só com ids e o nome da ação; nenhum texto, valor, categoria ou comerciante.

## Financial Integrity

- A API não calcula: devolve o que a geração determinística e versionada gravou; estimativa, mês em andamento e
  confiança sempre presentes. As linhas do "O que mudou?" são os números do próprio insight.

## Operational Impact

- Consultas por índice `(workspace_id, status, algorithm_version)`; destaques e relacionados leem os ativos do
  Workspace (dezenas) e filtram em memória. Uma consulta de origens por detalhe.
- Snapshot do OpenAPI e contrato manual do frontend (`schema.d.ts`) atualizados (ADR-0010).

## Related Specs

- `design/specs/02-components.md` (Insight Card, Insight Detail), `03-screens.md` (HOME, INSIGHTS, `D-TxnDetail`),
  `05-states.md`, layouts `D-Insights`, `D-InsightDetail`, `M-Insights`, `M-InsightDetail`, `D-Home`, `M-Home`, `M-More`
- `docs/adr/ADR-0010`, `ADR-0015`, `ADR-0019` (§5), `ADR-0021`, `ADR-0022`

## Date

2026-10-17
