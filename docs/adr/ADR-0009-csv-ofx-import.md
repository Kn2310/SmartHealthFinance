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
   - **Adendo (2026-10-14, aprovado): "marcar como reembolso".** Um estorno do extrato continua nascendo `INCOME`
     (nenhuma inferência automática), mas o usuário pode corrigi-lo no detalhe da transação:
     `POST /api/v1/workspaces/{id}/transactions/{transactionId}/mark-as-refund` com corpo opcional
     `{refundOfTransactionId}`.
     - Na mesma transação de banco, a receita é **estornada** (`POSTED → REVERSED`) e substituída por um **`REFUND`
       `POSTED`** com a mesma conta, valor, data, descrição e origem (`IMPORT`). O tipo da receita não muda: a
       imutabilidade do ADR-0005 §8 é preservada e o fato do extrato fica no histórico.
     - Vínculo nos dois sentidos: `reclassified_from_transaction_id` no reembolso (imutável) e
       `reclassified_as_transaction_id` na receita estornada. FKs compostas com `workspace_id`, `check` de tipo/status e
       índices únicos parciais: no máximo um substituto por receita, mesmo com bug na aplicação (`V13`).
     - Vínculo **opcional** com a despesa original, com as regras do ADR-0005 §6 (EXPENSE `POSTED`, soma ≤ original,
       lock no original). Sem vínculo, continua sendo devolução, não renda (ADR-0006 §9). A categoria não é herdada
       (questão em aberto do ADR-0013); o reembolso passa pelas regras automáticas como qualquer lançamento.
     - Só **receita importada e `POSTED`** é elegível (`400 transactionId NOT_RECLASSIFIABLE`; receita já estornada
       à mão → `409 TRANSACTION_STATUS_CONFLICT`). Lançamentos manuais escolhem `REFUND` na criação.
     - Idempotente: repetir com o mesmo vínculo devolve o mesmo reembolso (`200`); outro vínculo →
       `400 refundOfTransactionId ALREADY_RECLASSIFIED`. A receita fica bloqueada (`FOR UPDATE`) durante a operação.
     - Exige a conta ativa (`409 ACCOUNT_ARCHIVED`), como todo lançamento novo.
     - Efeitos: o saldo não muda (os dois creditam a mesma conta); no fluxo de caixa a entrada passa de `income` para
       `refunds`, e o resultado não muda. Eventos `transaction.reversed` (receita) e `transaction.created`
       (reembolso): a projeção de Analytics recalcula o mês sem nenhuma mudança (ADR-0014); o rebuild também, porque
       relê o estado atual. A chave de deduplicação continua apontando para a receita estornada, então reimportar o
       extrato não recria o estorno.
     - Sem desfazer nesta versão: estornar o reembolso o tira do fluxo, mas não devolve a receita.
     - Alternativas consideradas: reclassificar o tipo na mesma linha (exceção à imutabilidade), regra automática por
       palavra-chave (falsos positivos como "Estorno de tarifa") e manter como estava (receitas e o Health inflados).
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
    - O batch fica na URL (`/import?id=`): recarregar retoma o fluxo; o processamento é acompanhado por polling
      do `GET` a cada 1,5 s até um status final.
    - BFF (ADR-0007): `GET /api/bff/accounts`, `POST /api/bff/imports`, `GET /api/bff/imports/{id}[/records]`,
      `POST /api/bff/imports/{id}/confirm|cancel`. Rotas que alteram dados exigem `Origin` do app (ADR-0007 §6);
      ids e filtros são validados antes de chegar à API; só códigos estáveis (inclusive o motivo da recusa do
      arquivo) chegam ao browser.
    - Para o selo, `recentTransactions` do Overview passou a expor `source` (campo aditivo; ADR-0006).
    - A UI oferece um modelo CSV (`/modelo-extrato.csv`, UTF-8 com BOM para o Excel).

### Faturas de cartão (adendo de 2026-10-23, M8 Sessão 6.2 parte D)

> Decisões aprovadas em 2026-10-23:
> - **Ingestion → Cards**: o processamento chama um caso de uso de Cards na mesma transação tudo-ou-nada;
> - **CSV canônico com o sinal de fatura** (positivo = compra) e coluna opcional `parcela`;
> - **parcelas reconstruídas** quando não há parcelamento compatível, com o **início do acompanhamento** do cartão
>   (ADR-0025 §15);
> - **a conta é a fonte do pagamento**: o crédito de pagamento no arquivo do cartão não vira transação, e o débito da
>   conta é conciliado por uma ação explícita (ADR-0025 §7, adendo).

19. **Destino cartão.**
    - O upload recebe `accountId` **ou** `cardId`, nunca os dois e nunca nenhum
      (`400 VALIDATION_FAILED destination EXACTLY_ONE`).
    - O cartão precisa estar ativo e no mesmo Workspace: `404 CARD_NOT_FOUND` ou `409 CARD_ARCHIVED`.
    - O hash da `Idempotency-Key` com cartão é `sha256("v1|card|" + cardId + "|" + hashDoArquivo)`. O formato da conta
      não muda.
    - Um cartão arquivado entre o preview e o processamento leva a `FAILED` `CARD_ARCHIVED`, não retentável, como a
      conta (§12).
20. **Direção dos módulos:** `identity → accounts → transactions → cards → ingestion`.
    - Para um batch de cartão, o `ProcessImport` chama `RecordImportedCardTransactions` (Cards, `MANDATORY`).
    - Esse caso de uso lança as transações pelo caso de uso de Transactions (`source = IMPORT`, `card_id`) e gera as
      parcelas, tudo na transação de banco do batch.
    - ArchUnit: Ingestion pode usar casos de uso e DTOs de Cards, e Cards nunca depende de Ingestion.
21. **OFX de cartão.**
    - `CCSTMTRS` só é aceito com destino cartão, e `STMTRS` só com conta. O tipo trocado leva a
      `422 IMPORT_FILE_REJECTED` `STATEMENT_TYPE_MISMATCH`.
    - As regras de extrato único e BRL (§5) continuam valendo.
    - O sinal segue o OFX: `TRNAMT` negativo é compra e positivo é crédito.
    - `TRNTYPE` é lido só para reconhecer `PAYMENT` (item 23).
    - **Dados da fatura, só quando explícitos:** se o arquivo trouxer o agregado `CCCLOSING` com `DTPMTDUE` e/ou
      `MINPMTDUE`, a fatura do cartão que vence naquele mês recebe:
      - o pagamento mínimo, mostrado como "Informado no arquivo";
      - o vencimento real, com `dates_source = IMPORTED`.
    - O fechamento real (`DTCLOSE`) **não** é aplicado nesta versão, porque exigiria reatribuir as parcelas.
    - O CSV não tem campo para nenhum desses dados.
22. **CSV de fatura:** o mesmo modelo canônico do §6, com a mesma autodetecção, separador e regras de valor.
    - **Sinal invertido em relação ao extrato de conta:** positivo é compra e negativo é crédito, como nas exportações
      de fatura.
    - **Coluna opcional `parcela`** (`parcelas`, `installment`), no formato `k/n` ou `k de n`. Um valor fora do padrão
      leva a `INVALID_INSTALLMENT`.
    - **Convenção interna única:** o parser normaliza toda linha de cartão para negativo = compra, qualquer que seja o
      formato. Records e chaves de dedupe têm o mesmo sinal no OFX e no CSV.
    - A UI oferece `/modelo-fatura.csv`.
23. **Classificação das linhas de cartão** (domínio do Ingestion, determinística, visível no preview):
    - **Crédito de pagamento:** crédito com `TRNTYPE` `PAYMENT`, ou cuja descrição normalizada comece com
      `PAGAMENTO`, `PAGTO`, `PGTO` ou `PAG FATURA`.
      - Vira o record **`SKIPPED`** com o motivo `CARD_PAYMENT` ("Pagamento: registre pela conta"). Nenhuma transação
        é criada.
    - **Outro crédito:** `REFUND` no cartão, sem vínculo, que entra na fatura do ciclo da data (ADR-0025 §8).
      Vincular depois a uma compra fica para depois do M8.
    - **Débito com marcador de parcela:** parcela (itens 24–26).
    - **Outro débito:** compra à vista. Anuidade, IOF e juros entram aqui, porque `ADJUSTMENT` no cartão continua fora
      do MVP.
24. **Detecção de parcela.**
    - A coluna `parcela` tem prioridade.
    - Na falta dela, procura-se na descrição normalizada:
      - `PARC` ou `PARCELA` seguido de `k/n` ou `k DE n`;
      - um `k/n` isolado no **fim** da descrição.
    - Vale só com 1 ≤ k ≤ n e 2 ≤ n ≤ 48. Fora disso a linha é uma compra comum.
    - A descrição da compra é a da linha sem o marcador.
    - Risco aceito: uma data no fim da descrição ("… 05/12") pode ser lida como parcela. O preview mostra
      "Parcela k de n" em cada linha para o usuário conferir antes de confirmar.
25. **Data da compra de uma parcela.**
    - A **fatura do arquivo** é a do ciclo da maior data entre as linhas.
    - Se a parcela k, contada a partir da data da linha, cai nessa fatura, a data da linha é a data da compra.
    - Caso contrário, a linha traz a data de lançamento, e a compra é essa data menos k − 1 meses, com o dia limitado
      ao fim do mês.
26. **Conciliação ou reconstrução** (Cards, no processamento).
    - **Busca de compatível:** compra `POSTED` do mesmo cartão em n parcelas, com a parcela k na mesma fatura,
      |valor da linha − parcela k| < n centavos e a parcela k ainda não confirmada por arquivo.
    - **Desempate:** data da compra mais antiga, depois o id.
    - **Encontrou:** record **`RECONCILED`** apontando para a compra existente, manual ou importada. Nada é lançado, e
      a parcela k passa a constar como confirmada por arquivo (`card_installment_confirmations`).
    - **Não encontrou:** a compra é reconstruída em n parcelas, com **total = valor da linha × n** na data da compra.
      - As parcelas são todas iguais (`InstallmentSplitter`).
      - A parcela k fica confirmada.
      - Na conciliação dos meses seguintes, a diferença de centavos fica dentro da tolerância.
      - Limitação documentada: quando a 1ª parcela do banco carrega o resto dos centavos, o total reconstruído difere
        do real em menos de n centavos por parcela.
    - Parcelas anteriores ao início do acompanhamento ficam fora da fatura e da utilização (ADR-0025 §15). A despesa
      continua sendo o total, na data da compra (ADR-0025 §3).
27. **Deduplicação por cartão.**
    - `imported_transaction_keys` ganha `card_id`, com exatamente um entre conta e cartão. A fórmula `v1` não muda.
    - A chave de uma linha conciliada aponta para a compra existente, então várias chaves podem apontar para a mesma
      compra (uma por parcela).
    - Não há dedupe contra lançamentos manuais à vista. A limitação do §11 continua.
28. **Início do acompanhamento.** O processamento recua o início do acompanhamento do cartão, sem nunca avançá-lo,
    até a fatura mais antiga com evidência no arquivo:
    - para uma linha comum, o ciclo da data da linha;
    - para uma parcela, a fatura em que a parcela cai.
29. **Batch, records e evento.**
    - `import_batches` ganha `card_id`, com exatamente um entre conta e cartão, e as contagens `skipped_lines` e
      `reconciled_lines`.
    - `total = valid + invalid + duplicate + skipped`; depois do processamento, `imported + reconciled ≤ valid`.
    - `import_records` ganha os status `SKIPPED` e `RECONCILED` e as colunas `installment_number` e
      `installment_count`.
    - `import.completed` leva `cardId` no lugar de `accountId`. `firstDate` cobre também a data das compras
      reconstruídas, para o Analytics recalcular o mês da compra.
    - API: `ImportResponse` ganha `cardId`, e `accountId` passa a aceitar nulo. Os records ganham
      `installment {number, count}` e os novos status. Todas as mudanças são aditivas.
30. **UI:** `/import` lista contas e cartões ativos como destino. O preview mostra "Parcela k de n" e "Pagamento:
    registre pela conta", e o resultado mostra as linhas conciliadas.

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
- **Faturas (adendo de 2026-10-23):**
  - **Cards consumindo eventos para gerar as parcelas:** rejeitado. A fatura e a utilização ficariam
    inconsistentes por um tempo (o mesmo motivo do ADR-0026, Alternatives).
  - **Importação de cartão dentro de Cards:** rejeitada. Duplicaria batch, records, dedupe, expiração e worker.
  - **Sinal da conta no CSV de fatura:** rejeitado. As exportações de fatura usam positivo para compra, e o arquivo
    seria importado invertido.
  - **Sinal autodetectado:** rejeitado. É heurística, e um arquivo com muitos créditos sairia invertido sem aviso.
  - **Parcela como compra à vista:** rejeitada. As parcelas futuras sumiriam da utilização, e cada mês repetiria uma
    "compra".
  - **Crédito de pagamento como `TRANSFER` a partir da conta padrão:** rejeitado. Duplicaria o débito importado na
    conta e inventaria a origem.
  - **Crédito de pagamento como `REFUND`:** rejeitado, porque seria dupla contagem.

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
