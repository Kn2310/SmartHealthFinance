# ADR-0013 — Categorização automática e comerciantes (M5, parte B)

## Status

Accepted

> Decisões de produto e de arquitetura aprovadas em 2026-10-13: normalização determinística e versionada, sem
> entidade `Merchant`; regras do sistema + regra aprendida da correção do usuário ("aplicar a transações
> semelhantes"); origem da categoria registrada na própria transação; categorização na importação, no lançamento
> manual e sob demanda (retroativa). Sem IA nem LLM.

## Context

As análises (M5, partes C–E) precisam de transações categorizadas e de um comerciante identificado. O ADR-0012
entregou o catálogo e a categorização **manual**; deixou para esta parte as regras automáticas, o comerciante, a
origem da categoria, o momento da categorização e a herança da categoria pelo reembolso (§4 do ADR-0012).

As specs não definem como sair da descrição bruta ("Mercado Bom Preço 0712", "Pix enviado - Maria Exemplo") até um
comerciante, nem como convivem a regra automática e a correção do usuário. O design mostra o alerta da importação
"Categorizamos 131 de 142 transações… nada aqui é definitivo" (`design/specs/03-screens.md`, ImportDone).

## Decision

### Comerciante

1. **O comerciante é um atributo derivado da transação**, não uma entidade. O `MerchantNormalizer` (função pura do
   domínio de Transactions) transforma a descrição em `merchantKey` (minúsculas, sem acento, usada para agrupar e
   para as regras), `merchantName` (exibição) e `merchantNormalizerVersion`.
   - **v1:** tira rótulos genéricos do início (`Pix enviado -`, `Compra no débito`, `Salário -`, `Aluguel -`,
     `Estorno compra online -`...), só como palavra inteira e nunca quando o rótulo é a descrição inteira; tira do
     fim parcelas, datas, códigos numéricos e sufixos societários (Ltda, ME, EPP, S/A...); junta espaços.
     Descrições todas em maiúsculas ou minúsculas viram "Title Case"; as demais mantêm o texto original.
   - Se nada com letra sobra, não há comerciante (`null`).
   - Qualquer mudança nas regras exige nova versão: a auditoria sabe qual algoritmo produziu cada chave.
   - **Adendo (2026-10-14, aprovado): v2.** Depois de um rótulo genérico, tira também a preposição que o liga ao
     comerciante (`de`, `do`, `da`, `dos`, `das`, `no`, `na`, `nos`, `nas`, `em`, `ao`, `aos`, `para`, `pra`):
     "Compra do Bob" → "Bob", "Pix enviado para Maria Exemplo" → "Maria Exemplo". Só logo após um rótulo e só como
     palavra inteira ("Casa do Pão" e "Do Bob Burgers" não mudam); o artigo "a" fica de fora por ser ambíguo; se a
     preposição for tudo o que sobrou, ela fica.
     - **Renormalização retroativa** (decisão aprovada: recalcular tudo), pelo `MerchantRenormalizationRunner` na
       inicialização (`shf.transactions.merchant-renormalize` = `outdated` | `off`), antes do rebuild do Analytics:
       toda transação com comerciante de versão anterior recebe o comerciante v2 calculado da **descrição atual**
       (exceção ao §3: se o usuário editou a descrição, a chave nova reflete o texto editado).
     - **A categoria não muda** (nem `MANUAL`, nem de regra): a renormalização não reavalia regras.
     - **Regras aprendidas** acompanham a chave: a regra da chave antiga é copiada para a nova (se as duas têm
       regra, vence a de `updated_at` mais recente) e a antiga é apagada quando nenhuma transação do Workspace a usa
       mais (ex.: "Do Bob" sem rótulo continua na chave `do bob` e mantém a regra).
     - Cada transação alterada grava `transaction.categorized` (ADR-0014 §2); a projeção também sobe para a versão 2
       e é reconstruída no deploy (modo `missing`). Idempotente; cada Workspace numa transação de banco.
       Runbook: `docs/runbooks/merchant-renormalization.md`.
2. **Só há comerciante onde há categoria** (`INCOME`, `EXPENSE`, `REFUND`). Transferência e ajuste não têm.
3. O comerciante é calculado **na criação** e não muda quando a descrição é editada (a chave que as análises e as
   regras usam fica estável). Transações anteriores ao ADR recebem o comerciante pelo comando retroativo (§9).
4. Fica adiado: renomear e mesclar comerciantes. Exigem entidade, CRUD e uma tela que o design não tem; o modelo
   atual permite introduzi-las depois sem quebrar o contrato.

### Regras de categorização

5. **Prioridade:** escolha do usuário (`MANUAL`, nunca sobrescrita) > regra aprendida do Workspace
   (`RULE_LEARNED`) > regra do sistema (`RULE_SYSTEM`) > sem categoria.
6. **Regras do sistema** (`SystemCategoryRules`, versão `system-v1`): palavras-chave dobradas (sem acento, sem
   pontuação) procuradas como palavra ou frase inteira na descrição, por natureza da categoria. A primeira regra que
   casa vence, e a ordem é a prioridade. Sem casamento, a transação fica sem categoria: não adivinhar vale mais que
   errar (por exemplo, `Pix enviado - Maria Exemplo` continua sem categoria).
7. **Regra aprendida:** ao categorizar, o usuário pode marcar "aplicar a transações semelhantes"
   (`applyToSimilar`). Isso grava a regra `comerciante → categoria` do Workspace (tabela `category_rules`, uma por
   comerciante e natureza) e a aplica às transações do mesmo comerciante **que o usuário ainda não escolheu**
   (sem categoria ou com categoria de regra). Nada é aprendido sem o usuário pedir.
   - Exige categoria e comerciante: `400 VALIDATION_FAILED` com `applyToSimilar` = `REQUIRES_CATEGORY` ou
     `NO_MERCHANT`.
   - A regra vale para os próximos lançamentos e importações do comerciante, só no próprio Workspace.

### Origem da categoria

8. A transação ganha `category_source` (`MANUAL` | `RULE_SYSTEM` | `RULE_LEARNED`) e `category_rule_version`
   (só para `RULE_SYSTEM`, hoje `system-v1`). `category_source` nulo = ninguém tratou ainda.
   - `PUT .../category` sempre grava `MANUAL`, inclusive confirmar a categoria que uma regra tinha dado e escolher
     **"Sem categoria"** de forma explícita. `MANUAL` sem categoria é uma escolha que nenhuma regra desfaz.
   - Regra automática (`Transaction.categorizeByRule`) devolve sem alterar se a origem é `MANUAL`; a proteção está
     no agregado, e as consultas de "semelhantes" já excluem `MANUAL`.
   - O banco garante: categoria exige origem; versão da regra existe só em `RULE_SYSTEM` e é obrigatória nela;
     comerciante é tudo ou nada. As categorias da parte A viram `MANUAL` na migration.
   - O histórico completo de mudanças fica para os eventos `transaction.categorized` (parte C).

### Momento

9. **Importação e lançamento manual (síncrono):** `RecordImportedTransactions` e `CreateTransaction` aplicam as
   regras na mesma transação de banco que grava o fato. O `POST /transactions` não ganhou campo de categoria: a
   escolha do usuário vem pelo `PUT .../category`.
10. **Retroativa, sob demanda:** `POST /api/v1/workspaces/{id}/transactions/categorize-pending` identifica o
    comerciante e aplica as regras às transações ainda não tratadas (`category_source` nulo) e preenche o comerciante
    das que não têm. Idempotente; nunca toca em `MANUAL`. Responde `{evaluated, categorized, merchantsIdentified}`.
    Varre o Workspace em lotes de 500, por id, numa única transação (volume do MVP).
11. **Importação:** o batch ganha `categorizedLines` (das importadas, as que as regras categorizaram), exposto em
    `lines.categorized` da API e no payload de `import.completed`. É a base do alerta "Categorizamos X de Y".

### Contrato

12. `TransactionResponse` ganha `categorySource` e `merchantName` (aditivos, nuláveis). `CategorizeTransactionRequest`
    ganha `applyToSimilar` (opcional). `ImportResponse.lines` ganha `categorized`. Snapshot OpenAPI atualizado
    (ADR-0010).

### Persistência

13. `V10__create_categorization_rules.sql`: colunas de comerciante e de origem em `transactions`, índice parcial
    `(workspace_id, merchant_key, occurred_on desc)` e a tabela `category_rules` (PK `workspace_id, merchant_key,
    category_nature`; FK composta para `categories`). `V11__add_import_categorized_lines.sql`: `categorized_lines`.

### Fora desta decisão

- **Herança da categoria do original pelo reembolso vinculado** (citada no §4 do ADR-0012): não foi decidida aqui. Hoje o
  reembolso passa pelas mesmas regras de qualquer transação e, sem casamento, fica sem categoria até o usuário
  escolher. Se o produto quiser a herança, é uma regra nova (e uma nova origem) que exige decisão própria.
- Os eventos `transaction.*` (inclusive `transaction.categorized`) seguem na parte C.

## Alternatives

- **Entidade `Merchant` por Workspace:** rejeitada por ora (CRUD, mescla e uma tela de gestão inexistente).
- **Agrupar pela descrição exata:** "Mercado Bom Preço 0712" e "...0815" virariam comerciantes diferentes.
- **Aprendizado silencioso de toda correção:** rejeitado (efeito surpresa, difícil de explicar e auditar; contraria
  "nada aqui é definitivo").
- **Só regras do sistema:** rejeitado (o usuário corrigiria a mesma loja repetidamente).
- **Tabela de histórico de categorização:** adiada para os eventos da parte C.
- **Categorização assíncrona por evento:** rejeitada (antecipa consumidores e consistência eventual da parte C
  sem necessidade; o custo da regra é desprezível dentro da transação).
- **Reclassificar "Sem categoria" explícito:** rejeitado; "Sem categoria" escolhido é um fato do usuário.

## Consequences

- Positivas:
  - toda análise tem uma chave de comerciante estável e uma categoria com origem auditável;
  - a correção do usuário é protegida no agregado e no banco;
  - sem nova dependência nem infraestrutura; tudo determinístico e testável (casos reais do exemplo e da fixture).
- Negativas:
  - sem renomear/mesclar comerciantes por ora;
  - o conjunto de palavras-chave é pequeno e em pt-BR; ampliá-lo exige nova versão (`system-v2`);
  - transações antigas só ganham comerciante/categoria quando o comando retroativo roda;
  - o comando retroativo roda numa transação só (aceitável no volume do MVP).

## Security / Privacy

- Comerciante e categoria revelam hábitos de consumo: nunca aparecem em log. Os logs novos carregam só IDs e
  contagens (`transactionId`, `workspaceId`, `evaluated/categorized/merchants`, `similarUpdated`), com teste.
- `category_rules` é por Workspace; nenhuma regra vaza entre Workspaces (teste de isolamento).
- Sem IA: nada sai do sistema.

## Financial Integrity

- Categoria e comerciante não alteram saldo nem fluxo de caixa; o Overview e o teste de paridade não mudam.
- Transferência e ajuste continuam sem categoria e sem comerciante (FK composta e `check` do ADR-0012).
- Os campos financeiros continuam imutáveis (ADR-0005 §8).

## Operational Impact

- Migrations `V10` e `V11`, somente aditivas (o `update` da V10 só marca como `MANUAL` as categorias da parte A).
- Endpoint novo `categorize-pending`; contrato OpenAPI e client do frontend atualizados.

## Related Specs

- `docs/adr/ADR-0012-transaction-categories.md` (§4 adiou regras, origem e momento para este ADR)
- `docs/adr/ADR-0009-csv-ofx-import.md` (importação, outbox e `import.completed`)
- `docs/adr/ADR-0006-overview-module.md` (§9, semântica de fluxo de caixa)
- `specs/05-system-design/05.3-application-architecture.md` (CategorizeTransaction)
- `design/specs/03-screens.md` (ImportDone: "Categorizamos X de Y")

## Date

2026-10-13
