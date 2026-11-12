# Development Backlog

## M0 Foundation

- [x] monorepo (`backend/`, `frontend/`, `infrastructure/`, `design/`, `specs/`, `docs/adr/` — PR #1)
- [x] Docker Compose (`infrastructure/docker-compose.yml` local, perfis `app` e `observability`;
  `docker-compose.deploy.yml` para staging/produção — PR #1)
- [x] Spring Boot (4.1, Java 25, monólito modular — ADR-0001, PR #1)
- [x] Next.js (App Router + BFF — ADR-0007, PR #7)
  - upgrade de segurança 16.4.0 → 16.4.3 (Sessão 7.1, parte 0): corrige GHSA-2464-fhr4-w9xw (RCE no Image
    Optimization com SVG), GHSA-673q-mjm6-r5xr (RCE em `use cache` com typed arrays) e GHSA-p3m5-59mp-jv5r (DoS por
    Server Actions), todos `>=16.0.0 <=16.4.0`; `eslint-config-next` acompanha. Sem mudança de código; `next build`
    volta a passar; CSP com nonce, redirecionamento para `/auth/login` e BFF 401 sem sessão conferidos no `next start`
- [x] PostgreSQL (17, fonte de verdade — PR #1)
- [x] Redis (infra local/deploy e readiness da API; ainda sem uso funcional — nunca fonte de verdade, specs 05.5)
- [x] RabbitMQ (Transactional Outbox + Import Worker com retry e DLQ — ADR-0009 §14/§15, PR #10)
- [x] Flyway (`V1`–`V8`, `ddl-auto: validate`)
- [x] Spring Security (OAuth2 Resource Server sobre o IdP OIDC — ADR-0002, PR #1)
- [x] OpenAPI (springdoc code-first, `/v3/api-docs` desligado por padrão; o frontend usa contrato manual com teste
  de drift — ADR-0007 §11)
- [x] OpenTelemetry (starter + export OTLP opcional, Grafana LGTM no perfil `observability` — PR #1)
- [x] Testcontainers (PostgreSQL, RabbitMQ e Redis reais nos `*IT` — `support/TestcontainersConfiguration`)
- [x] ArchUnit (`architecture/ArchitectureTest` — ADR-0001)
- [x] CI (`.github/workflows/ci.yml`: backend `./mvnw verify` + frontend lint/typecheck/test/build, snapshot do
  contrato OpenAPI verificado dos dois lados — ADR-0010)
- [x] Design System foundation (`design/` como fonte de tokens/fontes, `scripts/sync-design.mjs`, componentes
  `components/ui` — ADR-0007 §13, PR #7)
- [x] ADR template (`specs/06-development/adr-template.md`)

## M1 Workspace + Accounts

- [x] Workspace (pessoal automático no provisionamento — ADR-0003, PR #2)
- [x] membership (`WorkspaceAccessGuard.requireMember`, regra do 404 — ADR-0003, PR #2)
- [ ] roles/permissions (MVP: apenas OWNER — ADR-0003; papéis adicionais exigem revisão do ADR)
- [x] Account aggregate (`accounts.domain.model.Account` — ADR-0004, PR #4)
- [x] account persistence (`V4__create_accounts.sql`, `JpaAccountRepository`, lock otimista por `version` — PR #4)
- [x] create/update account (`CreateAccount`, `UpdateAccount`, `ArchiveAccount`, `ReactivateAccount` — PR #4)
- [x] account API (`/api/v1/workspaces/{id}/accounts`: listar, consultar, criar, editar com lock otimista, arquivar/reativar — ADR-0004, PR #4)
- [x] account UI (`/accounts` lista com saldo do Overview e filtro de arquivadas, `/accounts/new` com retorno para `/import`, `/accounts/{id}` edição + arquivar/reativar; BFF `/api/bff/accounts[/{id}[/archive|/reactivate]]` — ADR-0004 §11, PR #12)
- [x] movimentações por conta em `/accounts/{id}` (D-AccountDetail): lista de transações filtrada pela conta, com "Nova transação" (ADR-0005 §16, PR #13)
- [x] saldo inicial em `/accounts/new` como `ADJUSTMENT` orquestrado pelo BFF, com retry idempotente (ADR-0004 §12, PR #13)
- [x] isolation tests (`isolation/WorkspaceIsolationIT`: as 23 operações sob `/workspaces/{id}` contra Workspace
  alheio, 19 casos de id alheio no próprio Workspace e checagem de cobertura de endpoints novos — ADR-0003, adendo
  ADR-0005 §1)

## M2 Transactions

- [x] Transaction aggregate (ADR-0005)
- [x] money VO (`shared.domain.Money`)
- [x] transaction types
- [x] status
- [x] dedupe (lançamento manual: Idempotency-Key + request hash; dedupe de import por external ID fica no M4)
- [x] transaction API
- [x] transaction list
- [x] filters
- [x] tests
- [x] transaction UI (`/transactions` com filtros, `/transactions/new` receita/despesa/ajuste com `Idempotency-Key` por intenção, `/transactions/{id}` efetivar/cancelar/estornar e editar descrição; BFF `/api/bff/transactions[/{id}[/post|/cancel|/reverse]]`; M-More mínimo — ADR-0005 §16)
- [x] métricas do topo de D-Transactions (Entradas/Saídas/Transferências/Resultado do período filtrado, `GET
  .../transactions/summary` com os filtros da lista) e "Por que meu dinheiro mudou?" com filtro de mês inteiro — ADR-0016
- [x] transferência e reembolso pela UI (ADR-0026 §10): "Transferência" com conta de origem e de destino e
  "Reembolso" em `/transactions/new`; "Registrar reembolso" no detalhe da despesa efetivada abre o formulário já
  vinculado (`?type=REFUND&refundOf=…`), com opção de remover o vínculo; BFF valida destino e vínculo por tipo
- [x] eventos `transaction.*` (created, posted, cancelled, reversed, categorized) pela outbox, consumidos pela
  projeção de Analytics (ADR-0014); importações seguem pelo `import.completed`

## M3 First Home

- [x] balance calculation (derivado de transações POSTED — ADR-0006)
- [x] overview query (`GetFinancialOverview`, ADR-0006)
- [x] Home API — bloco Overview em `GET /api/v1/workspaces/{id}/overview` (o composto `/api/v1/home` entra com Health/Insights)
- [x] Health na Home: card "Como estou?" (M6, ADR-0020)
- [x] Home com cartões (M8, ADR-0006 §18): "Faturas a pagar" logo abaixo do saldo, separado do saldo total e sem saldo
  líquido; "Próximos eventos" com fechamento e vencimento das faturas nos próximos 30 dias e as vencidas, **só fatos**
  ("Parcial", "Em aberto", "Vencida"). Receitas e despesas recorrentes estimadas continuam para o Forecast (M9)
- [x] Home UI — Overview: saldo, fluxo do período, contas, movimentações recentes, seletor de período (ADR-0007/0008; critérios da etapa validados em 2026-10-07; Health/Insights ficam para M6/M7)
- [x] responsive states — READY, NO_ACCOUNTS, NO_TRANSACTIONS (com PENDING), NO_ACTIVITY_IN_PERIOD, loading, erro; 375/768/1024/1280/1440 sem overflow
- [x] E2E (Playwright) do First Financial Loop (saldo inicial → receita → despesa → saldo e fluxo exatos na Home) e da
  Home vazia (`NO_ACCOUNTS`) — `frontend/e2e/`, `e2e.yml`, ADR-0011
- [x] dívida técnica de `recentTransactions` resolvida: ADR-0006 §12/§13 emendado para o comportamento real (recentes consultados sempre que há conta ativa; em `NO_TRANSACTIONS` só `PENDING`, exibidos no card "Movimentações pendentes"); sem mudança de API nem de produto, coberto por `GetFinancialOverviewTest` e `OverviewApiIT`

## M4 Import

- [x] CSV parser (modelo canônico + autodetecção — ADR-0009 §6)
- [x] OFX parser (1.x SGML e 2.x XML, parser próprio — ADR-0009 §5)
- [x] import batch (PREVIEW → CONFIRMED → PROCESSING → COMPLETED/FAILED, CANCELLED, EXPIRED — ADR-0009 §2)
- [x] import records (lineage por linha; arquivo bruto nunca guardado)
- [x] dedupe (por conta: FITID+data+valor ou impressão digital com ocorrência — ADR-0009 §11)
- [x] async job (Transactional Outbox + RabbitMQ + Import Worker com inbox, retry e DLQ — ADR-0009 §14/§15)
- [x] status API (`/api/v1/workspaces/{id}/imports`, upload 201, confirm 202)
- [x] import UI (`/import`: conta + arquivo → preview → confirmação → acompanhamento → resultado; selo "Importado" na Home — ADR-0009 §18)
- [x] E2E da importação: CSV fictício → preview → confirmação → resultado → selo "Importado", com somas exatas do
  fixture (`frontend/e2e/import.spec.ts` — ADR-0011)
- [x] faturas de cartão (`CCSTMTRS` e CSV de fatura) entram no M8 (adendo ADR-0009 §19–§30)

## M5 Analytics

- [x] analytics API (`GET .../analytics/{overview,spending,income,categories,categories/{code},merchants,evolution}`;
  mês + bases "mês anterior" e "média" no mesmo recorte de dias; Estável < 5%; composição e decomposição da
  variação que fecham exatamente; fontes de receita recorrentes — ADR-0015)
- [x] analysis UI (`/analysis` e abas Gastos, Receitas, Categorias, Comerciantes e Evolução; gráficos em SVG do DS com
  tabela acessível; mês e "Comparar com" na URL; estados de dados, freshness e média curta; responsivo 375–1440 —
  ADR-0016)
- [x] spending analysis (API ADR-0015, UI ADR-0016)
- [x] income analysis (API ADR-0015, UI ADR-0016; "próxima receita prevista" fica para o M9)
- [x] categories (catálogo do sistema 22 + 6, sem hierarquia; categoria em INCOME/EXPENSE/REFUND, nulo = "Sem
  categoria"; `GET /workspaces/{id}/categories`, `PUT .../transactions/{id}/category`, filtro `categoryCode` /
  `uncategorized`; FK composta tipo × natureza no banco; UI: categoria na lista, filtro e "Alterar categoria" no
  detalhe — ADR-0012)
- [x] categorização automática e origem da categoria (`MANUAL`/`RULE_LEARNED`/`RULE_SYSTEM` + versão da regra; regras
  do sistema `system-v1`; regra aprendida por "aplicar a transações semelhantes"; categorização na importação, no
  lançamento manual e sob demanda em `POST .../transactions/categorize-pending`; "Categorizamos X de Y" no resultado
  da importação — ADR-0013)
- [x] merchants (comerciante derivado da descrição por `MerchantNormalizer` v1, atributo da transação, sem entidade —
  ADR-0013; renomear/mesclar comerciantes e botão de "categorizar pendentes" na UI ficam para depois)
- [x] evolution (API ADR-0015, UI ADR-0016; score na evolução no M6, ADR-0020)
- [x] read models (projeção materializada `analytics_daily_flows` por eventos, consumidor idempotente com inbox,
  retry e DLQ, rebuild na inicialização + runbook, freshness em `GET .../analytics/status`, paridade com a
  referência do domínio — ADR-0014)
- [x] "marcar como reembolso" para estornos importados: a receita importada é estornada e substituída por um REFUND
  igual, com vínculo opcional à despesa original (`POST .../transactions/{id}/mark-as-refund`; ação no detalhe da
  transação; saldo inalterado, entrada vai de receitas para reembolsos — adendo de 2026-10-14 ao ADR-0009 §9). Desfazer
  a marcação fica para depois
- [x] normalizador de comerciante v2: tira a preposição depois do rótulo genérico ("Compra do Bob" → "Bob");
  renormalização das transações v1 na inicialização, regras aprendidas acompanham a chave, categorias intactas,
  projeção de Analytics v2 (adendo de 2026-10-14 ao ADR-0013 §1, runbook `merchant-renormalization.md`)

## M6 Health

- [x] modelo `health-v1` (ADR-0017): 6 dimensões com fórmula, curvas e templates; Credit/Debt `NOT_APPLICABLE` até o
  M8 com renormalização e cobertura; pesos 25/20/15/15/15/10; reserva = contas `SAVINGS` ativas; estimado com < 3
  meses; confiança por meses e % sem categoria; evidências; fatores e próximos passos determinísticos (sem ✦)
- [x] dimensions, calculator, algorithm version, evidence (`health-v1` puro no domínio, testes de tabela com o
  fixture de 09-dados-de-exemplo — ADR-0017)
- [x] snapshot por eventos (ADR-0018): `analytics.projection-updated` (adendo ao ADR-0014) e `account.changed`
  pela outbox; consumidor `health.snapshot` idempotente com inbox, retry e DLQ; debounce 10 s / teto 60 s; mês
  corrente + 12 fechados por versão; job diário; rebuild na inicialização + runbook `health-rebuild.md`; paridade
  com o cálculo direto sobre as portas
- [x] freshness: `GET .../health/status` (`UP_TO_DATE`, `RECALCULATING`, `NOT_BUILT`), métrica
  `shf.health.snapshot.freshness`; endpoint na suíte de isolamento
- [x] Health API (ADR-0019): `GET .../health?month` (resumo, dimensões, fatores e próximos passos do snapshot,
  com fontes, estados `READY`/`INSUFFICIENT_DATA`/`NO_DATA` e freshness), `.../health/evolution?month&months`
  (1–12) e `.../health/method`; testes de API, contrato OpenAPI e suíte de isolamento
- [x] Health UI (ADR-0020): card "Como estou?" na Home (sempre visível, mês corrente), `/home/health` (resumo, fontes,
  dimensões com evidências, fatores, próximos passos, evolução), drawer "Como calculamos?" acessível, score na Análise ›
  Evolução; tokens `on-dark` no DS; E2E com valores exatos do `health-v1`
- [x] `health-v2` (M8, ADR-0027): Credit = 0,7 · S(utilização agregada com parcelas futuras) + 0,3 · faturas pagas
  até o vencimento; Debt = 0,7 · S(parcelas da próxima fatura ÷ renda) + 0,3 · S(parcelas futuras em meses de renda);
  sem cartão continuam `NOT_APPLICABLE`; o Aurora reproduz Credit 71 e Debt 88; porta `CardPortfolio` (posição dos
  cartões em qualquer data); bindings `card.*`/`invoice.closed` na fila do Health; passos "Ver fatura"/"Ver cartão"
  (Em breve até a Parte F); linhas `health-v1` preservadas; runbook atualizado

## M7 Insights

- [x] modelo `insights-v1` (ADR-0021): 10 detectores determinísticos (categoria e gasto total acima do padrão no mesmo
  recorte de dias, comerciante recorrente novo, compra atípica, receita recorrente ausente/menor, nova fonte de
  receita, variação do score e de dimensão do Health, reserva baixa, gastos sem categoria); piso de impacto; Impact,
  Relevance, Confidence; Importante/Informativo; fingerprint, estados e versionamento; evidências; feedback
- [x] detector, impact, relevance, confidence, evidence (`insights-v1` puro no domínio, testes de tabela com o
  fixture de 09-dados-de-exemplo e casos de borda — ADR-0021)
- [x] geração encadeada no Health (ADR-0022): `health.snapshot-updated` pela outbox (adendo ao ADR-0018);
  consumidor `insights.generation` idempotente com inbox, retry e DLQ; debounce 10 s / teto 60 s; reconciliação
  (novo, atualizado, resolvido, expirado, dispensado nunca volta); retenção de 13 meses; rebuild que só marca +
  runbook `insights-rebuild.md`; paridade com a geração direta sobre as portas
- [x] freshness: `GET .../insights/status` (`UP_TO_DATE`, `UPDATING`, `NOT_BUILT`), métricas
  `shf.insights.generation[.freshness]`; endpoint na suíte de isolamento
- [x] Insights API (ADR-0023): lista com filtros, contagens dos chips e paginação; detalhe com evidências, origens e
  relacionados; ações visto, dispensar e feedback (idempotentes, com lock contra a geração); destaques da Home
  ("Merece atenção", "O que mudou?" dos insights, badge); relacionados a transação ou dimensão; estados `NO_DATA`,
  `INSUFFICIENT_HISTORY`, `READY`; testes de API, contrato OpenAPI e suíte de isolamento
- [x] insight UI (ADR-0024): `/insights` (chips com contagem, Cartões "Em breve", Importantes / Outros), `/insights/:id`
  (resumo, evidências com gráfico do DS e tabela, explicação, limitações, fontes, ação recomendada, confiança, feedback,
  dispensar com confirmação, relacionados), Home "O que mudou?" e "Merece atenção", "Ver insight relacionado" na
  transação, próximo passo do Health → insight, contagem de Importantes na sidebar e no M-More; E2E com valores exatos
- [x] `insights-v2` (M8, ADR-0028): D11 fatura acima do padrão (fatores que somam a diferença), D12 utilização alta e
  D14 fatura vencendo/vencida; tipo `CARDS` e chip Cartões habilitado; "Registrar pagamento" leva a
  `/transactions/new`; "Ver cartão"/"Ver fatura" Em breve até a Parte F; troca de versão herda visto e feedback; V19

## M8 Cards

- [x] modelo (ADR-0025):
  - compras = transações com `card_id`, pelo valor total na data da compra;
  - pagamento de fatura = `TRANSFER` conta → cartão;
  - fatura `[fechamento anterior, fechamento)` pelo mês do vencimento, com valor e estado derivados;
  - centavos das parcelas na 1ª;
  - estorno consome as parcelas futuras;
  - utilização = (em aberto + parcelas futuras) ÷ limite;
  - dívida fora do saldo total;
  - eventos `card.*` e `invoice.closed`;
  - adendos ADR-0005 §17 e ADR-0006 §17.
- [x] card (ADR-0026): módulo `cards`, `CreditCard` só com os 4 últimos dígitos, CRUD + arquivar/reativar, requests
  que recusam campos desconhecidos, conta de pagamento padrão, eventos `card.*`, ArchUnit e suíte de isolamento
- [x] invoice — núcleo (ADR-0026): ciclo `invoice-v1` (dia 31, fevereiro, fechamento exclusivo, vencimento efetivo no
  fim de semana), datas congeladas depois do fechamento, estados derivados, alocação de pagamentos, faturas por mês de
  vencimento, atual, futuras comprometidas e lançamentos paginados; job `invoice.closed`; paridade do SQL. Compras,
  créditos e pagamentos entram na parte C
- [x] invoice — lançamentos (ADR-0026 §10): compras, estornos e pagamentos entram nas faturas; reatribuição das
  parcelas das faturas não fechadas na mudança de ciclo, na mesma transação (mês fechado → primeira não fechada)
- [x] installment (ADR-0026 §10): `POST /cards/{id}/purchases` à vista ou em 1–48 parcelas com `Idempotency-Key`
  (o hash inclui o número de parcelas), centavos na 1ª parcela, `GET /cards/{id}/installments[/{transactionId}]`;
  estornos (`POST …/credits`) aplicados na leitura pelo `CreditAllocator` (parcelas não faturadas, da última para a
  primeira; o resto vira crédito na fatura da data do estorno)
- [x] utilization (ADR-0025 §9, ADR-0026 §6): (em aberto + fatura atual + parcelas futuras) ÷ limite, inteiro
  HALF_EVEN, composição e "sem parcelas futuras"; resumo dos cartões ativos numa chamada
- [x] payment (ADR-0026 §10): `POST /cards/{id}/payments` grava `TRANSFER` conta → cartão (`destination_card_id`),
  aplicado da fatura mais antiga para a mais nova; sai do saldo, não entra em despesas
- [x] sem dupla contagem no Overview e no Analytics: compra e estorno de cartão ativo em despesas/reembolsos, pagamento
  só em transferências, saldo total só de contas; `analytics_daily_flows.card_id`, rebuild `VERSION` 3; paridade SQL
- [x] frontend mínimo (ADR-0026 §10): "Compra no cartão" (total + parcelas) e "Pagamento de fatura" (conta padrão
  sugerida) em `/transactions/new`; `/cards/new` só com os 4 últimos dígitos; cartão na lista, no detalhe ("Pago com",
  "Fatura do cartão") e na Home; BFF `/api/bff/cards[/{id}/purchases|payments|credits]`; E2E
  `card-transactions.spec.ts` (cartão → compra parcelada → pagamento → Home sem dupla contagem)

- [x] importação de fatura (Sessão 6.2 parte D; adendos ADR-0009 §19–§30 e ADR-0025 §7/§15):
  - destino conta **ou** cartão no upload; Ingestion → Cards na mesma transação tudo-ou-nada (ArchUnit);
  - OFX `CCSTMTRS` (o `UNSUPPORTED_STATEMENT_TYPE` vira `STATEMENT_TYPE_MISMATCH`) e CSV de fatura com o sinal das
    exportações (positivo = compra) e coluna opcional `parcela`; vencimento e mínimo só do `CCCLOSING`;
  - crédito de pagamento fica `SKIPPED` (a conta é a fonte); outro crédito vira `REFUND`; parcela k/n é conciliada com
    um parcelamento compatível (`RECONCILED`) ou reconstruída pelo total na data da compra;
  - dedupe por cartão; início do acompanhamento do cartão (`invoice-v2`, parcelas `BEFORE_TRACKING`) recuado pela
    evidência do arquivo;
  - `POST /cards/{id}/payments/reconcile` ("Marcar como pagamento de fatura"), vinculando a um pagamento manual da
    mesma conta, valor e ±5 dias; V18; suíte de isolamento; `CardStatementImportApiIT`;
  - UI: cartões como destino em `/import`, modelo `/modelo-fatura.csv`, parcela e pagamento no preview, ação no
    detalhe da despesa importada; E2E `card-import.spec.ts` com a fatura fictícia do Aurora.

- [x] integrações dos cartões (Sessão 6.2 parte E; ADR-0027, ADR-0028, adendos ADR-0006 §18, 0017, 0018, 0020–0026):
  - Overview: bloco aditivo `cards` ("Faturas a pagar" e "Próximos eventos", sempre em hoje, só fatos); saldo total
    continua só das contas;
  - Analytics: sem mudança (compra em gastos pelo total, pagamento só em transferências; `VERSION` 3; paridade já
    cobria cartões);
  - Health `health-v2` (Credit e Debt) e Insights `insights-v2` (D11, D12, D14), rebuilds encadeados
    Analytics → Health → Insights sem apagar estado do usuário;
  - porta `CardPortfolio` (posição em qualquer data, utilização histórica recalculada pelos fatos); ArchUnit;
  - suíte de isolamento e paridade; E2E `cards-integration.spec.ts`.

- [x] telas de cartões (Sessão 6.2 parte F; ADR-0029, adendos ADR-0020, 0024 e 0026 §11):
  - `/cards` (métricas, bloco por cartão com fatura atual, utilização com parcelas futuras tracejadas e compromisso da
    próxima fatura, insight de Cartões, parcelamentos ativos, arquivados), `/cards/:id` (Visão geral, Faturas,
    Parcelamentos), fatura atual e por mês (parcial, datas, mínimo só do arquivo, pago e em aberto, "✦ Explicação" do
    D11, lançamentos paginados), próximas faturas (só o comprometido) e parcelamento (blocos, `BEFORE_TRACKING`,
    impacto e limite reservado);
  - backend aditivo: `GET /cards/{id}/utilization/history` e `InstallmentPlan.currentInvoiceShare`; suíte de
    isolamento; BFF com os `GET` de cartões, faturas, parcelamentos e utilização, `PUT`, `archive` e `reactivate`;
  - destinos ativados: `VIEW_CARD`/`VIEW_INVOICE` (Insights), `PAY_OVERDUE_INVOICE`/`REVIEW_CARD_UTILIZATION` (Health),
    item "Cartões" da navegação;
  - lacunas da 6.1 fechadas: filtro "Conta ou cartão", "Ver fatura"/"Ver cartão"/"Ver parcelamento" no detalhe,
    edição e arquivamento do cartão, estorno no cartão ("Estorno no cartão" em `/transactions/new`);
  - E2E `cards-frontend.spec.ts` (datas relativas a hoje, 5 larguras).
- [ ] fatura "por categoria" (D-Invoice): adiada (ADR-0029 §6); precisa de endpoint próprio com a regra de
  agrupamento do D11
- [x] estimativa da próxima fatura e das faturas futuras: entregue no M9 (ADR-0033 §4)

**M8 completo** (Sessões 6.1 e 6.2), com as duas pendências acima explicitamente adiadas e as do ADR-0025 §14
(feriados, `INCOME`/`ADJUSTMENT`/`PENDING` no cartão) fora do MVP.

### Handoff da Sessão 6.1 para a 6.2

**Decisões em vigor** (não reabrir sem ADR): ADR-0025 (modelo, com adendo de estornos) e ADR-0026 (API, com o adendo
§10 dos lançamentos). Pontos que a 6.2 deve respeitar:
- compra = `EXPENSE` com `card_id` (sem conta), pelo total na data da compra; pagamento = `TRANSFER` com
  `destination_card_id`; estorno = `REFUND` com `card_id`. Cartão só aceita `POSTED`/`REVERSED` (sem `PENDING`);
- fatura, parcelas, estornos, pagamentos e utilização são calculados no backend (`invoice-v1`); o frontend só exibe e
  deve mostrar a versão do cálculo onde houver valor derivado;
- correção de lançamento errado = `POST /transactions/{id}/reverse`.

**Contratos prontos para as telas:** `GET /cards`, `/cards/summary`, `/cards/{id}` (com `PUT`, `archive` e
`reactivate`), `/cards/{id}/invoices` (`/current`, `/future`, `/{dueMonth}` e `/{dueMonth}/items` paginado),
`/cards/{id}/installments[/{transactionId}]`,
`/cards/{id}/utilization`; `POST /cards/{id}/purchases|payments|credits`; `GET /transactions?cardId=` e
`/transactions/summary?cardId=`; `recentTransactions[].card` no Overview. O BFF hoje só expõe `GET|POST /api/bff/cards`
e os três `POST` de lançamento — a 6.2 adiciona os `GET` de fatura, parcelamento, resumo e utilização.

**Eventos:** `card.created|updated|archived|reactivated` e `invoice.closed` (job) vão para a outbox, ainda **sem
consumidor**. Lançamentos de cartão usam os `transaction.*` existentes (payload só com id e `occurredOn`); o Analytics
os consome e relê a fonte.

**Lacunas conhecidas (fora do escopo da 6.1):** todas fechadas na 6.2.
- ~~estorno no cartão pela UI~~ (parte F);
- ~~edição/arquivamento do cartão pela UI e o filtro "Conta ou cartão" na lista de transações~~ (parte F);
- ~~telas `/cards`, fatura, faturas futuras e parcelamento~~ (parte F); ~~bloco "Faturas a pagar" no Overview; chip
  "Cartões" nos Insights~~ (parte E);
- ~~o item "Cartões" da navegação aponta para `/cards`, que ainda não existe~~ (parte F).

**Pendências que precisam de decisão:** as listadas no ADR-0025 §14 (CSV/OFX de fatura, parcelas no extrato,
conciliação do pagamento importado, saldo devedor inicial, feriados, `health-v2`, detectores, histórico de utilização,
`INCOME`/`ADJUSTMENT`/`PENDING` no cartão).

## M9 Forecast

- [x] modelo `forecast-v1` (Sessão 7.1, parte A — ADR-0030; adendos ADR-0006, 0015, 0016, 0021, 0025, 0029):
  - taxonomia `FACT`/`ESTIMATED`/`SIMULATED`;
  - recorrências mensais detectadas, com confirmar, ignorar e editar;
  - projeção do mês, próxima receita, próximos eventos, fluxo de caixa (mês + 3) e faturas;
  - mediana + mín–máx; confiança; previsto × realizado no backend; D15; porta `ForecastReader` para a 7.2
- [x] motor de Forecast e recorrências (parte B — ADR-0031; adendos ADR-0014, 0022, 0030):
  - snapshot por eventos em paralelo ao Health (`Analytics → {Health ‖ Forecast} → Insights`), debounce + job;
  - `V20`: séries (estado do usuário), snapshots e previsto × realizado (congelado na virada, reconciliado até D+5);
  - job diário 00:07, rebuild na inicialização + runbook, `forecast/status` e métricas de freshness;
  - `forecast.snapshot-updated` ligado à fila dos Insights (status `UPDATING` com o Forecast em recálculo; o D15 fica para a parte C); ArchUnit das fronteiras do módulo e `forecast/status` na suíte de isolamento
- [x] API de Forecast, `/recurring-expenses` e `/recurring-incomes` e D15 (parte C — ADR-0032; adendo ADR-0028)
  - leituras `forecast/{month,next-income,upcoming,invoices,cash-flow}` só do snapshot, sempre `200` com `state` e
    `freshness`, valores com `basis`, partes, faixa, confiança e evidências;
  - recorrências por id opaco (`V21`): lista, detalhe, `confirm`, `ignore`, `PUT`/`DELETE adjustment`, idempotentes;
  - suíte de isolamento, snapshot OpenAPI e `schema.d.ts`
  - D15 `BALANCE_MAY_GO_NEGATIVE` entregue no backend (`insights-v3`, lê o `ForecastReader`; ação `VIEW_UPCOMING_EVENTS`; os D1–D14 não mudam e a troca de versão herda `seenAt` e feedback); o card na UI fica para a parte D
- [x] UI de Forecast nas telas existentes e gestão de recorrências (parte D — ADR-0033)
  - fatia A (entregue na branch): BFF do Forecast e das recorrências, componente `ProjectedValue` (Estimado/Confirmado/Parcial), "Próximos eventos" da Home, projeção do mês e próxima receita na Análise, estimativa da próxima fatura e das futuras em Cartões;
  - fatia B: página `/recurring` (abas Despesas/Receitas; Para revisar, Confirmadas e Ignoradas; confirmar, ignorar com desfazer, ajustar e voltar ao detectado; "Atualizando estimativas…"), item em M-More, ação e evidências do D15 no detalhe do insight, testes Vitest + axe e E2E `forecast.spec.ts` com os valores exatos do `forecast-v1` nas 5 larguras
- [x] cash flow forecast
- [x] bill forecast
- [x] freshness
- [x] uncertainty

**M9 completo** (Sessão 7.1). Handoff para a 7.2 no ADR-0030, seção "Para a Sessão 7.2":
- disponível: porta `ForecastReader` (`cashFlow`, `monthlySavingCapacity`, `recurringSeries`, `current`,
  `state`), evento `forecast.snapshot-updated`, API e o componente `ProjectedValue`;
- lacunas: virada de mês das recorrências (decisão de produto pendente), faixa por parte, fechamento × vencimento em
  `/upcoming`, enums como `string` no OpenAPI, só recorrência mensal, D15 sem E2E;
- E2E completo não rodado nesta parte por falta de disco: só `forecast.spec.ts` passou; rodar `pnpm e2e --workers=1`
  antes do merge.

## M10 Goals

- [x] modelo `goals-v1` (Sessão 7.2, parte A — ADR-0034):
  - progresso = saldo do destino dedicado (fato); aportes e resgates = `TRANSFER` `POSTED` do destino; destino tipado
    (`ACCOUNT` no v1, `INVESTMENT_POSITION` preparado para o Open Finance);
  - objetivo de reserva espelha o Reserve (contas `SAVINGS` ativas, máx. 1, nenhum outro objetivo usa `SAVINGS`);
  - previsão `ESTIMATED` = plano a partir do mês seguinte, faixa pelo ritmo real, confiança com a capacidade do
    Forecast; sem Forecast pronto, só fatos;
  - `SimulationEngine` puro (aporte, prazo, redução de categoria), efêmero, `SIMULATED`; "Aplicar" só muda o plano;
  - eventos `goal.created|plan-changed|status-changed` sem consumidor no M10
  - adiado: insight acionável D16 (05.13 §9); dívida: "Marcar como transferência" para aportes importados dos dois
    lados
- [x] objetivos e aportes no backend (parte B — ADR-0035):
  - módulo `goals`: agregado `Goal` (plano + estado), destino tipado, origem, status idempotentes, `V22`
    (exclusividade do destino e reserva única por índice parcial);
  - progresso e histórico lidos do destino pela porta `GoalBalanceSource` (saldo do Overview + transações), sem cópia;
  - `POST .../contributions` cria a `TRANSFER` pelo `CreateTransaction` com `Idempotency-Key`; histórico e aportes
    recentes derivados;
  - eventos `goal.*` pela outbox; ArchUnit (ninguém depende de `goals`); suíte de isolamento; OpenAPI e `schema.d.ts`
- [x] previsão de conclusão e simulação (parte C — ADR-0036; adendos ADR-0020, 0021, 0024, 0031):
  - `forecast` (Estimado) no objetivo sobre o `ForecastReader`: central pelo plano, faixa pelo ritmo real,
    confiança com a capacidade de aporte; sem Forecast pronto, só fatos; resumo com peso na renda e "no prazo";
  - `SimulationEngine` puro (`POST /goals/{id}/simulations`, até 5 cenários; aporte, prazo, redução de categoria)
    e prévia do D-CreateGoal (`POST /goals/simulations/preview`: já guardado, alvo sugerido da reserva = 6 × despesa
    média), sempre `SIMULATED` e sem gravar nada;
  - `GET /goals/{id}/projection`: guardado real (fato) + projeção (estimado) + prazo;
  - médias pela porta do Analytics com a janela do Health (teste de paridade);
  - `CREATE_SAVINGS_GOAL` disponível no D1/D2 com a sugestão (300/1.800/mar 2027 no 09-dados), sem trocar de versão;
  - sem rebuild nem runbook (nada é projetado nem gravado)
- [x] UI de Objetivos (parte D — ADR-0037):
  - BFF `/api/bff/goals/*` (lista, criação, detalhe, edição, plano, status, aportes com `Idempotency-Key`, simulação,
    prévia, projeção), `contract.ts` com os enums e limites e teste de drift;
  - `/goals` (métricas, faixa de simulação sem ✦, Goal Cards, filtro por status, aportes recentes, toast do
    D-GoalCreated), `/goals/:id` (progresso, previsão Estimada, gráfico real + projeção + prazo com "Ver dados",
    aportes, Simulation Card com plano/mínimo/outro valor e "Aplicar" com confirmação, drawer "Registrar aporte"),
    `/goals/new` (tipo, conta do objetivo, origem do insight/Health, prévia Simulada ao vivo) e `/goals/:id/edit`
    (status com confirmação);
  - placeholders liberados: "Criar objetivo de economia" (Insights), passo da reserva (Health), "Objetivos" (M-More),
    "Ações rápidas" da Home; "Perguntar ao Copilot" segue "Em breve" (M11);
  - Vitest/RTL + axe e testes do BFF; E2E `goals.spec.ts` (Health → reserva → aporte → previsão → simular → aplicar,
    sem efeito em saldo, fluxo e Health) nas 5 larguras
- [x] goal
- [x] contributions
- [x] progress
- [x] forecast
- [x] simulation

**M10 completo** (Sessão 7.2; ADR-0034 a ADR-0037). Para o M11 (Copilot):
- disponível como fonte determinística: objetivos com progresso (fato), previsão (Estimado) e simulação (Simulado) por
  `GET /goals`, `/goals/{id}`, `/projection` e `POST /simulations` — o Copilot pode explicar e simular, nunca gravar;
  "Aplicar" continua uma ação da pessoa (`PUT /plan`) com confirmação;
- placeholders do Copilot ainda "Em breve": "Perguntar ao Copilot" no detalhe do insight e nas Ações rápidas da Home;
- dívidas: D16 (insight acionável), "Marcar como transferência", "Ver todos" dos aportes recentes, redução de categoria
  na UI de simulação, consumidores dos eventos `goal.*`, caixinhas do Open Finance (`INVESTMENT_POSITION`).

## M11 Copilot

- [x] consents (Sessão 8.1 Parte 0, ADR-0038): módulo `consents` (V23, `consents` + `consent_audit` só de inserção),
  `GET`/`PUT /workspaces/{id}/consents[/{purpose}]`, eventos `consent.granted`/`consent.revoked`, porta
  `ConsentReader` para o pipeline do Copilot; tela `/profile/consents` (submenu de Perfil, demais seções "Em breve")
  e item "Consentimentos" do M-More
  - dívidas: `INSIGHTS_GENERATION` adiado (Insights e Health seguem sem consentimento; a tela mostra 2 dos 3 switches
    do layout); consumidor de `consent.revoked` que apaga o histórico do Copilot (Sessão 8.2, com o histórico);
    cabeçalho do submenu de Perfil sem nome/e-mail (Dados pessoais é "Em breve"); no submenu, "Contas" já leva a
    `/accounts` (a rota existe), os demais itens seguem "Em breve"
- [x] modelo de IA (Sessão 8.1 Parte A, ADR-0039): Anthropic + SDK Java oficial; Haiku 5.5 (intenção) e Sonnet 5.5
  (resposta); porta `AiGateway` com adapter simulado padrão no local/CI; Resilience4j; minimização (só comerciante
  normalizado como texto de origem, pseudônimos locais); 10 intenções e tools somente leitura por lista fechada
  (adendos ADR-0022/0031/0034); valores por referência `{{v:ref}}` + modo seguro; quotas provisórias 10/dia, 100/mês,
  1M tokens/mês, 3/min; histórico 90 dias (8.2)
  - pendente de produção: DPA (retenção, ZDR, cláusulas de transferência internacional) e PIA (05.12)
- [x] AI Gateway (Sessão 8.1 Parte B, ADR-0039 §2): módulo `copilot`, porta `AiGateway` com `AnthropicAiProvider`
  (SDK 3.7.0, um cliente por papel, sem retries do SDK) e `SimulatedAiProvider` (padrão local/CI, modos de falha);
  `ResilientAiGateway` com Resilience4j (retry só em 429/5xx/conexão, circuit breaker `copilot-ai`, bulkhead 16);
  `CopilotAdmission` (Workspace → ligado → consentimento → rate limit → quota); runbook `docs/runbooks/copilot.md`
- [x] context builder (Sessão 8.1 Parte C, ADR-0039 §5): contexto `context-v1` (valores com ref, rótulo, unidade,
  `FACT`/`ESTIMATED`/`SIMULATED`, período e fonte; dinheiro como decimal), `dataQuality`, teto de 12K tokens
  estimados, pseudônimos `conta_N`/`cartao_N`/`objetivo_N`/`insight_N`/`categoria_N` só em memória; catálogo da
  intenção (categorias + alvos pseudonimizados)
- [x] tools (Parte C, ADR-0039 §4): 10 adapters somente leitura sobre a lista fechada de casos de uso (+
  `ListCategories`, adendo), ArchUnit fechado; dados ausentes → `INSUFFICIENT_DATA` sem chamar o modelo
- [x] structured output (Parte C, §6): esquemas `intents-v1`/`answer-v1` e prompts `intent-v1`/`answer-v1`
  versionados com SHA-256; V25 `ai_usage.prompt_version`/`schema_version`; leitura estrita da saída
- [x] claim validation (Parte C, §7): `claims-v1` (refs `{{v:REF}}`, nenhum dígito, número por extenso, `R$` ou `%`
  fora de referência), regenera uma vez e depois modo seguro; confiança `confidence-v1`; caso de uso interno
  `AskCopilot` + `CopilotPipeline` com orçamento de 30 s; endpoint de diagnóstico só no perfil `local`
  - handoff da Sessão 8.2 no ADR-0039 ("Para a Sessão 8.2"): API de conversa, histórico, suíte de avaliação, telas,
    smoke test com a chave real; a continuidade da conversa não pode mandar ao modelo os nomes renderizados
- [x] usage ledger (Parte B, ADR-0039 §10): V24 `ai_usage` (só metadados, custo USD `numeric(19,6)` pela tabela
  `pricing-2026-11`), quotas provisórias contadas no ledger no fuso de negócio, token bucket por pessoa no Redis (Lua),
  métricas `shf.copilot.*`
  - dívidas (até o M12): quota não atômica com o registro e token do balde gasto antes da quota (adendo ADR-0039
    §10); smoke test com a chave real (o SDK 3.7.0 manda `Authorization: Bearer`); DPA e PIA
- [x] API de conversa (Sessão 8.2 Parte D, ADR-0040; adendos ADR-0039 §4/§9/§13): `POST /copilot/questions`
  (`Idempotency-Key` no Redis sem conteúdo, contexto de entrada INSIGHT/INVOICE/INSTALLMENT_PLAN/GOAL validado antes
  do rate limit, continuidade só com as 3 perguntas anteriores e as intenções, resposta em segmentos tipados, quadro de
  fatores do sistema — fatura D11 e saúde —, "Dados usados" e avisos); `GET /copilot/usage`; histórico com
  `COPILOT_HISTORY` (V26 `copilot_conversations`/`copilot_messages`/`copilot_feedback`), listar/abrir/apagar
  conversas, retenção de 90 dias por mensagem (job diário), consumidor de `consent.revoked` com inbox e DLQ;
  feedback só com códigos; endpoint de diagnóstico removido; prompts `intent-v2`/`answer-v2`; suíte de isolamento
  - dívidas: `schema.d.ts` e BFF na Parte E; `intent-v2`/`answer-v2` sem avaliação contra o modelo real até a Parte F;
    o quadro de fatores só cobre fatura (D11) e saúde; replay de pergunta efêmera já respondida perde a resposta
    (409, sem cache de conteúdo); janela entre revogação e gravação coberta só pela retenção
- [x] telas do Copilot (Sessão 8.2 Parte E, ADR-0041; adendos ADR-0021/0024/0029/0037): `/copilot` (conversa
  efêmera na própria tela) e `/copilot/{id}` (com histórico), AI Response só por segmentos tipados (sem HTML), quadro
  de fatores, "Como cheguei a isso", avisos, Confiança, Dados usados, ações, Copiar e feedback; todos os estados do
  Copilot; uso "X de Y perguntas" (quota provisória); BFF `/api/bff/copilot/*` com `Idempotency-Key` e
  `Retry-After`; "Perguntar ao Copilot" na Home, no insight (`ASK_COPILOT` disponível também nos já gravados), no
  cabeçalho de toda fatura e no parcelamento; E2E com o adapter simulado
  - dívidas: "Conhecer o plano" e o nome do plano no M12; o simulado não reconhece "Como estou este mês?" (vira fora
    do escopo no local/E2E); E2E do Copilot longo (~3–5 min) e sensível à primeira compilação do `next dev`; alguns
    testes de view do Copilot com timeout explícito por carga
- [x] evaluation e observabilidade (Sessão 8.2 Parte F, ADR-0042; adendo ADR-0039 §3): golden set `golden-v1`
  (casos sintéticos por intenção, 09-dados-de-exemplo, adversariais, dados insuficientes e continuidade) no build
  normal com saída roteirizada; perfil `copilot-eval` + workflow manual `copilot-eval.yml` contra o modelo real, com
  relatório sem conteúdo; painel Grafana e alertas Prometheus versionados em `infrastructure/observability/copilot/`;
  custo por Workspace e por dia pelo ledger; revisão de segurança e privacidade (validação da pergunta antes da
  admissão e corpo até 16 KB, revogação apaga só até o evento, `baseUrl` do provedor restrito)
  - **bloqueante de produção:** destinatário de Pix/TED/DOC ainda vai ao modelo como comerciante; pseudonimizar como
    `destinatario_N` exige marcar a origem de transferência na projeção da analytics (migration, adendo ADR-0013;
    PR próprio, aprovado em 2026-11-12)
  - dívidas: rodar o golden set real e calibrar os limiares do runbook de avaliação e dos alertas; criar o secret
    `ANTHROPIC_API_KEY` no repositório; scan de dependências e CVEs (`anthropic-java`, Resilience4j, Jackson 2 do SDK);
    feedback do Copilot e `ai_usage` sem exclusão na revogação (só metadados; retenção do ledger no M13); entre a
    revogação e o consumidor, reconceder o histórico mostra conversas antigas por instantes; DPA e PIA
- **M11 completo** (Sessões 8.1 e 8.2; ADR-0038 a ADR-0042). Para o M12: planos e entitlements substituem as quotas
  provisórias (`shf.copilot.quota.*`), "Conhecer o plano" e o nome do plano na UI, calibração de preços e quotas pelo
  custo observado no golden set real. Para o M13/M14: retenção do `ai_usage`, staging com a chave real, painel e
  alertas no ambiente, DPA e PIA.

## M12 Monetization

- [ ] plans
- [ ] subscriptions
- [ ] entitlements
- [ ] usage limits
- [ ] billing abstraction

## M13 Hardening

- [ ] security
- [ ] privacy
- [ ] deletion/export
- [ ] rate limits
- [ ] backup
- [ ] restore test
- [ ] resilience
- [ ] performance

## M14 Release

- [ ] staging
- [ ] production
- [ ] dashboards
- [ ] alerts
- [ ] runbooks
- [ ] release verification
