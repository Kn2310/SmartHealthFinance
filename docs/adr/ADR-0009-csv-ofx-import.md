# ADR-0009 — Importação de extratos CSV/OFX (M4)

## Status

Accepted

> Aprovado em 2026-10-08. As decisões 14 (Outbox + RabbitMQ já no M4), 3 (parse síncrono no upload), 6 (CSV
> canônico com autodetecção) e a entrega em PRs por fatia foram escolhidas antes da redação; o restante foi
> aprovado na revisão do ADR. Fatias: 1) domínio + parsers; 2) Outbox, batches, dedupe, worker e API; 3) UI.

## Context

O M4 traz a primeira entrada de dados em lote. As specs definem:

- `import_batches` e `import_records`, com rastreabilidade de toda importação (05.4);
- deduplicação por external ID, com fallback para chave composta (05.4);
- "Multipart + processamento assíncrono + 202 + status endpoint" e `Idempotency-Key` em imports (05.6);
- imports assíncronos via RabbitMQ, Transactional Outbox obrigatório, at-least-once, consumers idempotentes,
  DLQ e retry com backoff + jitter (05.7, 05.13 §14);
- fluxo de UX *Choose file → Validate → Preview → Confirm → Async processing → Progress/status → Results*
  (`specs/03-ux/core-user-flows.md`);
- estado "Importado" distinto de "Manual" (05.13 §5, `design/specs/01-foundations.md`);
- testes de ingestão: CSV/OFX, deduplicação, arquivos malformados e precisão (05.10);
- upload security e nenhum dado financeiro em logs (05.13 §16).

Não definem: o layout de CSV aceito, como o OFX é lido, o que acontece com o arquivo bruto, quando o parse ocorre,
como o sinal vira tipo de transação, a composição exata da chave de dedupe, nem a infraestrutura de Outbox — que
ainda não existe (ADR-0003/0004/0005 adiaram eventos por isso).

## Decision

### Módulo e direção

1. **Novo módulo `ingestion`** (lista de módulos da 05.13 §4), downstream do Financial Core:
   `identity → accounts → transactions → ingestion`.
   - Ingestion cria transações apenas por um caso de uso de Transactions (`RecordImportedTransactions`), nunca
     escrevendo na tabela `transactions`. Regras de transação continuam num só lugar (ADR-0005).
   - Transactions, Accounts, Identity e Overview nunca dependem de Ingestion (ArchUnit).
   - Parsers são adapters (`ingestion.infrastructure.parser`) atrás da porta `StatementParser`
     (`ingestion.application.port`). O domínio só conhece `StatementLine`, sem saber de formatos.

### Fluxo e estados

2. **Fluxo:** upload → preview → confirmação → processamento assíncrono → resultado.

   ```text
   PREVIEW ──confirm──▶ CONFIRMED ──worker──▶ PROCESSING ──▶ COMPLETED
      │                                            └───────▶ FAILED
      ├──cancel──▶ CANCELLED
      └──24h─────▶ EXPIRED
   ```

3. **Parse síncrono no upload.** O arquivo (≤ 10 MB, limite já configurado) é decodificado, validado e parseado
   na requisição; as linhas viram `import_records` e a resposta é `201` com o resumo do preview.
   - **O arquivo bruto não é armazenado**: só o SHA-256 (detecção de reenvio do mesmo arquivo) e metadados
     (formato, tamanho, nome original truncado). Minimização (05.12) e nada de object storage no MVP.
   - O `POST .../confirm` responde `202` e o trabalho pesado (criação das transações) é assíncrono, como pede
     a 05.6. O status é consultado por `GET`.
   - Limite de 5.000 linhas por arquivo, para que o processamento caiba numa transação de banco.

4. **Conta de destino escolhida pelo usuário** no upload, ativa e do mesmo Workspace. Não criamos contas a
   partir do arquivo nem inferimos a conta pelo `ACCTID` do OFX (número de conta não é persistido).

### Formatos

5. **OFX: parser próprio**, sem dependência nova (as bibliotecas Java de OFX estão sem manutenção).
   - Lê OFX 1.x (SGML, folhas sem fechamento) e 2.x (XML) com o mesmo tokenizador de tags. Não usa parser XML,
     então não há superfície de XXE/entity expansion.
   - Usa `STMTTRN` dentro de um único `STMTRS` de conta bancária. Mais de um extrato no arquivo →
     `MULTIPLE_STATEMENTS`. Extrato de cartão (`CCSTMTRS`) → `UNSUPPORTED_STATEMENT_TYPE` (Cards é o M8).
   - `CURDEF` diferente de BRL → `UNSUPPORTED_CURRENCY` (MVP é BRL, ADR-0003).
   - Campos: `DTPOSTED` (só a data `YYYYMMDD`, como o banco declarou, sem conversão de fuso), `TRNAMT`, `FITID`
     (external ID), `NAME`/`MEMO` (descrição). `TRNTYPE` é ignorado: o sinal de `TRNAMT` é a fonte da verdade
     (especificação OFX), e há bancos que emitem `TRNTYPE` genérico.
   - Charset: BOM → UTF-8 válido → charset declarado (`CHARSET:1252`, declaração XML) → Windows-1252. UTF-8
     válido vence a declaração porque há bancos que declaram 1252 e enviam UTF-8.
   - `CURDEF` ausente → `MALFORMED_OFX` (não presumimos a moeda).

6. **CSV: modelo canônico com autodetecção.**
   - Cabeçalho obrigatório. Colunas por nome (sem diferenciar maiúsculas/acentos), em qualquer ordem:
     `data` (`date`), `descricao` (`descrição`, `description`, `historico`, `histórico`), `valor` (`amount`)
     e, opcional, `id` (`identificador`, `fitid`). Colunas extras são ignoradas. Coluna obrigatória ausente →
     `MISSING_COLUMN`; repetida → `DUPLICATE_COLUMN`.
   - Separador `;`, `,` ou tab, detectado no cabeçalho (fora de aspas). Aspas RFC 4180.
   - Datas `dd/MM/yyyy` ou `yyyy-MM-dd`.
   - Valor com sinal (`-` = saída). Aceita `R$`, espaços e separador de milhar. Decimal `,` ou `.` decidido
     **por arquivo**: valores inequívocos (dois separadores, ou um separador seguido de 1–2 dígitos) definem o
     estilo; estilos conflitantes → `INCONSISTENT_DECIMAL_SEPARATOR`; valor ambíguo (`1.234` / `1,234`) sem
     nenhum valor que desambigue → a linha é rejeitada com `AMBIGUOUS_AMOUNT`. **Nunca arredondamos**: mais de
     2 casas → `TOO_MANY_DECIMALS`.
   - UTF-8 (com ou sem BOM); se não for UTF-8 válido, Windows-1252 (padrão de exportação de bancos brasileiros).
   - A UI oferece um modelo para download.

7. **Erros por linha não derrubam o arquivo.** Cada linha inválida vira um `import_record` `INVALID` com um código
   estável (`INVALID_DATE`, `INVALID_AMOUNT`, `ZERO_AMOUNT`, `AMBIGUOUS_AMOUNT`, `TOO_MANY_DECIMALS`,
   `DESCRIPTION_REQUIRED`, `COLUMN_COUNT_MISMATCH`, ...). Erros do arquivo inteiro (`EMPTY_FILE`,
   `NO_TRANSACTIONS`, `TOO_MANY_LINES`, `MISSING_COLUMN`, `MALFORMED_OFX`, ...) → `422 IMPORT_FILE_REJECTED`,
   sem criar batch. Os códigos nunca carregam o valor bruto da linha.

8. **Descrição normalizada, não rejeitada:** espaços colapsados, caracteres de controle removidos e corte em 200
   caracteres (limite de `TransactionDescription`). No OFX, `NAME` e `MEMO` distintos são unidos com ` · `.
   Descrição vazia → `DESCRIPTION_REQUIRED`.

### Semântica financeira

9. **Sinal → tipo:** valor positivo → `INCOME`, negativo → `EXPENSE`, valor absoluto em `amount` (05.4).
   Zero → linha inválida (`ZERO_AMOUNT`).
   - Não inferimos `TRANSFER` nem `REFUND` no M4. Uma transferência entre contas próprias importada dos dois
     extratos aparece como despesa + receita. Casar as pernas (ADR-0005, Alternatives) fica para depois do M5,
     com regra explícita e revisável pelo usuário.
10. **Importadas nascem `POSTED` com `source = IMPORT`** (extrato é movimento efetivado). A migration amplia o
    `check` de `transactions.source` (prevista no ADR-0005 §12). Correção segue o ADR-0005: estorno.
11. **Deduplicação por conta**, em tabela própria `imported_transaction_keys`
    `unique (workspace_id, account_id, dedupe_key)` → `transaction_id`:
    - com external ID: `sha256("v1|ext|" + externalId + "|" + data + "|" + valorComSinal)`. Data e valor entram
      na chave porque há bancos que reutilizam `FITID` para lançamentos distintos;
    - sem external ID: `sha256("v1|fp|" + data + "|" + valorComSinal + "|" + descriçãoNormalizada + "|" + n)`,
      onde `n` é a ordem da ocorrência daquela tupla no arquivo. Dois cafés iguais no mesmo dia continuam
      sendo dois lançamentos, e um extrato sobreposto ao anterior não duplica nenhum;
    - duplicata dentro do próprio arquivo ou já importada → record `DUPLICATE`, apontando para a transação
      existente quando houver;
    - o preview já marca as duplicatas conhecidas; a decisão final é no processamento, com
      `INSERT ... ON CONFLICT DO NOTHING` (mesmo padrão do ADR-0005).
    - Não deduplicamos contra lançamentos **manuais**: exigiria casamento aproximado. Fica documentado como
      limitação.
12. **Processamento tudo-ou-nada** numa transação de banco: transações, chaves de dedupe, status dos records,
    status do batch e evento de saída. Uma falha deixa o batch como estava e a mensagem é reentregue.
    Conta arquivada entre preview e processamento → `FAILED` com `ACCOUNT_ARCHIVED` (não retentável).

### Idempotência e assíncrono

13. **`Idempotency-Key` obrigatório no upload**, por Workspace, com SHA-256 de (conta + hash do arquivo) como
    request hash; mesma chave + mesmo conteúdo → replay do batch, outro conteúdo → `422 IDEMPOTENCY_KEY_REUSED`.
    O `confirm` e o `cancel` são idempotentes pela máquina de estados (repetir não muda nada).
14. **Transactional Outbox genérica em `shared`** (primeira do sistema; destrava os eventos `transaction.*`):
    - tabela `outbox_events` com o envelope da 05.7, gravada na mesma transação do fato;
    - relay agendado lê pendentes com `FOR UPDATE SKIP LOCKED`, publica no exchange topic `shf.events` com
      publisher confirms e marca `published_at`. At-least-once, sem ordering global;
    - inbox `processed_events (consumer, event_id)` para consumers críticos.
15. **Import Worker** dentro do monólito (mesmo deploy, 05.13 §3), consumindo `import.confirmed` da fila
    `shf.ingestion.import`:
    - idempotente por inbox + máquina de estados (batch fora de `CONFIRMED`/`PROCESSING` → ack sem efeito);
    - retry com backoff exponencial + jitter para falhas transitórias; não retentáveis vão direto para a DLQ
      `shf.ingestion.import.dlq`;
    - ao concluir, grava `import.completed` na outbox (o M5 Analytics vai consumi-lo). Eventos
      `transaction.*` por lançamento ficam para quando houver consumidor, para não gerar milhares de mensagens
      sem uso.
16. **Expiração:** previews não confirmados em 24h viram `EXPIRED` e seus records são apagados por job
    agendado. Um preview cancelado perde os records na hora. Batches concluídos mantêm os records como lineage
    (05.4: rastreabilidade). Esgotadas as tentativas, o batch vira `FAILED` (`PROCESSING_ERROR`) e a mensagem
    fica na DLQ para replay (`docs/runbooks/import-dlq.md`).

### API (fatia 2)

17. Rotas aninhadas por Workspace (ADR-0004/0005):
    - `POST /api/v1/workspaces/{workspaceId}/imports` — multipart (`file`, `accountId`) + `Idempotency-Key` → `201`;
    - `GET .../imports` (histórico) e `GET .../imports/{importId}` (status, contagens, período) — polling;
    - `GET .../imports/{importId}/records` — linhas do preview/resultado, paginadas por offset;
    - `POST .../imports/{importId}/confirm` → `202`; `POST .../imports/{importId}/cancel` → `200`.
    - Batch de outro Workspace ou inexistente → `404 IMPORT_NOT_FOUND` (regra do 404, ADR-0003).

### UI (fatia 3)

18. Página `/import` (ação rápida "Importar extrato" da Home e da conta): conta + dropzone → preview (resumo,
    período, válidas/inválidas/duplicadas, tabela com motivos) → confirmar → progresso → resultado. Badge
    "Importado" (ícone de download) nas transações com `source = IMPORT`. Estados loading/empty/error/partial.

## Alternatives

- **Async in-process sem RabbitMQ:** rejeitada (decisão aprovada). Divergiria da 05.7 e adiaria a Outbox de novo.
- **Parse assíncrono (upload `202`):** rejeitada (decisão aprovada). Exigiria guardar o arquivo bruto; com 10 MB o
  parse síncrono cabe no p95.
- **Mapeamento de colunas na UI:** adiado (decisão aprovada). Pode vir depois sem mudar o modelo de records.
- **Biblioteca OFX (ofx4j e afins):** rejeitada. Sem manutenção, puxa parser XML e não resolve os desvios dos
  bancos brasileiros (vírgula decimal, charset).
- **Dedupe só por `FITID`:** rejeitada. Há bancos que reutilizam `FITID`; incluir data e valor evita perder
  lançamento legítimo, ao custo de não reconhecer um lançamento que o banco tenha alterado (raro em extrato).
- **Chave de dedupe na tabela `transactions`:** rejeitada pelo mesmo motivo do ADR-0005: mistura transporte com o
  fato financeiro.
- **Processamento por linha (commits parciais):** rejeitado. Batch parcialmente aplicado é difícil de explicar e
  de reprocessar; com 5.000 linhas uma transação basta.
- **Inferir transferências/reembolsos:** adiado. É regra financeira nova e exige revisão do usuário.

## Consequences

- Positivas:
  - reimportar o mesmo extrato (ou um sobreposto) não duplica lançamentos;
  - nada do arquivo bruto fica guardado;
  - a Outbox e o worker ficam prontos para Analytics/Health/Forecast/Insights;
  - toda transação importada tem lineage até o batch e a linha de origem.
- Negativas:
  - transferências entre contas próprias aparecem como despesa + receita até existir o casamento;
  - lançamento manual + importado do mesmo movimento ficam duplicados;
  - CSV fora do modelo canônico precisa ser ajustado pelo usuário;
  - o monólito passa a depender do RabbitMQ para concluir importações (não para a API em geral).

## Security / Privacy

- Upload: extensão (`.csv`/`.ofx`) e conteúdo precisam concordar; limite de tamanho e de linhas; arquivo
  nunca gravado em disco nem servido de volta.
- Descrições importadas são conteúdo não confiável (05.8): nunca interpretadas, só exibidas/escapadas.
- Logs com apenas `importId`, `workspaceId`, contagens e códigos de erro — nunca linhas, valores, descrições,
  nome do arquivo ou números de conta (há teste).
- Previews expiram em 24h; `ACCTID`/agência do OFX não são persistidos.
- Isolamento: FKs compostas com `workspace_id` em `import_batches`, `import_records` e
  `imported_transaction_keys`, como no ADR-0005.

## Financial Integrity

- `BigDecimal` do texto até o `NUMERIC(19,4)`; sem float, sem arredondamento; precisão excedente é erro.
- Sinal → tipo determinístico e documentado; valor sempre positivo na transação.
- Dedupe garantido por `unique` no banco, não só na aplicação.
- Processamento tudo-ou-nada; reprocessar é idempotente.
- Testes: casos reais de bancos (vírgula decimal, Windows-1252, SGML sem fechamento), arquivos malformados,
  precisão, dedupe entre arquivos sobrepostos, concorrência de dois confirms e reentrega da mensagem.

## Operational Impact

- Migrations aditivas: `outbox_events`, `processed_events`, `import_batches`, `import_records`,
  `imported_transaction_keys` e ampliação do `check` de `transactions.source`.
- Declaração de exchange/filas/DLQ na inicialização; readiness já inclui `rabbit`.
- Métricas: batches por status, duração do processamento, linhas por resultado, idade do evento mais antigo
  não publicado na outbox e tamanho da DLQ (SLO "Import ≥99% dentro do target", 05.9).
- Sem nova dependência Maven (`spring-boot-starter-amqp` e Testcontainers RabbitMQ já estão no `pom.xml`).

## Related Specs

- `specs/03-ux/core-user-flows.md`
- `specs/05-system-design/05.4-data-architecture.md`
- `specs/05-system-design/05.6-api-integration.md`
- `specs/05-system-design/05.7-event-async.md`
- `specs/05-system-design/05.10-testing.md`
- `specs/05-system-design/05.12-lgpd-privacy-data-governance.md`
- `specs/05-system-design/05.13-consolidated-system-design.md`
- `design/specs/01-foundations.md`, `design/specs/03-screens.md`
- `docs/adr/ADR-0003-personal-workspace-and-membership-authorization.md`
- `docs/adr/ADR-0005-transactions-module.md`

## Date

2026-10-08
