# RIPD — Relatório de Impacto à Proteção de Dados Pessoais (PIA)

| Campo | Valor |
|---|---|
| Sistema | Smart Health Finance (plataforma e Financial Copilot) |
| Versão | 1 (Fase 9, Parte F) |
| Data | 2026-11-25 |
| Status | **Proposta**: pendente de aprovação do controlador |
| Controlador | _a preencher pelo controlador_ |
| Encarregado (DPO, LGPD art. 41) | _a designar pelo controlador_ |
| Base normativa | LGPD (Lei 13.709/2018), arts. 5, 6, 7, 18, 20, 33, 37, 38, 46 e 48; spec `05.12` |
| Revisão | antes do primeiro dado real em produção (M14) e a cada mudança relevante de tratamento |

> Este documento é técnico. As **bases legais, os prazos de retenção e as conclusões precisam de validação jurídica**
> e da aprovação do controlador. Itens marcados como **proposta** não estão implementados.

## 1. Contexto e escopo

O Smart Health Finance é um app de finanças pessoais. A pessoa registra ou importa contas, transações, cartões e
objetivos. O sistema calcula análises, um score de saúde financeira, insights e previsões, e oferece um assistente
(Financial Copilot) que explica esses números com um modelo de IA de terceiro (Anthropic).

- **Escopo:** todo tratamento de dado pessoal da plataforma, com destaque para o Copilot (IA) e a transferência
  internacional, os tratamentos de maior risco.
- **Fora do escopo:** Open Finance (não implementado) e planos e pagamentos (M12, não implementados).
- **Estado atual:** não há produção. O staging (ADR-0044) recebe **só dados fictícios** (05.11). Este RIPD é
  pré-condição para o primeiro dado real.

## 2. Titulares e dados tratados

Titular: a pessoa usuária do app (adulta, conta individual, um Workspace pessoal por pessoa no MVP, ADR-0003).
Inventário detalhado: `registro-de-tratamento.md`.

| Categoria (05.12) | Dados | Onde |
|---|---|---|
| Identificação (Personal) | e-mail, nome de exibição, `oidc_subject` e issuer; no IdP: hash de senha, segredo TOTP, sessões e eventos de login (se ligados; hoje não configurados no realm) | `users`; Keycloak (ADR-0043) |
| Financeiro (Financial) | contas (nome, instituição), transações (valor, data, descrição livre, comerciante), cartões (apelido, instituição, **só os 4 últimos dígitos**), faturas e parcelas, objetivos, linhas de importação | `accounts`, `transactions`, `credit_cards`, `card_*`, `goals`, `import_records` |
| Derivado (Derived) | projeções de análise, score de saúde e explicação, insights, previsões, regras de categorização | `analytics_*`, `health_snapshots`, `insights`, `forecast_*`, `category_rules` |
| Contexto de IA (AI Context) | contexto mínimo montado por pergunta: agregados, categorias, pseudônimos | **só em memória**, por requisição (ADR-0039 §3) |
| Conteúdo do Copilot | pergunta literal (até 500 caracteres), resposta renderizada, título da conversa | `copilot_*` (90 dias; ADR-0040) |
| Auditoria (Audit) | consentimentos e trilha de concessões e revogações; ledger de uso de IA (só metadados) | `consents`, `consent_audit`, `ai_usage` |

**Não tratados** (05.12, "Proibições"): CVV, número completo de cartão, senha bancária, senha de Open Finance e dados
sensíveis do art. 11. Dados financeiros não são sensíveis na definição legal, mas são tratados internamente como de
**alto risco** (05.12).

## 3. Finalidades e bases legais (**proposta, validar com o jurídico**)

| # | Tratamento | Finalidade | Base legal proposta (art. 7) |
|---|---|---|---|
| T1 | Cadastro e autenticação | criar a conta, autenticar, proteger contra força bruta | V (execução de contrato) |
| T2 | Registro e importação de dados financeiros | prestar o serviço contratado | V (execução de contrato) |
| T3 | Derivados (análise, saúde, insights, previsão) | funcionalidade central do produto | V (execução de contrato) |
| T4 | Copilot: uso dos dados para responder | explicar os números da pessoa com IA | **I (consentimento)**: opt-in, revogável, versionado (`COPILOT_DATA_USE`, ADR-0038) |
| T5 | Copilot: histórico de conversas | continuidade e consulta | **I (consentimento)**: `COPILOT_HISTORY`, revogado em cascata com T4 |
| T6 | Ledger de uso de IA (`ai_usage`) | quotas, custo, prevenção de abuso | IX (legítimo interesse; exige teste de balanceamento, art. 10) |
| T7 | Logs técnicos, métricas e auditoria de consentimento | segurança, operação e prova do consentimento (art. 8 §2) | IX (legítimo interesse); II (obrigação legal) para a guarda de registros de acesso a aplicações do Marco Civil da Internet (Lei 12.965/2014, art. 15), se aplicável (validar com o jurídico) |

T6 e T7 por legítimo interesse: minimização (sem conteúdo, sem `workspaceId` em métrica) e expectativa razoável da
pessoa. O teste de balanceamento formal fica com o jurídico (art. 10 §3: a ANPD pode pedir este RIPD).

## 4. Fluxos de dados

```text
pessoa ──HTTPS──► Caddy ─► BFF Next.js (cookie selado AES-256-GCM) ─► API (rede interna) ─► PostgreSQL
                    └────► Keycloak (identidade; e-mails pelo Mailpit no staging)
API ─► RabbitMQ (eventos só com ids e contagens) ─► consumidores (derivados)
API ─HTTPS──► api.anthropic.com (EUA): pergunta literal + até 3 perguntas anteriores + contexto mínimo pseudonimizado   [T4, transferência]
API ─► otel-lgtm local (métricas, logs e traces sem conteúdo) ─► Telegram (só texto do alerta)
```

## 5. Operadores e transferência internacional

| Operador | Papel | Dados | Local | Contrato |
|---|---|---|---|---|
| Anthropic | IA do Copilot (T4) | pergunta literal, contexto pseudonimizado, metadados técnicos | EUA (inferência `global`/`us`; sem região Brasil, ADR-0039 §1) | **DPA pendente**: `dpa-checklist.md` §1 |
| AWS (Lightsail, São Paulo) | hospedagem e snapshots | todos os dados do ambiente | Brasil (`sa-east-1`) | termos AWS + DPA: `dpa-checklist.md` §2 |
| Telegram | canal de alertas | texto do alerta (nome, prioridade, resumo, runbook) | fora do Brasil | sem dado pessoal: §3 do checklist |
| GitHub (Actions, GHCR) | CI, imagens | só código e dados **sintéticos**; o golden set é fictício | EUA | sem dado pessoal: §4 |
| Let's Encrypt | certificados | domínios e e-mail ACME do operador | EUA | sem dado de titular |

**Transferência internacional (art. 33):** só a Anthropic recebe dado de titular fora do Brasil. Mecanismo proposto:
**cláusulas-padrão contratuais da ANPD** (Resolução CD/ANPD nº 19/2024), no DPA ou anexadas a ele; validar com o
jurídico. Informar a pessoa da transferência no consentimento do Copilot e na política de privacidade (art. 9).

## 6. Medidas existentes

| Área | Medida | Evidência |
|---|---|---|
| Minimização para a IA | todo comerciante vai como `comerciante_N`; contas e cartões também como pseudônimos; nada de nome, e-mail, ids internos, dígitos de cartão ou descrição crua; contexto só em memória | ADR-0039 §3/§8 (adendo 2026-11-16); `CopilotPipelineTest`; grupo `privacy` do golden set |
| IA não é fonte de verdade | o sistema calcula, a IA explica; validação de claims bloqueia número sem fonte | ADR-0039 §7; `quality.claimLeaksToThePerson` |
| Consentimento | opt-in, versionado, revogável, efeito imediato, cascata, auditoria só de inserção | ADR-0038; `consent_audit` com trigger de bloqueio de UPDATE |
| Retenção do Copilot | 90 dias com job diário; exclusão definitiva pela pessoa e na revogação | ADR-0040; `PurgeExpiredCopilotData`, `DeleteConversationsOnRevocation` |
| Outros expurgos | insights não visíveis após 13 meses; outbox 7 dias e inbox 30 dias; prévia de importação 24 h; arquivo bruto de importação nunca guardado | ADR-0022, `OutboxMaintenance`, `ExpireImportPreviews`, ADR-0009 |
| Isolamento | autorização por Workspace (404 para não membro), FKs compostas, suíte `WorkspaceIsolationIT` | ADR-0003 |
| Logs e telemetria | sem pergunta, resposta, valores, e-mail ou token; `Email.toString()` mascarado; métricas sem `workspaceId`/`userId`; log do proxy sem headers, query ou IP | testes de log (`*ApiIT`, `ChangeConsentTest`); ADR-0042 §4; `Caddyfile` |
| Transporte e sessão | TLS (Let's Encrypt, HSTS); cookie selado AES-256-GCM, HttpOnly, Secure; CSP com nonce | ADR-0007, ADR-0044 |
| Identidade | IdP autogerenciado no ambiente; senha só no IdP; política de senha, força bruta, TOTP opcional | ADR-0002, ADR-0043 |
| Ambientes | staging e CI só com dados fictícios; cadastro do staging desligado | 05.11; ADR-0044 §2 |
| Segredos | env file 600 no host; Environments do GitHub com aprovação; chaves de IA por workspace com limite de gasto | ADR-0044 §6; adendo ADR-0042 §3 |

## 7. Riscos

Escala: probabilidade e impacto em B (baixo), M (médio) e A (alto). Risco: A×A, A×M e M×A = **A**; M×M, B×A, A×B e
M×B = **M**; B×B e B×M = **B**. "Bloqueante de produção" independe da nota: é uma exigência (05.12, art. 18 ou art.
33) que precisa estar cumprida antes do primeiro dado real.

| # | Risco | Prob. | Impacto | Risco | Tratamento |
|---|---|---|---|---|---|
| R1 | A pessoa digita nome de terceiro, CPF ou outro dado na pergunta, que vai literal ao provedor junto com até 3 perguntas anteriores da conversa (ADR-0040) | M | M | **M** | **residual aceito** (ADR-0039 §3), com mitigações: ZDR (R2) e retenção de 90 dias no histórico. **A implementar (bloqueante de produção, R14):** aviso na tela do Copilot e no termo de consentimento |
| R2 | O provedor retém prompts (até 30 dias para abuso) | M | M | **M** | **ZDR por acordo antes de produção** (`dpa-checklist.md` §1); opt-out de treinamento; só dados fictícios até lá |
| R3 | Transferência internacional sem mecanismo válido | M | A | **A** | cláusulas-padrão da ANPD (Res. 19/2024) no DPA, incorporadas sem alteração do texto (validar com o jurídico); informar no consentimento (R14) — **bloqueante de produção** |
| R4 | Vazamento do banco ou do disco sem criptografia em repouso (05.12 exige) | B | A | **M** | **bloqueante de produção:** criptografia de disco ou volume e dos backups, e avaliar criptografia de campo para descrições e nomes (M13) |
| R5 | Titular sem atendimento a exclusão e portabilidade (art. 18) | M | A | **A** | **bloqueante de produção:** exclusão de conta (aplicação + Keycloak + derivados + backups por expiração) e exportação autenticada, expirável e auditada (05.12) (M13) |
| R6 | Exceção logada com dado (`GlobalExceptionHandler` loga a exceção completa, sem teste) | M | M | **M** | **bloqueante de produção:** teste de ausência de dado em log de exceção, e sanitização se preciso (M13) |
| R7 | Retenções indefinidas (`ai_usage`, `consent_audit`, linhas de importação confirmadas, eventos de login, logs, backups) | A | M | **M** | prazos **propostos** no §8; implementação no M13 |
| R8 | Acesso indevido entre Workspaces | B | A | M | regra do 404, FKs compostas, suíte de isolamento; testado no staging (roteiro da Parte E, passo 12) |
| R9 | IA inventa saldo ou número | M | M | M | validação de claims e modo seguro; zero vazamento de claim no golden set (ADR-0042) |
| R10 | Dados reais em staging ou CI | B | A | M | regra 05.11, cadastro do staging desligado, seed fictício, reset obrigatório ao achar dado real |
| R11 | Insights e saúde gerados sem consentimento específico | B | B | B | execução de contrato (T3); regras fixas e explicadas; `INSIGHTS_GENERATION` adiado (ADR-0038) |
| R12 | Decisão automatizada com efeito sobre a pessoa (art. 20) | B | B | B | score, insights e previsões são informativos, sem efeito jurídico nem concessão de crédito; cada um traz explicação (ADR-0017/0021/0030). Reavaliar se houver uso para crédito ou preço |
| R13 | Incidente sem processo de comunicação (art. 48) | B | A | M | processo de incidente com prazos da ANPD (Res. CD/ANPD nº 15/2024) a escrever no M13; alertas já existem |
| R14 | **Consentimento do Copilot sem informação adequada.** O termo vigente (`copilot-data-v1`) diz que "o Copilot só lê dados dentro do app" (`frontend/src/features/consents/messages.ts`). Ele não informa o envio ao provedor de IA nos EUA nem o risco da pergunta literal. Isso enfraquece a base do T4 (art. 7 I; art. 8: consentimento informado) e a transferência (art. 33) | A | A | **A** | **bloqueante de produção:** novo termo `copilot-data-v2`, que informa o provedor, o país, o que vai e o que não vai (pseudônimos), a pergunta literal e a retenção, com aviso fixo na tela do Copilot. Pela regra do ADR-0038, a versão nova expira os consentimentos v1 e pede nova concessão. Mudança de texto e UX exige aprovação (M13) |

## 8. Retenção (**proposta** onde indicado)

| Dado | Prazo | Estado | Justificativa |
|---|---|---|---|
| Conversas e mensagens do Copilot | 90 dias; exclusão pela pessoa e na revogação | implementado | ADR-0040 |
| Feedback do Copilot | 90 dias; **apagar também na revogação** | 90 dias implementado; revogação **proposta** | hoje a revogação não o apaga (backlog) |
| Insights não visíveis | 13 meses | implementado | ADR-0022 |
| Outbox / inbox | 7 / 30 dias | implementado | operação |
| Prévia de importação | 24 h | implementado | ADR-0009 |
| Linhas de importação confirmadas (`import_records`) | **30 dias** após a confirmação | **proposta** | os fatos já estão em `transactions`; a deduplicação usa `imported_transaction_keys` |
| `ai_usage` (metadados) | **13 meses** | **proposta** | quotas, custo e abuso; comparar anos |
| `consent_audit` | **enquanto a conta existir + 5 anos** | **proposta** | prova do consentimento (art. 8 §2); validar o prazo com o jurídico |
| Eventos de login do Keycloak | **90 dias** | **proposta** | segurança da conta |
| Logs técnicos (produção) | **30 dias** | **proposta** | diagnóstico e segurança. Validar com o jurídico contra a guarda de 6 meses de registros de acesso do Marco Civil (art. 15): hoje o proxy não guarda IP, e isso precisa ser decidido junto |
| Métricas | 15 dias (staging) | staging implementado | ADR-0044 §7; produção no M14 |
| Backups (produção) | **35 dias** | **proposta** | restauração; a exclusão de conta se completa quando o último backup com o dado expira, e isso é informado à pessoa |
| Dados da conta (financeiros, derivados) | enquanto a conta existir; na exclusão, apagar ou anonimizar | **proposta** (M13) | art. 16; derivados são reconstruíveis e apagáveis |

## 9. Direitos do titular (art. 18)

| Direito | Hoje | Proposta |
|---|---|---|
| Confirmação e acesso | o app mostra os dados da pessoa | exportação cobre o acesso completo (R5) |
| Correção | edição de nome de exibição, contas, transações, objetivos | — |
| Exclusão | só do Copilot (conversas) | exclusão de conta completa, **bloqueante** (R5) |
| Portabilidade | não há | exportação autenticada, expirável e auditada, **bloqueante** (R5) |
| Informação sobre compartilhamento | não há texto público | política de privacidade com os operadores do §5 |
| Revogação do consentimento | implementada (Copilot) | — |
| Revisão de decisão automatizada | não aplicável hoje (R12) | reavaliar se houver efeito jurídico |

## 10. Conclusão

- **Staging:** pode seguir com dados fictícios. O Copilot real no staging (Parte E) usa só o seed.
- **Produção (primeiro dado real) fica condicionada a:**
  1. DPA com a Anthropic, com cláusulas-padrão da ANPD e ZDR (R2, R3);
  2. criptografia em repouso (R4);
  3. exclusão de conta e exportação (R5);
  4. teste de log de exceção (R6);
  5. prazos de retenção aprovados e implementados (R7);
  6. termo do Copilot `copilot-data-v2` e política de privacidade informando a transferência internacional e o risco da pergunta literal (R14);
  7. encarregado designado e processo de incidente (R13);
  8. validação jurídica das bases legais do §3.
- Com esses itens, o risco residual fica em **R1 (médio, aceito)**, mitigado por aviso (R14), ZDR e retenção curta.

## 11. Aprovação

| Papel | Nome | Data | Decisão |
|---|---|---|---|
| Controlador | | | |
| Encarregado (DPO) | | | |
| Responsável técnico | | | |
