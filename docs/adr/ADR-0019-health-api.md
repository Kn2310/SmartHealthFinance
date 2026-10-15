# ADR-0019 — API do Financial Health (M6, parte D)

## Status

Accepted

> Decisões aprovadas em 2026-10-15: um `GET /health` completo (resumo, dimensões, fatores e próximos passos) em vez
> de rotas separadas; fontes ("OFX, CSV e manual") lidas na hora por um método novo da porta do Overview; score em
> Análise › Evolução pela chamada a `/health/evolution` no frontend, sem mudar o contrato do Analytics (ADR-0015);
> demais pontos (parâmetro `month`, estados, `RECALCULATING` com o último snapshot, evolução de 1 a 12 meses,
> método a partir das constantes do cálculo, textos prontos + campos estruturados) como propostos.

## Context

O ADR-0017 fixou o modelo `health-v1` e o ADR-0018 o snapshot por eventos. Faltava expor o resultado para a Home
("Como estou?"), `D-Health`/`M-Health`, o drawer `D-HealthMethod` e a coluna de score em `D-AnalysisEvolution`,
nas convenções da API de Analytics (ADR-0015) e com isolamento por Workspace (ADR-0003).

## Decision

### 1. Rotas (todas sob `/api/v1/workspaces/{workspaceId}/health`, só leitura)

| Rota | Conteúdo |
|---|---|
| `GET /health?month=YYYY-MM` | Resumo (score, variação, frase, `estimated`, confiança, cobertura, período/janela, contexto, `algorithmVersion`, freshness) + 6 dimensões (status, nota, motivo, explicação, ponto de atenção, evidências) + fatores + próximos passos |
| `GET /health/evolution?month=YYYY-MM&months=N` | Série mensal até `month` (padrão o corrente), `months` de 1 a 12 (padrão 6): mês, estado, score, variação, `estimated`, `partial`. Sempre N itens; meses sem snapshot em `NO_DATA` |
| `GET /health/method` | Conteúdo do "Como calculamos?" (§4) |
| `GET /health/status` | Freshness (ADR-0018 §7) |

- `month` segue a validação da Análise: `400 VALIDATION_FAILED` com `month` `INVALID` ou `FUTURE`; `months` fora de
  1–12 → `months` `OUT_OF_RANGE`. O `month` da evolução é o mesmo da aba Evolução da Análise, para as séries
  alinharem.
- Autorização do membro antes de validar parâmetros; Workspace alheio e inexistente respondem igual
  (`404 WORKSPACE_NOT_FOUND`). Todas as rotas estão na suíte de isolamento.
- Nenhum cálculo de score na requisição: só o snapshot gravado. A leitura pode marcar o Workspace para recálculo
  (snapshot de um dia anterior, ADR-0018 §5).

### 2. Estados

- `state`: `READY` (há score), `INSUFFICIENT_DATA` (sem score; dimensões disponíveis com motivo) ou `NO_DATA` (sem
  snapshot para o mês: Workspace sem lançamentos, mês anterior ao primeiro lançamento ou além dos 12 meses gravados).
  Em `NO_DATA`, score, confiança, cobertura e contexto vêm nulos e as listas vazias.
- `freshness` em toda resposta: `{state: UP_TO_DATE | RECALCULATING | NOT_BUILT, computedAt, analyticsRefreshedAt}`.
  Em `RECALCULATING` a resposta traz o **último snapshot gravado**; a UI sinaliza a atualização.
- `estimated` (menos de 3 meses fechados) e `period.partial` (mês em andamento) sempre presentes.

### 3. Formato

- Convenções do ADR-0015: dinheiro em `MoneyDto`, percentuais e meses de cobertura como string decimal, enums como
  string.
- Textos (frase, explicações, fatores, próximos passos) vêm **prontos**, dos templates do ADR-0017 §7, com os
  números do snapshot. Campos estruturados acompanham para a UI montar links e gráficos: `dimension`, `status`,
  `reason`, evidências `{code, label, kind, value, from, to, source}` e, nos próximos passos, `kind`,
  `categoryCode`, `month` e `amount`. A rota de cada passo é decidida pela UI a partir do `kind` (só telas
  existentes; sem ✦).
- `context`: `activeAccounts`, `uncategorizedShare` e `sources` (`CSV`, `MANUAL`, `OFX`, ordem alfabética).

### 4. Método ("Como calculamos?")

- Montado das mesmas constantes do `HealthCalculator` (`HealthMethod`): dimensões com nome, peso, se já se aplica,
  o que observa, fórmula, mínimo e curvas (pontos x → nota); score geral e renormalização; janela; fontes
  possíveis; precisão (estimado com menos de 3 meses, níveis de confiança); mês em andamento; arredondamento;
  exclusões (investimentos, seguros, empréstimos complexos, cartões e parcelamentos por enquanto, pendentes,
  transferências, ajustes, contas arquivadas). Um teste garante que pesos e curvas são os do cálculo.

### 5. Fontes

- `OverviewReadModel.dataSources(workspaceId, period)` (aditivo): origens dos lançamentos `POSTED` de contas ativas
  no período — `MANUAL` ou o formato do lote de importação (`CSV`, `OFX`) via `import_records`/`import_batches`.
  Lido na requisição, da janela até a data-base do snapshot; não faz parte do score nem do snapshot.

### 6. Score na Análise › Evolução

- O frontend chama `/health/evolution` com o mesmo `month` e junta pelo mês. O contrato do Analytics não muda e
  continua valendo a regra de que nenhum módulo depende do Health (ArchUnit).

## Alternatives

- **Rotas separadas** (`/summary`, `/dimensions`, `/factors`, `/next-steps`): 3–4 requisições em `D-Health` e risco
  de misturar snapshots de recálculos diferentes.
- **Omitir as fontes**: tiraria uma linha do layout.
- **`score` dentro de `/analytics/evolution`**: uma requisição a menos, mas muda um contrato existente e faz o
  Analytics depender do Health.
- **Só códigos, textos montados no frontend**: duplicaria os templates fora do backend, contra o ADR-0017 §7.

## Consequences

- A Home recebe fatores e próximos passos que não exibe (alguns KB), em troca de uma requisição e um snapshot
  consistente.
- A aba Evolução da Análise faz duas chamadas.
- O método nunca diverge do cálculo; mudar curva ou peso muda o método junto (e a versão, ADR-0017 §5).

## Security / Privacy

- Leitura só do Workspace do membro, com a mesma resposta para alheio e inexistente; isolamento testado.
- Nenhum dado financeiro em logs; métrica `shf.health.query` só com a visão e o estado.

## Financial Integrity

- A API não calcula: devolve o snapshot determinístico e versionado (`algorithmVersion` em toda resposta).
- Estimativa e mês em andamento sempre sinalizados; nenhum número inventado em `NO_DATA`/`INSUFFICIENT_DATA`.

## Operational Impact

- Uma consulta de fontes por `GET /health` (índice por Workspace e data de `transactions`); demais leituras pela
  chave primária de `health_snapshots`.
- Snapshot do OpenAPI e contrato manual do frontend (`schema.d.ts`) atualizados (ADR-0010).

## Related Specs

- `docs/adr/ADR-0017-financial-health-model.md`, `ADR-0018-health-snapshot-by-events.md`, `ADR-0015-analytics-api.md`,
  `ADR-0006-overview-module.md`, `ADR-0010-ci-and-openapi-contract-snapshot.md`
- `design/specs/03-screens.md` (HOME, FINANCIAL HEALTH, `D-AnalysisEvolution`), `05-states.md`

## Date

2026-10-15
