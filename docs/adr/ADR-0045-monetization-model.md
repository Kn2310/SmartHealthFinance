# ADR-0045 — Modelo de monetização: planos, entitlements e limites de IA (M12, Parte A)

## Status

Accepted (2026-11-26). Decisões de produto, preço, cache e privacidade e o texto completo aprovados pelo usuário em
2026-11-26. Limites e preços seguem **provisórios** até a calibração pelo custo real.

## Context

A spec `05.4.1` define três planos (Free, Plus, Pro), monetização como módulo de primeira classe e entitlements
checados na Application Layer, mas não define: o titular da assinatura, as chaves de entitlement, o que é "Insights
limitados", os números de "Copilot limitado" e "≈ 5× Plus", o ciclo de vida, o trial, o plano dos Workspaces
existentes, os preços nem os contratos de erro.

O que já existe:

- **Usage ledger** `ai_usage` (V24/V25, ADR-0039 §10): só metadados, custo em USD `numeric(19,6)` pela tabela
  `pricing-2026-11`. Conta como pergunta a chamada de intenção registrada, exceto falhas do provedor.
- **Quotas provisórias** (`shf.copilot.limits.*`, `CopilotLimits`): 10 perguntas/dia, 100/mês, 1M tokens de entrada/mês
  por Workspace, no fuso de negócio (`shf.overview.zone`, `America/Sao_Paulo`), por `QuotaUsage`; token bucket de
  3/min por `(workspace, user)` no Redis (Lua), falha fechada para o Copilot.
- **Admissão** (`CopilotAdmission`): membership → ligado → consentimento → contexto → rate limit → quota.
- **Uso** `GET /copilot/usage` (ADR-0040 §8) com `provisional: true`; a UI mostra "Uso no mês: X de Y perguntas", sem
  nome de plano e sem "Conhecer o plano" (ADR-0041 §3).
- **Insights**: geração por eventos (ADR-0022), lista em ordem fixa (ADR-0021 §3), destaques, relacionados, detalhe,
  contagens dos chips e da navegação (ADR-0023/0024). O ADR-0023 já prevê o corte do Free **na leitura**.
- **Custo real**: `docs/evaluations/copilot-real-validation-2026-11.md` está **pendente**. Vale a estimativa do
  ADR-0039: US$ 0,02–0,03 por pergunta. **Todos os limites e preços abaixo são provisórios.**
- Staging (ADR-0044): o Caddy publica só o frontend e o IdP; a API não é pública (webhooks: Parte C).

## Decision

### 1. Titular da assinatura: o Workspace

- A assinatura pertence ao **Workspace**; quem gerencia (assinar, trocar, cancelar) é o `OWNER` (ADR-0003). O
  pagador registrado é o `userId` do `OWNER` que assinou.
- Os limites do Copilot continuam por Workspace (ledger e quota atuais); o rate limit continua por pessoa.
- Com mais membros (pós-MVP), todos usam o plano do Workspace e dividem a quota; só papéis com permissão de
  cobrança gerenciam. Novos papéis exigem revisão do ADR-0003.

### 2. Catálogo `plans-v1`

Catálogo versionado em recurso do backend (mesmo padrão de `pricing-2026-11`), com chaves estáveis. Workspace sem
assinatura vigente = **Free** implícito (não há linha de assinatura para o Free).

| Chave | Tipo | Free | Plus | Pro |
|---|---|---|---|---|
| `COPILOT` | booleano | não | sim | sim |
| `copilot.dailyQuestions` | inteiro | 0 | 10 | 50 |
| `copilot.monthlyQuestions` | inteiro | 0 | 100 | 500 |
| `copilot.monthlyInputTokens` | inteiro | 0 | 1.000.000 | 5.000.000 |
| `copilot.ratePerMinute` | inteiro | 0 | 3 | 6 |
| `INSIGHTS_FULL` | booleano | não | sim | sim |
| `insights.visibleLimit` | inteiro | 3 | ilimitado | ilimitado |
| `insights.riskAlertBonus` | inteiro | 1 | — | — |
| `OPEN_FINANCE` | booleano | não | **não (reservado)** | **não (reservado)** |

- "Pro ≈ 5× Plus" = 5× nas perguntas por dia, por mês e nos tokens; o rate limit dobra (é anti-abuso, não volume).
- `OPEN_FINANCE` fica no catálogo para o contrato não mudar quando o Open Finance entrar; no MVP é falso em todos os
  planos e a UI mostra "Em breve" (estado Unavailable, `05-states`), nunca paywall.
- Núcleo financeiro (contas, transações, cartões, importação CSV/OFX/manual, Análise, Health, Forecast, Objetivos,
  Quando o Open Finance existir, uma nova versão do catálogo o liga no Plus e no Pro, como manda a `05.4.1`.
  Consentimentos) **não tem entitlement**: está em todos os planos e nunca consulta o módulo.
- Créditos avulsos: **fora do MVP** (aprovado).

### 3. "Insights limitados" no Free

Regra determinística aplicada só na **leitura** (`InsightQueries`), sem tocar na geração (ADR-0022), nos detectores, na
pontuação nem no Health:

- O Free vê os **3 primeiros insights ativos** na ordem da lista do ADR-0021 §3 (Importantes primeiro, depois a
  pontuação), calculados sobre todos os ativos, antes dos filtros.
- **Mais 1 alerta de risco** (`insights.riskAlertBonus`), além dos 3: se **nenhum** alerta de risco estiver entre os 3,
  entra o primeiro alerta de risco da lista. Alerta de risco = D14 (`detector = INVOICE_DUE` com `subject` terminado em
  `|OVERDUE`, ADR-0028 §4) ou D15 (`BALANCE_MAY_GO_NEGATIVE`, ADR-0032 §3). Um D14 `DUE_SOON` não é alerta de risco.
  No máximo 4 visíveis (aprovado).
- `hidden` = ativos − visíveis (bônus incluído).
- Os visíveis têm detalhe completo (evidências, "Como comparamos", ações): a explicabilidade não é cortada.
- Os ocultos não expõem título nem valor; a resposta diz só quantos estão ocultos e o plano necessário.
- A ação `ASK_COPILOT` de um insight visível continua na resposta; no Free o clique leva ao paywall do Copilot (a
  forma visual é do ADR de UI da Parte D).
- Destaques da Home, relacionados, contagens dos chips e da navegação usam só os visíveis. Dispensar um visível
  revela o próximo (efeito da ordem fixa).
- Detalhe ou ação (`seen`, `dismiss`, `feedback`) num oculto → `403 ENTITLEMENT_REQUIRED`.
- Ao assinar, todos aparecem na hora (nada é regerado). Ao voltar ao Free, nada é apagado.

### 4. Limites e créditos de IA

- `CopilotLimits` deixa de ler `shf.copilot.limits.*` e passa a ler os entitlements do Workspace (§8). O record
  hoje rejeita zero: no Free a admissão para em `COPILOT = false` e nunca o constrói (ou a Parte B muda a validação).
  A flag `shf.copilot.enabled` continua (desliga o Copilot para todos).
- Contagem: **inalterada**, no ledger `ai_usage`, com a regra do adendo de 2026-11-10 (falha do provedor não conta).
- Janelas: dia e mês **de calendário** no fuso `America/Sao_Paulo`, como hoje (não o ciclo de cobrança). Num upgrade,
  o uso do mês continua contando contra o novo limite, maior.
- As dívidas do ADR-0039 §10 (quota não atômica, token do balde antes da quota) seguem aceitas; o teto do rate limit
  por plano limita o excesso.
- Créditos avulsos: fora do MVP (aprovado).

### 5. Ciclo de vida da assinatura

| Estado | Entitlements | Transições |
|---|---|---|
| `TRIALING` | do plano do trial | → `ACTIVE` (assinou) · → `EXPIRED` (fim do trial sem assinatura) |
| `ACTIVE` | do plano | → `PAST_DUE` (cobrança falhou) · → `CANCELED` (cancelou) |
| `PAST_DUE` | do plano, durante a **carência de 7 dias** (aprovada) | → `ACTIVE` (pagou) · → `EXPIRED` (fim da carência) |
| `CANCELED` | do plano até `currentPeriodEnd` | → `EXPIRED` (fim do ciclo) · → `ACTIVE` (reativou antes do fim) |
| `EXPIRED` | Free | terminal (nova assinatura cria outra linha) |

- **Upgrade** (Free → Plus/Pro, Plus → Pro): imediato; a prorrata é calculada pelo provedor (Parte C).
- **Downgrade** (Pro → Plus): agendado para o fim do ciclo. A assinatura guarda o plano agendado
  (ex.: `pendingPlan`; Parte B).
- `PAST_DUE` não vai direto a `CANCELED`; `TRIALING → ACTIVE` depende do provedor (Parte C).
- **Cancelamento**: no fim do ciclo pago, sem reembolso proporcional no MVP.
- **Ao rebaixar para o Free**: nenhum dado é apagado.
  - Copilot: novas perguntas bloqueadas; o histórico (se houver `COPILOT_HISTORY`) continua listável, legível e
    apagável até a retenção de 90 dias (é dado da pessoa; ADR-0040 §6).
  - Insights: só a exposição volta ao limite do §3.
- O estado vem do provedor (webhook e reconciliação, Parte C); o domínio valida as transições e as audita.

### 6. Trial e Workspaces existentes

- Todos os Workspaces existentes começam no **Free** (só há dados fictícios de local, CI e staging). Migração
  idempotente: nada a gravar, porque o Free é implícito.
- Trial (aprovado): **10 dias de Plus, uma vez por Workspace, opt-in** pelo paywall do Copilot ou dos Insights
  ("Experimentar o Plus por 10 dias"), sem cartão e sem provedor; só o `OWNER` inicia; no fim volta ao Free (job
  diário, idempotente).
- No staging, o seed pode pôr uma pessoa de demonstração no Plus pela troca manual de plano (suporte, auditada,
  Parte E).

### 7. Preços (provisórios, BRL)

Base: US$ 0,03 por pergunta (teto da estimativa do ADR-0039), câmbio de referência R$ 5,80 (a confirmar), taxa do
meio de pagamento ~5% e tributos ~6% (a confirmar com a contabilidade), infraestrutura ~R$ 2 por Workspace ativo/mês
(estimativa grosseira; o staging custa ~US$ 84/mês).

| Plano | Preço/mês | Custo de IA no teto | Líquido após taxa e tributo | Margem no teto | Margem com 30% de uso |
|---|---|---|---|---|---|
| Free | R$ 0 | R$ 0 | — | −R$ 2 (infra) | −R$ 2 |
| Plus | **R$ 29,90** | R$ 17,40 (100 × US$ 0,03) | R$ 26,61 | R$ 7,21 | R$ 19,39 |
| Pro | **R$ 79,90** | R$ 87,00 (500 × US$ 0,03) | R$ 71,11 | −R$ 17,89 | R$ 43,01 |

- O Plus cobre o teto; o Pro cobre até ~79% de uso do teto (~397 perguntas/mês).
- Só mensal no MVP; anual e cupons fora.
- Valores em `numeric(19,4)` / `BigDecimal`; a assinatura guarda o preço vigente na contratação.
- Valores aprovados como **provisórios** (2026-11-26). Recalibrar quando o golden set real for executado (adendo datado).

### 8. Onde e como os entitlements são checados

- Porta `EntitlementReader` na Application Layer do módulo `monetization`
  (`Entitlements current(WorkspaceId)`): snapshot imutável com o plano, a versão do catálogo, o estado e os limites.
  Os módulos consumidores (só `copilot` e `insights` no MVP) dependem só da porta, nunca de entidades do módulo.
- Resolução determinística: assinatura vigente (estado + datas) + catálogo `plans-v1` → entitlements.
- Ordem no Copilot: membership → idempotência (ADR-0040 §4) → ligado → **`COPILOT`** → consentimento → contexto →
  rate limit (valor do plano) → quota (valores do plano). O paywall vem antes do pedido de consentimento. O 403
  libera a chave de idempotência, como os demais erros (ADR-0040 §5); um replay já respondido segue a regra atual.
- Cache (aprovado): **sem cache no MVP** — uma leitura indexada por pedido, como `ConsentReader` e `WorkspaceAccessGuard`
  (ADR-0003); a porta permite pôr Redis atrás depois, invalidado por `entitlements.changed`.
- Falha do módulo (exceção, banco indisponível na leitura da assinatura):
  - Copilot: falha fechada → `503 COPILOT_UNAVAILABLE` (código existente);
  - Insights: aplica a regra do Free (§3), sem erro;
  - núcleo financeiro: não consulta o módulo, logo não é afetado.
- ArchUnit (Parte B): `monetization` não depende de módulos financeiros; consumidores só via porta; nenhuma regra de
  plano no frontend (o frontend lê o snapshot da API).

### 9. Eventos (outbox, consumidores idempotentes)

- `subscription.changed`: `{eventId, workspaceId, subscriptionId, plan, status, previousStatus, currentPeriodEnd,
  catalogVersion, occurredAt}`. Sem e-mail, valor ou dado de pagamento.
- `entitlements.changed`: `{eventId, workspaceId, plan, catalogVersion, effectiveAt}`.
- MVP sem consumidor obrigatório (sem cache); servem à auditoria e a notificações e ao cache futuros. Todo consumidor
  usa a inbox `ProcessedEvents`.
- Auditoria: `subscription_audit` só de inserção (origem `WEBHOOK`, `RECONCILIATION`, `SUPPORT`, `TRIAL`, `SYSTEM`),
  no padrão de `consent_audit`.

### 10. Erros e contratos (só aditivos)

- Novo `403 ENTITLEMENT_REQUIRED` `{code, entitlement, requiredPlan}` — Copilot no Free, insight oculto.
- `429 COPILOT_QUOTA_EXCEEDED` continua, com os campos novos `window` (`DAY`/`MONTH`/`TOKENS`) e `plan`.
  `PLAN_LIMIT_REACHED` não é criado (seria sinônimo).
- `CopilotUsage` ganha `plan`; `provisional` passa a significar "limites do catálogo ainda não calibrados" (fica
  `true` até a calibração). No Free, `plan = FREE`, limites 0 e o uso já contado no ledger.
- Lista de insights ganha `visibility: {limited, visible, hidden, requiredPlan}`.
- Novos recursos (contrato em ADR próprio na Parte B): `GET /plans` (catálogo e preços),
  `GET /workspaces/{id}/subscription` (plano, estado, ciclo, entitlements e uso), e na Parte C checkout, portal e
  cancelamento.
- Dados (Parte B, migrations só aditivas, sem backfill porque o Free é implícito): `subscriptions` (Workspace, plano,
  estado, versão do catálogo, ciclo, `pendingPlan`, preço contratado `numeric(19,4)`, origem, IDs externos; um trial
  por Workspace garantido por índice único) e `subscription_audit` só de inserção. O catálogo fica em recurso
  versionado.

### 11. Privacidade e fiscal

- Dados no app: `workspaceId`, `userId` do pagador, plano, estado, datas do ciclo, preço contratado, IDs externos do
  provedor (cliente, assinatura). **Nunca** cartão, CVV, dados bancários, CPF ou endereço.
- CPF/CNPJ e endereço, se o provedor ou a nota exigirem, são coletados **no checkout hospedado do provedor** e ficam
  só lá (aprovado; o provedor é escolhido na Parte C).
- E-mail enviado ao provedor para o recibo: o do IdP, no início do checkout.
- Bases legais: execução de contrato (art. 7º, V) e cumprimento de obrigação legal (art. 7º, II) nos registros
  fiscais.
- Retenção: assinatura e auditoria por 5 anos após o fim (prazo tributário e de defesa), depois apagadas
  (aprovado; a validar com jurídico e contabilidade).
- Nota fiscal (NFS-e): fora do M12, decidida antes da cobrança real (M14).
- RIPD, registro de tratamento e checklist do DPA ganham o tratamento "Cobrança e assinatura" e o provedor como
  suboperador (Parte E).

## Alternatives

- **Assinatura do usuário:** a quota e o ledger são por Workspace; o plano do usuário teria de ser projetado em cada
  Workspace, e um membro sem plano num Workspace pago seria ambíguo. Rejeitada.
- **Insights Free por detector** (só Gastos e Saúde): esconde Cartões e Previsão inteiros, inclusive alertas de risco.
- **Insights Free só "Importantes":** quase todo o valor fica no Free; o paywall perde sentido.
- **Insights Free sem detalhe:** corta a explicabilidade (`principles.md`).
- **Cache Redis já no MVP:** acrescenta invalidação e um ponto de falha para economizar uma leitura indexada.
- **Janela de quota pelo ciclo de cobrança:** diverge da UI ("no mês") e exige o ciclo no Copilot.
- **Créditos avulsos:** exigem compra avulsa, saldo e estorno; ficam para depois.

## Consequences

- Positivas: um ponto de checagem (porta), contratos aditivos, quotas sem dívida de configuração, o núcleo financeiro
  isolado do módulo.
- Negativas: limites e preços provisórios até o golden set real; o Pro pode dar prejuízo num uso extremo; o trial sem
  cartão permite repetir com contas novas (o cadastro é por IdP; risco aceito no MVP). Até 10 dias × 10 perguntas
  por dia ≈ US$ 3 por trial no teto.

- Adendos datados previstos nos ADRs aceitos, cada um na parte que implementa a mudança:
  - Parte B: ADR-0039 §10 (quotas por plano, rate limit por plano); ADR-0040 §4 (checagem de `COPILOT` antes do
    consentimento, `403 ENTITLEMENT_REQUIRED`) e §8 (`plan`, novo sentido de `provisional`, `window` no 429);
    ADR-0023 (`visibility`, 403 no oculto, contagens só dos visíveis); ADR-0024 (contagens da navegação).
  - Parte C: ADR-0044 (rota pública só para `/webhooks/billing`).
  - Parte B: ADR-0021 §3 (o corte do Free deixa de ser futuro).
  - Parte D: ADR-0041 §3 (uso do plano e "Conhecer o plano") e §6 (`ASK_COPILOT` no Free leva ao paywall).
- Divergência com a spec, registrada: `05.4.1` põe Open Finance no Plus e no Pro; no MVP ele não existe e
  `OPEN_FINANCE` é falso em todos os planos (§2), então o preço do §7 não inclui Open Finance.
  `05.4` lista um "entitlements cache" no Redis; o MVP fica sem cache (§8). A `05.6` cita `/subscriptions` e `/usage` na raiz; o contrato da Parte B decide os caminhos sob
  `/workspaces/{id}` (regra do 404, ADR-0003).

## Security / Privacy

- Entitlements só no backend; o JWT não carrega plano (`05.5`).
- Nenhum dado de pagamento no app; nada pessoal em eventos, logs, métricas ou traces.
- `ENTITLEMENT_REQUIRED` só depois da membership (o 404 de Workspace alheio continua).

## Financial Integrity

- Monetização só limita recursos premium. Saldos, transações, faturas, Health e Forecast iguais em todos os planos.
- Insights: a geração não muda; o Free só vê menos.
- Preços em `numeric(19,4)`; custo de IA segue em `numeric(19,6)` USD.

## Operational Impact

- Métricas (Parte E): assinaturas por estado, bloqueios por entitlement (`shf.entitlement.denied{entitlement}`),
  webhooks e reconciliação.
- Troca manual de plano para suporte, auditada (runbook na Parte E).

## Related Specs

`05.4.1`, `05.13` §13, `05.8` (Planos, cost control), `05.6` (`/subscriptions /usage`, Billing), `05.7`, `05.12`;
ADR-0003, ADR-0021–0024, ADR-0028, ADR-0039 §10, ADR-0040 §4/§8, ADR-0041 §3, ADR-0044.

## Date

2026-11-26
