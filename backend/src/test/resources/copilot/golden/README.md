# Golden set do Copilot (`golden-v1`)

Casos versionados do Financial Copilot (ADR-0042 §1; ADR-0039 §11). Um arquivo JSON por grupo, validado por
[`golden-case.schema.json`](golden-case.schema.json). **Dados sintéticos apenas**: o Workspace é o de
`design/specs/09-dados-de-exemplo.md` (Marina, hoje = terça, 22/09/2026); nenhum dado real, nenhum segredo.

| Arquivo | Grupo | Casos | O que cobre |
|---|---|---|---|
| `intents.json` | `intents` | 15 | as 10 intenções com dados, `OUT_OF_SCOPE` e `UNSUPPORTED_ACTION` (com e sem contexto de entrada) |
| `sample-data.json` | `09-dados-de-exemplo` | 10 | fatura de R$ 3.412,80; fatores que somam R$ 520,60; parcelamento do notebook; delivery; reserva; Lisboa 700/780/900 |
| `adversarial.json` | `adversarial` | 25 | injeção em comerciante, vazamento do prompt, transferir/pagar/gravar/apagar, número inventado (regenera, modo seguro), dígito solto, número por extenso, ref e pseudônimo desconhecidos, alvo de outro Workspace, bloco fechado pela pergunta, saídas fora do esquema |
| `insufficient-data.json` | `insufficient-data` | 23 | dados insuficientes por intenção, falhas de tool, alvo que some, provedor fora ou recusando, modo seguro, confiança baixa |
| `continuity.json` | `continuity` | 7 | até três perguntas anteriores com a intenção gravada |

## Como o golden set roda

- **CI** (`CopilotGoldenSetTest`, em `./mvnw test` e portanto no `verify`): cada caso passa pelo `AskCopilot` e pelo
  `CopilotPipeline` reais (admissão, catálogo, tools, contexto, prompts versionados, claim validation, renderização,
  ledger). Só o que está além das portas é fixture: o catálogo, o contexto de entrada, o que as tools devolvem e o
  provedor de IA, que devolve o `scripted` do caso (sem rede, sem chave). Um caso que falha derruba o build.
- **Modelo real** (`./mvnw -Pcopilot-eval test`, `CopilotRealEvaluation`): o mesmo golden set com o adapter Anthropic;
  `scripted` é ignorado. Veja `docs/runbooks/copilot-evaluation.md`.

## Formato de um caso

```jsonc
{
  "id": "invoice-aurora-current-3412-80",      // único em todo o golden set; é o nome do caso no teste e no relatório
  "group": "09-dados-de-exemplo",              // igual ao grupo do arquivo
  "note": "por que o caso existe",             // opcional; nunca vai ao relatório da avaliação real
  "question": "...",
  "previousQuestions": [{"question": "...", "intent": "CATEGORY"}],   // opcional, até 3; intent opcional
  "context": {"kind": "INVOICE", "cardId": "<uuid>", "month": "2026-10"},  // contexto de entrada, opcional
  "fixtures": { "catalog": {...}, "entry": {...}, "tool": {...} },
  "scripted": { "intent": {...}, "answers": [ {...}, {...} ] },
  "expect": { ... }
}
```

### `fixtures`

- `catalog` (herda `shared.catalog` do arquivo): `currency`, `categories` (código → nome) e `targets` (cartões, objetivos...).
  O pseudônimo do alvo é o **prefixo do tipo + a ordem entre os alvos do mesmo tipo**: o primeiro `CARD` é `cartao_1`, o
  segundo `cartao_2`, o primeiro `GOAL` é `objetivo_1`. `name` é o nome que a pessoa vê; só `descriptor` e
  `description` vão ao modelo de intenção.
- `entry` (obrigatório quando há `context`): o que o resolvedor do contexto devolve. `targets` (tipo → pseudônimo),
  `month`, `screen` (a ação da tela do contexto), `assigns` (itens que ele pseudonimiza ao resolver);
  `verifyFails: true` = alvo de outro Workspace (a admissão responde `CONTEXT_NOT_FOUND`); `resolveFails: true` = o
  alvo sumiu entre a admissão e o pipeline.
- `tool`: o que a tool de **qualquer** intenção com dados devolve (uma intenção errada não escapa da conferência).
  `status` (`DATA` padrão, `NO_DATA`, `NOT_BUILT`, `NEEDS_TARGET`) ou `failure` (`TARGET_NOT_FOUND`, `INVALID_VALUE`);
  `source` (tool e versão dos valores, ex.: `cards-v1`); `values` na ordem de prioridade, que vira `v1`, `v2`...;
  `quality`; `sources`; `actions` (as que o contexto oferece ao modelo); `breakdown` (quadro do sistema, índices
  0-based em `values`). O `label` de um valor não pode ter dígitos fora de um pseudônimo (regra do `ContextValue`).

### `scripted` (só CI)

`intent` é a saída do modelo de intenção (`intents-v1`) e cada item de `answers` a do modelo de resposta
(`answer-v1`), na ordem das chamadas (`ANSWER`, depois `REGENERATE`). Dois formatos especiais:
`{"outcome": "UNAVAILABLE"}` (o provedor falha; também `TIMEOUT`, `REFUSED`, `RATE_LIMITED`, `INVALID_OUTPUT`) e
`{"raw": "texto"}` (saída que não é JSON do esquema). Pedir mais chamadas do que o roteiro tem, ou sobrar roteiro, falha o caso.
O texto do modelo cita valores por `{{v:vN}}` e nunca escreve números.

### `expect`

Tudo é opcional e só é conferido quando presente. Valores formatados são pt-BR, como a tela mostra (`R$ 1.234,56`,
`R$ 311,00 (estimado)`, `agosto de 2027 (simulado)`).

| Campo | O que confere |
|---|---|
| `code`, `intent`, `validation`, `confidence` | resultado do pipeline; `intent: "NONE"` = a pergunta não chegou à classificação |
| `calls` | chamadas ao modelo no ledger, em ordem (`INTENT`, `ANSWER`, `REGENERATE`); `[]` = nenhuma |
| `error` | erro antes do pipeline: `CONTEXT_NOT_FOUND` ou `INVALID_VALUE:campo:motivo` |
| `refs`, `citedValues` | refs em `answer.financialData` (em ordem, inclui os do quadro) e os valores formatados citados |
| `sources`, `kinds` | conjunto das fontes e dos rótulos `FACT`/`ESTIMATED`/`SIMULATED` dos valores citados |
| `notices` | avisos, em ordem (`PARTIAL_PERIOD`, `ESTIMATED`, `SIMULATED`, `SAFE_MODE`, `UPDATING`) |
| `actions`, `breakdown`, `dataUsed` | ações resolvidas, quadro de decomposição e "Dados usados" |
| `textContains`, `textExcludes` | trechos que precisam (ou nunca podem) aparecer no texto e nos passos |
| `modelInputContains`, `modelInputExcludes` | o que vai (ou nunca pode ir) ao modelo, prompt e conteúdo |
| `real` | modelo real: `skip: true` para o que depende de falha roteirizada; `code`/`intent` substituem os do CI |

### Invariantes gerais (todo caso, sem declarar)

Nenhum id interno (UUID) vai ao modelo; todo ref citado existe no contexto; todo número, `R$` ou `%` do texto vem de um
valor do contexto (nunca de um trecho de texto); `SAFE_MODE` e `validation` são coerentes. Valem no CI e na avaliação real.

## Como adicionar ou mudar um caso

1. Escreva o caso no arquivo do grupo (ids únicos, kebab-case). Use só dados sintéticos.
2. `cd backend && ./mvnw -q test -Dtest='*Golden*'` (o schema é validado antes de qualquer caso rodar).
3. Mudou prompt, esquema ou pipeline? Rode também a avaliação real (runbook) e compare com o relatório anterior.
4. Mudar o formato de um caso é mudar o schema e a versão (`golden-v2`), com ADR.
