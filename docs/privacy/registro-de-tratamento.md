# Registro das operações de tratamento (LGPD art. 37)

Complementa o `ripd.md` (finalidades T1–T7, bases legais propostas no §3, retenção no §8). Status: **proposta**,
pendente de aprovação do controlador e validação jurídica. Atualize a cada tabela, operador ou finalidade nova (a
skill `flyway-migration` e o `adr-review` devem apontar para cá).

Legenda de classe (05.12): **P** Personal · **F** Financial · **D** Derived · **A** Audit · **T** Technical.

## Banco da aplicação (PostgreSQL)

| Tabela | Dados pessoais / financeiros | Classe | Finalidade | Retenção | Exclusão hoje |
|---|---|---|---|---|---|
| `users` | e-mail, nome de exibição, issuer e subject OIDC | P | T1 | conta | não há exclusão de conta (R5) |
| `workspaces`, `workspace_memberships` | nome do Workspace (texto livre), vínculo pessoa↔Workspace | P | T1/T2 | conta | — |
| `accounts` | nome, instituição, tipo | F | T2 | conta | arquivamento (soft) |
| `transactions` | valor, data, descrição livre, comerciante normalizado | F | T2 | conta | sem exclusão física (ADR-0005) |
| `import_batches` | nome do arquivo, SHA-256, tamanho, autor, metadados de fatura; **sem o arquivo bruto** | F | T2 | conta | — |
| `import_records` | linhas válidas: data, valor, descrição, id externo; inválidas: só o código | F | T2 | prévia 24 h; confirmadas **sem prazo** (proposta: 30 dias) | cancelamento ou expiração da prévia |
| `imported_transaction_keys` | chaves de deduplicação | T | T2 | conta | — |
| `credit_cards` | apelido, instituição, **4 últimos dígitos**, limite, datas | F | T2 | conta | arquivamento |
| `card_invoices`, `card_installments`, `card_installment_confirmations` | faturas e parcelas | F | T2 | conta | — |
| `goals` | nome digitado, alvo, aporte, prazo | F | T2 | conta | sem exclusão física (ADR-0034) |
| `categories`, `category_rules` | catálogo; regra comerciante→categoria | D | T3 | conta | — |
| `analytics_*` | fluxos diários por comerciante, projeções | D | T3 | conta (reconstruível) | rebuild |
| `health_snapshots`, `health_workspaces` | score e explicação | D | T3 | conta (reconstruível) | rebuild |
| `insights`, `insights_workspaces` | assunto, conteúdo, estado do usuário | D | T3 | não visíveis: 13 meses | expurgo |
| `forecast_*` | séries recorrentes (rótulo), previsões | D | T3 | conta (reconstruível) | rebuild |
| `consents` | finalidade, estado, versão do termo | A | T4/T5/T7 | conta | — |
| `consent_audit` | ação, versão, causa, data (sem IP ou user-agent); só inserção | A | T7 | **sem prazo** (proposta: conta + 5 anos) | não (prova) |
| `copilot_conversations`, `copilot_messages` | título (80 caracteres da 1ª pergunta), pergunta literal, resposta | P/F | T5 | 90 dias | pela pessoa; na revogação |
| `copilot_feedback` | útil ou não útil, intenção, versões (sem texto livre) | A | T5 | 90 dias | **não na revogação** (proposta: sim) |
| `ai_usage` | ids, modelo, tokens, custo, latência, outcome, versões (sem conteúdo) | A | T6 | **sem prazo** (proposta: 13 meses) | — |
| `outbox_events`, `processed_events` | só ids e contagens no payload | T | operação | 7 / 30 dias | expurgo |
| `transaction_idempotency_keys` | chaves de idempotência | T | T2 | — | — |

## Fora do banco da aplicação

| Onde | Dados | Classe | Finalidade | Retenção |
|---|---|---|---|---|
| Keycloak (banco próprio) | e-mail, nome, hash de senha, segredo TOTP, sessões; eventos de login só se ligados (hoje não configurados no realm) | P | T1 | conta; eventos, se ligados: **proposta** 90 dias |
| Redis | rate limit do Copilot (bucket por pessoa, com TTL), idempotência; sem conteúdo | T | T6 | TTL |
| RabbitMQ | eventos em trânsito (ids e contagens) | T | operação | até o consumo; DLQ até o tratamento |
| Cookie `shf_session` (navegador) | tokens e nome de exibição, selados AES-256-GCM | P | T1 | até 7 dias (teto) |
| Logs (containers, Loki) | ids, códigos e status; sem conteúdo | T | T7 | containers: rotação por tamanho; Loki: retenção não definida (ADR-0044 §7, divergência registrada com o item 31); produção **proposta**: 30 dias |
| Traces (Tempo), métricas (Prometheus) | sem conteúdo nem `workspaceId` em métrica | T | T7 | métricas 15 dias (staging); traces sem prazo definido (M14) |
| Backups (`pg_dump`, snapshots) | cópia de todo o banco | todas | continuidade | staging 7 dias; produção **proposta**: 35 dias |
| Anthropic | pergunta literal, contexto pseudonimizado | P/F | T4 | padrão do provedor (até 30 dias); **ZDR proposto** |
| Mailpit (só staging) | e-mails do Keycloak para usuários sintéticos | P (fictício) | testes | até 500 mensagens |
