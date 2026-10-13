# ADR-0012 — Categorias de transação (M5, parte A)

## Status

Accepted

> Decisões de produto e de arquitetura aprovadas em 2026-10-13: catálogo só do sistema, sem hierarquia; catálogo
> amplo (22 categorias de despesa e 6 de receita, lista no §3); categoria só em `INCOME`, `EXPENSE` e `REFUND`;
> `null` = "Sem categoria", sem backfill; categorias dentro do módulo `transactions`. Os detalhes de rota, contrato
> e persistência (§5–§10) seguem os padrões dos ADR-0004/0005/0006.

## Context

As telas de Análise (`/analysis` e as abas Gastos, Receitas, Categorias, Comerciantes e Evolução), as métricas do
topo de D-Transactions e "Por que meu dinheiro mudou?" dependem de transações categorizadas. O ADR-0005 adiou as
categorias para o M5 (§13) e só permitia editar a descrição (§8).

As specs citam `Category` como entidade (05.2), `CategorizeTransaction` como command (05.3) e `/categories` como
recurso (05.3/05.6), mas não definem:

- se as categorias são do sistema, do Workspace ou ambas, nem se há hierarquia;
- o catálogo inicial;
- em que módulo as categorias vivem;
- a quais tipos de transação a categoria se aplica;
- o que acontece com as transações que já existem.

O design mostra um único nível (Moradia, Alimentação fora, Mercado, Transporte, Assinaturas, Outros) e nenhuma
tela de gestão de categorias. Nas linhas de transferência e de receita, a lista exibe o tipo no lugar da categoria.

## Decision

### Modelo

1. **Catálogo do sistema, sem hierarquia.** As categorias são dados de referência gravados por migration, iguais
   para todos os Workspaces. O usuário escolhe uma categoria, mas não cria nem renomeia categorias.
   - Uma categoria nunca é apagada. Quando sair do catálogo, ela é desativada (`active = false`): continua visível
     nas transações antigas, mas não pode ser escolhida de novo.
   - Categorias do Workspace podem ser adicionadas depois, de forma aditiva, sem mudar as rotas (§6).
2. **Natureza.** Cada categoria tem `nature` = `EXPENSE` ou `INCOME`.
   - `EXPENSE` e `REFUND` aceitam só categorias de despesa. O reembolso é uma devolução de despesa (ADR-0006 §9).
   - `INCOME` aceita só categorias de receita.
   - `TRANSFER` e `ADJUSTMENT` nunca têm categoria. Transferência nunca é gasto nem receita, e ajuste não entra no
     fluxo de caixa (ADR-0006 §9). Na UI, esses tipos mostram o tipo no lugar da categoria, como no layout.
3. **Catálogo inicial** (código estável na API e nome em pt-BR), na ordem de exibição:

   | Natureza | Código | Nome |
   |---|---|---|
   | EXPENSE | `HOUSING` | Moradia (aluguel, condomínio, energia, água, gás, internet) |
   | EXPENSE | `HOME` | Casa e manutenção |
   | EXPENSE | `GROCERIES` | Mercado |
   | EXPENSE | `DINING_OUT` | Alimentação fora |
   | EXPENSE | `TRANSPORT` | Transporte |
   | EXPENSE | `HEALTH` | Saúde |
   | EXPENSE | `FITNESS` | Bem-estar e esportes |
   | EXPENSE | `PERSONAL_CARE` | Cuidados pessoais |
   | EXPENSE | `EDUCATION` | Educação |
   | EXPENSE | `CHILDREN` | Filhos |
   | EXPENSE | `PETS` | Pets |
   | EXPENSE | `LEISURE` | Lazer |
   | EXPENSE | `SUBSCRIPTIONS` | Assinaturas |
   | EXPENSE | `SHOPPING` | Compras |
   | EXPENSE | `CLOTHING` | Vestuário |
   | EXPENSE | `TRAVEL` | Viagem |
   | EXPENSE | `INSURANCE` | Seguros |
   | EXPENSE | `GIFTS_DONATIONS` | Presentes e doações |
   | EXPENSE | `BANK_FEES` | Tarifas bancárias |
   | EXPENSE | `SERVICES` | Serviços |
   | EXPENSE | `TAXES` | Impostos e taxas |
   | EXPENSE | `OTHER_EXPENSE` | Outros |
   | INCOME | `SALARY` | Salário |
   | INCOME | `SELF_EMPLOYED` | Trabalho autônomo |
   | INCOME | `INVESTMENT_INCOME` | Rendimentos |
   | INCOME | `RENTAL_INCOME` | Aluguel recebido |
   | INCOME | `BENEFITS` | Benefícios |
   | INCOME | `OTHER_INCOME` | Outras receitas |

   O texto entre parênteses em Moradia é a orientação de uso (a mesma do layout, em que energia entra em Moradia).
   O nome exibido é só "Moradia".
4. **"Sem categoria" é a ausência de categoria** (`null`), não uma categoria do catálogo.
   - "Sem categoria" (ninguém classificou) e "Outros" (alguém classificou como outros) são fatos diferentes.
   - A migration não altera nenhuma transação existente: todas ficam sem categoria.
   - A categorização automática e retroativa, a origem da categoria (`manual`/`rule`/`import`) e a herança da
     categoria do original pelo reembolso vinculado ficam para a parte B (ADR próprio), junto com as regras.

### Módulo

5. **As categorias vivem no módulo `transactions`.** Não há módulo novo (Categories não está na lista da 05.13 §4).
   - `Category` (`transactions.domain.model`), `CategoryCode` e `TransactionCategory` (`domain.valueobject`),
     `CategoryNature` (`domain.enums`) e a porta `CategoryRepository` (`domain.repository`).
   - A categoria é um atributo **não financeiro** da transação. A validação tipo × natureza fica no agregado
     (`Transaction.categorize`).
   - Analytics (M5, partes C e D) lê categorias e transações a jusante, sem que Transactions dependa dele.

### API

6. **Catálogo:** `GET /api/v1/workspaces/{workspaceId}/categories` → `{items: [{code, name, nature, active}]}`, na
   ordem de exibição, incluindo as inativas (para exibir transações antigas).
   - A rota é aninhada por Workspace, como em ADR-0004/0005: a autorização é a mesma das demais rotas e categorias do
     Workspace poderão entrar no futuro sem mudar o contrato. O catálogo é o mesmo para todos os Workspaces.
7. **Categorizar:** `PUT /api/v1/workspaces/{workspaceId}/transactions/{transactionId}/category`, com corpo
   `{"categoryCode": "DINING_OUT"}`. `{"categoryCode": null}` remove a categoria (volta a "Sem categoria"). O
   caso de uso é o `CategorizeTransaction` (05.3).
   - É idempotente: repetir a mesma categoria não grava nada.
   - Vale em qualquer status, como a descrição: a categoria não muda saldo nem fluxo de caixa.
   - A edição concorrente é tratada pelo lock otimista já existente (`409 CONFLICT`).
   - Erros de validação: `400 VALIDATION_FAILED` com `field = categoryCode` e um destes códigos:
     - `INVALID`: código mal formado ou inexistente;
     - `INACTIVE`: a categoria foi desativada;
     - `NOT_ALLOWED`: a transação é `TRANSFER` ou `ADJUSTMENT`;
     - `INCOMPATIBLE_WITH_TYPE`: a natureza não combina com o tipo.
   - Transação de outro Workspace ou inexistente → `404 TRANSACTION_NOT_FOUND` (regra do 404).
8. **`TransactionResponse` ganha `categoryCode`** (`string | null`), um campo aditivo. O nome é resolvido pelo
   catálogo, como `accountId` é resolvido pela lista de contas.
9. **Filtro da listagem:** `categoryCode` (uma categoria) ou `uncategorized=true` ("Sem categoria"). Os dois
   juntos → `400 VALIDATION_FAILED` (`uncategorized`, `CONFLICTS_WITH_CATEGORY`). Para o filtro, só o formato do
   código é validado: um código inexistente devolve uma lista vazia, como um filtro sem resultado.

### Persistência

10. **Migration `V9__create_transaction_categories.sql`**, somente aditiva:
    - tabela `categories (code, nature, name, display_order, active)`, com `unique (code, nature)` e o seed do §3;
    - `transactions.category_code` e `transactions.category_nature`, ambos nulos por padrão;
    - **FK composta** `(category_code, category_nature) → categories (code, nature)` e um `check` que amarra a
      natureza ao tipo (`INCOME` ↔ `INCOME`; `EXPENSE`/`REFUND` ↔ `EXPENSE`; `TRANSFER`/`ADJUSTMENT` sem
      categoria). A regra vale no banco mesmo com bug na aplicação, no mesmo espírito das FKs compostas do ADR-0005
      §10;
    - índice `(workspace_id, category_code, occurred_on desc)` para o filtro por categoria e para as agregações do
      M5.
    - No mapeamento JPA, os campos financeiros continuam `updatable = false`. Só a descrição, o status e a categoria
      mudam.

### Fora desta decisão

- Os eventos `transaction.*`, inclusive `transaction.categorized`, ficam para a estratégia de read models (parte C).
- As regras automáticas, os comerciantes e a origem da categoria ficam para a parte B.
- A categoria na criação manual (`POST /transactions`) e na importação também fica para a parte B, que decide o
  momento da categorização.

## Alternatives

- **Categorias do Workspace (personalizáveis), sozinhas ou com hierarquia:** rejeitada por ora. Exige CRUD, mescla e
  exclusão, além de uma tela que o design não tem. As análises também deixariam de ser comparáveis. O modelo atual
  permite adicioná-las depois de forma aditiva.
- **Catálogo mínimo do design (6 + 3) ou intermediário (14 + 4):** rejeitado pelo produto em favor do amplo, para
  não jogar farmácia, academia, seguro etc. em "Outros".
- **Módulo próprio `categories`:** rejeitado. Seria um módulo para uma tabela de leitura, com regras ArchUnit novas.
  Seria uma abstração prematura.
- **Categorias em `analytics`:** rejeitado. Transactions dependeria de Analytics (ciclo), ou a categoria ficaria fora
  da transação e a correção do usuário deixaria de ser um atributo auditável do fato.
- **Categoria em todos os tipos (incluindo "Transferência"):** rejeitado. Abriria a porta para a transferência contar
  como gasto, o que contraria o ADR-0006 §9.
- **Atribuir "Outros" às existentes:** rejeitado. Misturaria "não classificado" com "classificado como outros".
- **Rota do catálogo fora do Workspace (`/api/v1/categories`):** rejeitada. Teria uma autorização diferente das
  demais e mudaria quando surgissem categorias do Workspace.
- **Ação `POST .../{id}/categorize`:** rejeitada em favor do `PUT` no sub-recurso, que é idempotente por natureza e
  remove a categoria com `null`.
- **Chave substituta (UUID) para a categoria:** desnecessária. O código é estável e é o próprio contrato da API.
- **Validar natureza só na aplicação:** rejeitado. O `check` com FK composta custa uma coluna e protege o fato.

## Consequences

- Positivas:
  - as análises (partes C e D) têm uma base determinística, com uma única natureza por categoria;
  - transferência e ajuste nunca podem ser categorizados, nem por bug;
  - nenhuma transação existente é alterada;
  - não há tabela por Workspace, então não há nova superfície de isolamento de dados.
- Negativas:
  - o usuário não cria categorias próprias;
  - o ADR-0005 é emendado: o §8 passa a incluir a categoria entre os campos editáveis, e o §13 é substituído por este
    ADR;
  - o frontend precisa carregar o catálogo para exibir os nomes;
  - até a parte B, toda transação nasce sem categoria (a categorização é manual).

## Security / Privacy

- O código da categoria revela hábitos de consumo, então é tratado como dado financeiro: nunca aparece em log. O log
  de categorização tem só `transactionId` e `workspaceId` (há teste).
- O catálogo não tem dado de usuário.
- A suíte `WorkspaceIsolationIT` cobre as duas rotas novas e a categorização de transação alheia (404).

## Financial Integrity

- A categoria não altera saldo nem fluxo de caixa: o Overview e o teste de paridade não mudam.
- A FK composta e o `check` garantem no banco a coerência tipo × natureza e a ausência de categoria em transferências
  e ajustes.
- Os campos financeiros continuam imutáveis (ADR-0005 §8).

## Operational Impact

- Migration `V9`, somente aditiva, com seed do catálogo e um índice novo.
- Sem nova dependência nem infraestrutura.
- O snapshot OpenAPI (ADR-0010) e o contrato manual do frontend (ADR-0007 §11) são atualizados.

## Related Specs

- `specs/05-system-design/05.2-domain-architecture.md` (Category)
- `specs/05-system-design/05.3-application-architecture.md` (CategorizeTransaction, `/categories`)
- `specs/05-system-design/05.6-api-integration.md`
- `design/specs/03-screens.md` (D-Transactions, D-TxnDetail, ANALYSIS), `design/specs/09-dados-de-exemplo.md`
- `docs/adr/ADR-0005-transactions-module.md` (§8 e §13 emendados)
- `docs/adr/ADR-0006-overview-module.md` (§9)

## Date

2026-10-13
