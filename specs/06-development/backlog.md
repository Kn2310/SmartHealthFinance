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
- [ ] estimativa da próxima fatura e das faturas futuras: M9 (ADR-0025 §10, ADR-0029 §4)

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
- [ ] motor de Forecast e recorrências (parte B)
- [ ] API de Forecast e `/recurring-expenses` (parte C)
- [ ] UI de Forecast nas telas existentes e gestão de recorrências (parte D)
- [ ] cash flow forecast
- [ ] bill forecast
- [ ] freshness
- [ ] uncertainty

## M10 Goals

- [ ] goal
- [ ] contributions
- [ ] progress
- [ ] forecast
- [ ] simulation

## M11 Copilot

- [ ] AI Gateway
- [ ] context builder
- [ ] tools
- [ ] structured output
- [ ] claim validation
- [ ] usage ledger
- [ ] evaluation

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
