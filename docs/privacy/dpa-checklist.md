# Checklist de DPA e contratos com operadores

Referência: `ripd.md` §5 (operadores e transferência internacional), LGPD arts. 33, 37, 39, 46 e 48.
**Quem assina e negocia é o controlador.** Este checklist só organiza o que conferir. Os itens marcados
**bloqueante** precisam estar resolvidos antes do primeiro dado real em produção.

Registre a evidência de cada item (versão do documento, data, link ou cópia guardada fora do repositório, sem
segredos).

## 1. Anthropic (Financial Copilot): **bloqueante**

| # | Item | Conferir | Estado |
|---|---|---|---|
| 1.1 | DPA assinado / aceito | Data Processing Addendum vigente da Anthropic para a API comercial, na versão aceita pela conta que gera as chaves | ☐ |
| 1.2 | Papel das partes | Anthropic como **operadora** (processor), tratando só por instrução do controlador (art. 39) | ☐ |
| 1.3 | Transferência internacional | **Cláusulas-padrão contratuais da ANPD** (Resolução CD/ANPD nº 19/2024) incorporadas ou anexadas. Se o DPA só traz as SCCs europeias, anexar as da ANPD. O texto das cláusulas-padrão é incorporado sem alteração. Validação jurídica | ☐ |
| 1.4 | **Zero data retention (ZDR)** | Solicitar ZDR por acordo para a organização ou os workspaces de produção. Confirmar por escrito o escopo: Messages API, sem recursos não elegíveis. O Copilot usa só a Messages API, sem Files, Batches ou Managed Agents | ☐ |
| 1.5 | Sem treinamento | Dados de entrada e saída da API não usados para treinar modelos (termos comerciais) | ☐ |
| 1.6 | Retenção sem ZDR | Prazo de retenção padrão (abuso e segurança) documentado, caso o ZDR ainda não esteja ativo | ☐ |
| 1.7 | Suboperadores | Lista de suboperadores e forma de aviso de mudança | ☐ |
| 1.8 | Segurança | Certificações e relatórios (ex.: SOC 2 Tipo II, ISO 27001) e medidas técnicas | ☐ |
| 1.9 | Incidentes | Prazo de aviso ao controlador compatível com a comunicação à ANPD e aos titulares (art. 48; Res. CD/ANPD nº 15/2024) | ☐ |
| 1.10 | Região | Local de processamento (inferência `global`/`us`, ADR-0039 §1) declarado ao titular | ☐ |
| 1.11 | Fim do contrato | Devolução e eliminação dos dados ao término | ☐ |
| 1.12 | Configuração da conta | Workspaces separados (`shf-eval`, `shf-staging` e, no futuro, produção), limites de gasto, chaves por workspace, MFA e acesso mínimo ao Console | ☐ |

## 2. AWS (hospedagem: Lightsail São Paulo)

| # | Item | Conferir | Estado |
|---|---|---|---|
| 2.1 | DPA | AWS Data Processing Addendum (incorporado aos Service Terms) aplicável à conta | ☐ |
| 2.2 | Região | `sa-east-1` (dados no Brasil); snapshots na mesma região | ☐ |
| 2.3 | Criptografia | Criptografia de disco ou volume e dos snapshots; complementa o R4 do RIPD (**bloqueante** em produção) | ☐ |
| 2.4 | Conta | MFA na raiz, usuário IAM para o dia a dia, alerta de orçamento (`staging.md` §1) | ☐ |
| 2.5 | Produção | Reavaliar o provedor e o desenho no M14 (banco gerenciado, criptografia, backups fora do host) | ☐ |

## 3. Telegram (alertas)

| # | Item | Conferir | Estado |
|---|---|---|---|
| 3.1 | Sem dado pessoal | A mensagem leva só nome, prioridade, resumo e runbook (`StagingObservabilityAssetsTest`) | ☑ (técnico) |
| 3.2 | Acesso | Bot e grupo restritos ao controlador e à equipe; token só no env file | ☐ |
| 3.3 | Alternativa | Em produção, reavaliar o canal (ex.: e-mail com o SMTP de produção) | ☐ |

## 4. GitHub (código, Actions, GHCR)

| # | Item | Conferir | Estado |
|---|---|---|---|
| 4.1 | Sem dado de titular | CI, E2E e golden set só com dados sintéticos (05.11, 05.12) | ☑ (técnico) |
| 4.2 | Segredos | Chaves só em Environments com revisor (`staging`, `copilot-eval`) | ☐ |
| 4.3 | Imagens públicas | Sem segredo nem dado nas imagens (scan de segredos do Trivy bloqueante) | ☑ (técnico) |

## 5. Outros

| # | Item | Conferir | Estado |
|---|---|---|---|
| 5.1 | Let's Encrypt | Recebe só domínios e o e-mail ACME do operador; sem dado de titular | ☑ (técnico) |
| 5.2 | SMTP de produção (M14) | Quando escolhido: DPA, região e ausência de conteúdo financeiro nos e-mails | ☐ |
| 5.3 | Novos operadores | Todo serviço novo que receba dado pessoal entra aqui e no `registro-de-tratamento.md` antes do uso (ADR) | ☐ |
