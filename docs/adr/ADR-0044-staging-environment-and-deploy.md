# ADR-0044 — Ambiente de staging, imagens e deploy por digest

## Status

Accepted

## Context

A Fase 9 (pré-produção) precisa de um staging reproduzível, com deploy por SHA a partir do CI, para validar o
produto em condições de produção: `NODE_ENV=production`, HTTPS, CSP estrita (ADR-0007), IdP real (ADR-0043) e o
Copilot com a chave real (Parte E). Produção não é criada nesta fase.

O que já existe:

- `backend/Dockerfile`: multi-stage, usuário não-root, layers do Spring Boot. Sem healthcheck e com bases sem digest.
- `infrastructure/docker-compose.deploy.yml`: API, PostgreSQL, Redis e RabbitMQ num host único. Postgres, Redis e
  RabbitMQ sem portas publicadas e API por digest. Faltam frontend, IdP, proxy, observabilidade e backup.
- CI (`ci.yml`, ADR-0010) e E2E (`e2e.yml`, ADR-0011) sem segredos. Nenhum workflow publica imagem.
- Painel e alertas do Copilot versionados (`infrastructure/observability/copilot/`, ADR-0042), sem ambiente que os
  execute.
- `COPILOT_ENABLED` e `COPILOT_PROVIDER` (ADR-0039) já servem de feature flag.

Restrições (brief da Fase 9 e specs 05.5, 05.9, 05.11, 05.12):

- staging **nunca** recebe dados reais;
- segredos nunca em repositório, imagem, log ou artefato de CI;
- banco, filas e Redis nunca públicos; acesso ao host restrito e auditável;
- deploy como "consequência previsível de código aprovado" (05.11); migrations expand/contract; rollback da
  aplicação sem rollback automático do banco;
- sem Kubernetes sem justificativa; host único no MVP (05.11);
- qualquer ação com custo, conta ou credencial é executada pelo usuário.

## Decision

### 1. Host

1. **AWS Lightsail, região São Paulo (`sa-east-1`), plano Linux de 16 GB / 4 vCPU** (referência de preço nos EUA:
   cerca de US$ 84/mês; o preço de São Paulo é confirmado no console antes da contratação). **Ubuntu 24.04 LTS.**
   - Motivo do tamanho: duas JVMs (API e Keycloak), Next.js, PostgreSQL, RabbitMQ, Redis e observabilidade usam
     4,5 a 5,5 GB estimados. A CPU do Lightsail é burstable, e o plano de 8 GB tem só 2 vCPU, pouco para os
     startups das JVMs em cada deploy.
   - IP estático do Lightsail (gratuito enquanto anexado). Firewall do Lightsail com **22, 80 e 443** abertos; nada
     mais. IPv6 só se o firewall do IPv6 tiver as mesmas regras.
   - Região no Brasil: o staging não tem dados reais, mas a mesma região de uma produção futura deixa a latência e
     o comportamento comparáveis, e os dados do IdP ficam no país (ADR-0043, Consequences).
2. **Domínio** do usuário, com dois nomes para o staging: `app.staging.<domínio>` (frontend) e
   `auth.staging.<domínio>` (Keycloak). Registros DNS `A` (e `AAAA`, se houver IPv6) para o IP estático.
3. A conta AWS é do usuário, com MFA na conta raiz e um alerta de orçamento (AWS Budgets) configurado antes da
   instância.

### 2. Topologia (um host, Docker Compose)

```text
internet ──443/80──► caddy ─┬─ app.staging.<domínio>  ──► web  (Next.js, NODE_ENV=production)
                            └─ auth.staging.<domínio> ──► keycloak (só /realms/smart-health-finance/* e /resources/*)
rede interna (sem portas no host):
  web ──► api:8080 (BFF → API)        api ──► postgres, redis, rabbitmq
  api, web ──► auth.staging.<domínio> (alias de rede do caddy, TLS válido, sem sair do host)
  keycloak ──► postgres (database e usuário próprios)   keycloak ──► mailpit (SMTP)
  api ──► otel-lgtm (OTLP)             grafana, mailpit, admin do keycloak: só por túnel SSH
```

4. **Caddy** é o único container com portas publicadas (80 e 443). TLS automático com Let's Encrypt, sem custo;
   HTTP redireciona para HTTPS. O Caddy acrescenta `Strict-Transport-Security: max-age=31536000` nos dois nomes,
   sem `includeSubDomains` nem `preload`, porque o domínio é do usuário e pode ter outros usos. Hoje o app não
   emite HSTS.
   - `app.staging.<domínio>` → `web:3000`. A **API não é publicada**: o navegador fala só com o BFF (ADR-0007), e
     o BFF chama `http://api:8080` pela rede interna. `API_DOCS_ENABLED=false`.
   - `auth.staging.<domínio>` → `keycloak:8080`, publicando só `/realms/smart-health-finance/*` e `/resources/*`.
     Todo o resto responde 404 (ADR-0043 §5), inclusive o **realm `master`** (`/realms/master/*`), o que fecha o
     token endpoint do admin de bootstrap para a internet, além de `/admin`, `/metrics`, `/health` e a porta de
     gerenciamento.
   - Cabeçalhos `X-Forwarded-*` definidos pelo Caddy, nunca repassados do cliente.
5. **Tráfego para o IdP dentro do host.** O container do Caddy recebe o alias de rede `auth.staging.<domínio>`.
   API e BFF resolvem o hostname público para o Caddy, com o certificado válido e o mesmo issuer, sem sair do host
   e sem mudança de código (responde à pendência do ADR-0043 §5).
6. **Frontend com `NODE_ENV=production`**, `APP_BASE_URL=https://app.staging.<domínio>`. A CSP com nonce do
   `proxy.ts` já desliga as exceções de desenvolvimento e liga `upgrade-insecure-requests` quando o
   `APP_BASE_URL` é HTTPS. O cookie de sessão é `Secure`.
7. **API e workers no mesmo processo**, como hoje (ADR-0001). Separar workers fica para a evolução da 05.11.
8. **Keycloak** (ADR-0043): imagem oficial por digest, modo `start` (não `start-dev`), `--hostname
   https://auth.staging.<domínio>`, `--proxy-headers xforwarded`, `--http-enabled true`. Usa o mesmo PostgreSQL do
   ambiente, com **database e usuário próprios** (ADR-0043 §3). Realm importado só se ausente.
   - O realm de staging é um **arquivo próprio e endurecido** (Parte D). O realm local
     (`infrastructure/keycloak/realm-smart-health-finance.json`, com password grant, redirects em localhost e
     usuários de desenvolvimento) nunca é usado no staging.
   - **Client de seed** (adendo ao ADR-0043 §7, só no staging): `shf-staging-seed`, confidencial e com password
     grant, existe só no realm de staging e fica **desligado**.
     - O script de seed o liga pelo `kcadm` dentro do container do Keycloak (rede interna), faz o seed e o
       desliga, inclusive em caso de falha.
     - O segredo do client fica no env file.
     - Os usuários sintéticos (`@example.com`, senhas aleatórias no env file) são criados pelo mesmo `kcadm`.
     - O `shf-web` não muda.
9. **E-mail do staging: Mailpit** em container. Captura todo e-mail do Keycloak (verificação e recuperação de
   senha) sem entregar a ninguém. A UI fica só no túnel SSH. Sem serviço externo nem custo. O SMTP real de
   produção é decidido no M14.
   - **Cadastro desligado no staging** (`registrationAllowed=false`). A URL é pública, e um cadastro aberto
     guardaria e-mails reais no Keycloak e no Mailpit, contra a 05.11. Só existem os usuários sintéticos do seed
     (`@example.com`).
   - A verificação de e-mail e a recuperação de senha (`verifyEmail` e `resetPasswordAllowed` ligados) são testadas
     com esses usuários pelo Mailpit.
   - O fluxo de cadastro é testado no E2E local (ADR-0011) ou ligando o cadastro só durante um teste manual
     registrado, e desligando em seguida.
   - Produção segue o ADR-0043 §9.
10. **Limites de memória** por container e `JAVA_TOOL_OPTIONS` com `MaxRAMPercentage`, para nenhum serviço derrubar
    os outros. Logs de container com rotação (`json-file`, `max-size` e `max-file`).

### 3. Imagens

11. **Frontend:** novo `frontend/Dockerfile` multi-stage com `output: 'standalone'` do Next.js. Runtime
    `node:22-alpine` com usuário não-root, só o `.next/standalone`, `.next/static` e `public`, e
    `NEXT_TELEMETRY_DISABLED=1`. Nenhuma variável de ambiente sensível entra no build: tudo é lido em runtime.
12. **Backend:** o Dockerfile atual continua e recebe bases fixadas por digest. O healthcheck fica **no compose**,
    onde `MANAGEMENT_SERVER_PORT=8081` é definido.
    - Ele chama `/actuator/health/readiness` sem ferramenta extra na imagem: requisição HTTP pelo `bash` com
      `/dev/tcp`, pois a base Temurin tem bash, mas não `curl`.
    - O web usa o `node` da própria imagem para o healthcheck.
13. **Todas as imagens por digest**, inclusive as de terceiros (Caddy, PostgreSQL, Redis, RabbitMQ, Keycloak,
    Mailpit, otel-lgtm). A atualização de uma imagem de terceiros é deliberada e revisada em PR.
14. **Tags:** `sha-<sha completo>` em todo build da `main`; `vX.Y.Z` quando houver tag git SemVer. O deploy usa
    sempre o **digest** (`imagem@sha256:...`), nunca tag.
15. **Registry: GHCR** (`ghcr.io/<dono>/smart-health-finance-api` e `-web`), com pacotes **públicos**: o host faz
    `pull` sem credencial. As imagens não contêm segredo nem dado, e o código já é público.

### 4. CI de imagens (`.github/workflows/images.yml`)

16. Disparo: `push` na `main`, tags `v*` e `workflow_dispatch`. Não roda em PR de fork. `permissions`: `contents:
    read` e `packages: write` só no job que publica.
17. **Sem actions de terceiros.** Só `actions/*` fixadas por SHA (ADR-0010). Build e push com o `docker buildx`
    já instalado no runner; login no GHCR com o `GITHUB_TOKEN` pela entrada padrão (`--password-stdin`).
18. Ordem por imagem:
    - build com `--provenance=mode=max` e `--sbom=true`: o buildx anexa SBOM e provenance à imagem, sem custo;
    - **scan com Trivy** pela imagem oficial do Trivy fixada por digest, não pela `aquasecurity/trivy-action`.
      Em março de 2026, 76 das 77 tags dessa action foram reescritas com código malicioso (CVE-2026-33634,
      GHSA-69fq-xp46-6x23):
      - vulnerabilidades HIGH e CRITICAL **com correção disponível** falham o job;
      - o scanner de segredos também roda sobre a imagem, e qualquer achado falha o job;
      - **exceção:** uma vulnerabilidade da imagem base ainda sem atualização utilizável vai para um
        `.trivyignore` revisado em PR, com justificativa e data de expiração. Quando a base é corrigida, a exceção
        sai;
    - push só se o scan passar, e o digest vai para o resumo do job.
19. As imagens de terceiros do compose são escaneadas no mesmo workflow, mas **sem bloquear**: o relatório aparece
    no resumo e uma vulnerabilidade corrigível vira uma atualização de digest em PR.
20. O `ci.yml` (ADR-0010) não muda. O workflow de imagens não substitui o CI: o deploy exige os dois verdes
    para o mesmo SHA.

### 5. Deploy do staging (`.github/workflows/deploy-staging.yml`)

21. Disparo **manual** (`workflow_dispatch`) com o SHA da `main` como entrada, no GitHub Environment **`staging`**
    com o usuário como revisor obrigatório.
22. O workflow:
    1. confirma que o SHA pertence à `main` e que os workflows `CI`, `E2E` e `Images` desse SHA terminaram com
       sucesso;
    2. resolve os digests da API e do web a partir das tags `sha-<sha>`;
    3. conecta por SSH ao host com a chave do Environment e o `known_hosts` fixado (sem `StrictHostKeyChecking=no`);
    4. executa `deploy <sha> <digest-api> <digest-web>`;
    5. roda o smoke test externo e publica o resultado no resumo, sem dados.
23. **SSH restrito para o deploy.** Usuário `deploy` no host, sem senha, sem shell interativo e **fora do grupo
    `docker`**.
    - A chave pública no `authorized_keys` tem `command="/usr/local/bin/shf-deploy-gate"` e
      `restrict` (sem port forwarding, agent forwarding, X11 nem pty).
    - O *gate* lê `SSH_ORIGINAL_COMMAND` e aceita só o formato `deploy <sha> <digest-api> <digest-web>`: SHA com 40
      hexadecimais e digests `sha256:` com 64 hexadecimais, validados por expressão regular inteira. Qualquer outra
      coisa é recusada.
    - Depois da validação, o gate chama `sudo /usr/local/bin/shf-deploy` com os três valores como **argumentos**,
      nunca por `eval` ou shell. O `sudoers` permite ao `deploy` só esse comando, e o script revalida os
      argumentos e fixa os repositórios das imagens.
    - **Arquivos do host são instalados, não trazidos pelo deploy.** O compose, o Caddyfile, os scripts e as
      units do systemd ficam em `/opt/shf` e `/usr/local/bin`, com dono `root` e sem escrita para o `deploy`.
      - Quem instala é o usuário administrador, rodando `install.sh` de um commit revisado da `main`. O runbook
        manda conferir o SHA antes.
      - O deploy só troca os **digests**, gravados num arquivo `root:root 600` (`/etc/shf/staging.images`) lido pelo
        compose. Ele nunca busca código, compose ou scripts do repositório.
      - Uma mudança na infraestrutura do host é uma reinstalação deliberada, registrada no `deployments.log`.
24. **Estratégia: recreate**, aceita no staging (05.11):
    1. `pull` das imagens por digest antes de parar qualquer coisa;
    2. API recriada primeiro. O Flyway aplica as migrations no startup (expand/contract). O script espera a
       readiness;
    3. web recriado em seguida, e o script espera o `/` responder;
    4. downtime esperado de dezenas de segundos.
25. **Shutdown gracioso:** `server.shutdown=graceful` com `stop_grace_period` de 30 s na API. Os listeners do
    RabbitMQ terminam a mensagem em curso e não pegam novas. Uma mensagem interrompida volta para a fila e é
    reprocessada com idempotência (inbox). O outbox retoma depois do startup.
26. **Rollback = redeploy de um digest anterior.** O host grava cada deploy (data, SHA, digests e resultado) em
    `/var/lib/shf/deployments.log`. **Não há rollback automático do banco:** o código anterior convive com o schema
    novo porque as migrations são expand/contract, e o Flyway ignora migrations futuras (padrão `*:future`, não
    sobrescrito).
    - A expansão precisa ser **compatível com o código anterior**. Colunas novas são anuláveis ou têm default, para
      que os `INSERT` antigos e o `ddl-auto: validate` continuem funcionando.
    - Uma contração (remover coluna ou tabela, ou tornar `NOT NULL`) só entra depois que nenhum digest em uso
      depende do que ela remove.
    - Os rebuilds de startup (`ANALYTICS_REBUILD=missing`, `MERCHANT_RENORMALIZE=outdated`) são idempotentes. O
      runbook de rollback manda conferir se o intervalo entre os dois digests tem mudança de versão desses rebuilds
      antes de voltar.
27. **Smoke test pós-deploy:**
    - no host: readiness da API (actuator interno);
    - pelo runner:
      - `https://app.staging.<domínio>/` responde com `Content-Security-Policy` com nonce e `Strict-Transport-Security`;
      - discovery do realm em `https://auth.staging.<domínio>/realms/smart-health-finance/.well-known/openid-configuration`;
      - `/admin` e `/realms/master/.well-known/openid-configuration` do IdP respondem 404.
    - Uma falha marca o deploy como falho e mostra o comando de rollback; o rollback não é automático.

### 6. Segredos

28. **Env file no host:** `/etc/shf/staging.env`, `root:root`, `chmod 600`, criado pelo usuário a partir de
    `infrastructure/.env.staging.example`, só com placeholders no repositório. O script de deploy passa o arquivo ao
    compose e nunca imprime seu conteúdo.
    - Contém senhas do PostgreSQL, Redis, RabbitMQ e Keycloak (banco e admin de bootstrap), o `SESSION_SECRET`
      do BFF, o token do bot do Telegram e, na Parte E, a `ANTHROPIC_API_KEY`.
    - Senhas próprias do staging, geradas aleatoriamente, nunca reutilizadas.
29. **GitHub Environment `staging`** guarda só o necessário para o deploy: chave privada SSH do `deploy`, o
    `known_hosts` do host e o hostname/IP. Nenhum segredo de aplicação vai para o GitHub.
30. Sem secret manager pago no staging. Reavaliar para produção (M14).

### 7. Observabilidade

31. **`grafana/otel-lgtm`** no host (Grafana, Prometheus, Loki e Tempo numa imagem), só na rede interna. A **API**
    exporta OTLP para ele (`OTEL_EXPORT_ENABLED=true`, amostragem de traces 0,1). O Grafana fica acessível só por
    túnel SSH.
    - O BFF não tem instrumentação OpenTelemetry hoje e continua assim nesta fase. Os logs dele ficam no log do
      container (rotacionado). A disponibilidade do app aparece pelo smoke test e pelo 5xx do Caddy.
    - A instrumentação do frontend fica para o M14 e exigirá decisão própria (nova dependência).
    - Retenção curta no staging: cerca de 15 dias de métricas e 7 dias de logs e traces.
    - Essa imagem não serve para produção. A observabilidade de produção é decidida no M14.
32. **Painéis:**
    - o do Copilot (ADR-0042);
    - um painel mínimo de saúde: disponibilidade, latência p95 e 5xx da API; filas e DLQs do RabbitMQ; outbox
      pendente; frescor de Health, Forecast e Insights; memória e CPU dos containers.
33. **Alertas pelo Grafana Alerting**, com as regras do ADR-0042 e as de saúde provisionadas como código.
    - O `alerts.yml` do Copilot está no formato de regras do Prometheus, que o otel-lgtm padrão não avalia. A Parte D
      o **converte** para regras gerenciadas pelo Grafana (arquivos de provisionamento).
    - Um teste garante que as duas versões têm os mesmos alertas, expressões e prioridades. A fonte continua sendo
      o `alerts.yml`.
    - A Parte D também mapeia os nomes OTLP para os nomes usados nas regras (sufixos de unidade e `_total`), com
      teste.
    - **Canal: bot do Telegram** criado pelo usuário. Token e chat id ficam no env file.
    - A mensagem leva só nome, prioridade, resumo e link do runbook. Nunca valores de usuário, Workspace ou
      dado financeiro.
34. Logs continuam JSON, sem dados financeiros sensíveis nem tokens (05.9). Os logs do Keycloak ficam no host e não
    vão para artefatos de CI.

### 8. Dados

35. **Seed fictício** a partir de `design/specs/09-dados-de-exemplo.md`, carregado **pela API** (rede interna,
    `http://api:8080`), com tokens dos usuários sintéticos obtidos pelo client de seed temporário (item 8).
    - Carrega contas, categorias, transações por importação CSV, cartões e metas.
    - Nenhum SQL direto que contorne o domínio: os read models (Analytics, Health, Forecast, Insights) se formam pelos
      eventos, como em produção.
    - O script é idempotente por usuário sintético.
36. **Backup diário** por timer do systemd:
    - `pg_dump` em formato custom de cada database (aplicação e Keycloak) para `/var/backups/shf`, `root:root`,
      `chmod 600`, com 7 dias de retenção local;
    - mais os snapshots automáticos diários do Lightsail, que guardam os últimos 7.
37. **Teste de restore:** o primeiro é feito na Parte D e documentado. Consiste em restaurar o dump mais recente
    num database temporário, conferir contagens e saldos de uma conta do seed contra a API e apagar o temporário.
    Ele se repete a cada mudança relevante de schema ou de rotina de backup.
38. **Reset:** o staging pode ser zerado a qualquer momento (volumes do PostgreSQL e do RabbitMQ, reimportação do
    realm, novo seed) por um script com confirmação explícita. Nenhum dado real é tolerado: ao achar um, o ambiente
    é zerado.

### 9. Acesso e auditoria

39. **SSH só por chave:** `PasswordAuthentication no`, `PermitRootLogin no`, `AllowUsers` com o usuário de
    administração e o `deploy`. O usuário de administração é do usuário, com a chave dele.
40. `unattended-upgrades` para atualizações de segurança do sistema; o reboot fica a critério do usuário.
41. **Auditoria mínima:**
    - journald persistente com logins SSH e uso de `sudo`;
    - `deployments.log` com cada deploy;
    - no GitHub, a aprovação de cada execução do Environment.
42. Nenhum serviço interno (PostgreSQL, Redis, RabbitMQ, Grafana, Mailpit, admin do Keycloak, actuator) tem porta no
    host. O acesso administrativo é por túnel SSH (`ssh -L`).

### 10. Feature flags

43. O Copilot liga e desliga por `COPILOT_ENABLED` no env file, seguido de um redeploy do mesmo digest. O staging
    sobe com `COPILOT_PROVIDER=simulated`; a troca para o provedor real é da Parte E, feita pelo usuário.

## Alternatives

- **Hetzner CX33** (cerca de €9/mês, 4 vCPU e 8 GB, Alemanha ou Finlândia): o mais barato. Rejeitada pelo usuário em favor
  de uma região no Brasil.
- **Vultr São Paulo 8 GB** (cerca de US$ 40/mês): região no Brasil e mais barato que o Lightsail. Não escolhida.
- **Lightsail 8 GB / 2 vCPU** (cerca de US$ 44/mês): metade do custo, mas CPU burstable e 2 vCPU para duas JVMs.
  Risco de lentidão e de smoke tests instáveis. Rejeitada.
- **Kubernetes ou serviço gerenciado de containers:** desproporcional para um host (05.11).
- **Nginx ou Traefik no lugar do Caddy:** o Nginx precisa de certbot e renovação à parte; o Traefik configura por
  labels, mais difícil de revisar. O Caddy dá TLS automático com um arquivo curto.
- **Proxy no host (fora do Docker):** exigiria publicar a API e o web em `127.0.0.1`. Com o Caddy no compose, a
  configuração inteira é versionada e nenhum app tem porta no host.
- **Registry privado (ECR, GHCR privado):** exigiria credencial de pull no host. Sem ganho, pois o código é
  público.
- **`docker/build-push-action` e `aquasecurity/trivy-action`:** actions de terceiros. Os comandos equivalentes do
  `docker buildx` e a imagem do Trivy por digest fazem o mesmo sem ampliar a cadeia de suprimentos.
- **Deploy automático a cada push na `main`:** sem pausa entre uma migration nova e o staging. Rejeitado.
- **Script manual no host, sem SSH do GitHub:** deixaria o deploy dependente do terminal do usuário. Rejeitado.
- **Runner self-hosted no host:** inaceitável num repositório público (um PR poderia executar código no host).
- **Grafana Cloud (gratuito):** menos RAM no host, mas é uma conta e um destino externo a mais. Rejeitado.
- **Alerta por e-mail:** exigiria um SMTP real só para alertas, já que o Mailpit não entrega. Rejeitado.
- **SMTP real no staging (Brevo, Resend, SES):** conta externa e e-mails para endereços fictícios. O Mailpit atende.
- **Seed por SQL:** mais rápido, mas contornaria o domínio, os eventos e os read models. Rejeitado.
- **Seed por login PKCE automatizado (Playwright no host):** sem client novo, mas pesado (Chromium) e frágil.
  Rejeitado em favor do client de seed desligado por padrão.
- **Seed por endpoint interno sem autenticação:** abriria uma porta sem OIDC no código de produção. Rejeitado.
- **Publicar `/realms/*` inteiro:** exporia o realm `master` e o token endpoint do admin. Rejeitado.
- **Blue/green:** fica para quando houver necessidade de deploy sem downtime (05.11, "Futuro").

## Consequences

- Positivas:
  - staging igual à produção pretendida em modo de execução, TLS, CSP, IdP e topologia;
  - deploy por digest, aprovado e registrado, com rollback por redeploy;
  - nenhuma porta de dado aberta;
  - nenhuma action de terceiros nova;
  - nenhum segredo de aplicação no GitHub;
  - painel e alertas do ADR-0042 exercitados antes de produção.
- Negativas:
  - custo mensal do Lightsail de 16 GB (o maior item da fase, sem contar o Copilot);
  - um host único é ponto único de falha (aceitável para staging);
  - o recreate tem downtime;
  - a porta 22 fica aberta para a internet porque os runners do GitHub não têm IP fixo (mitigada: só chave,
    comando forçado e sem root);
  - o otel-lgtm não é a solução de produção, então a observabilidade de produção ainda será decidida.
- Trade-offs: deploy manual com aprovação em vez de contínuo; pacotes públicos no GHCR em vez de credencial no host.

## Security / Privacy

- **Dados:** só fictícios (05.11, 05.12). O cadastro fica desligado no staging, então ninguém de fora cria conta
  com e-mail real. Os e-mails do Keycloak ficam no Mailpit e nunca são entregues.
- **Segredos:** só no env file do host (`600`) e, para o deploy, no Environment `staging`. Nunca em imagem (o scan
  de segredos bloqueia o push), log, artefato ou repositório (só `*.example`).
- **Rede:** 80 e 443 só no Caddy; a API e o admin do IdP não são publicados; serviços internos sem porta no host.
- **Cadeia de suprimentos:**
  - todas as imagens por digest; scan bloqueante das imagens próprias;
  - SBOM e provenance anexados;
  - actions só oficiais e por SHA; nenhum runner self-hosted.
- **Deploy:** chave restrita a um comando validado; o `deploy` não tem shell nem acesso direto ao Docker;
  aprovação humana no Environment. O deploy não altera os arquivos executados como root: só troca digests.
- **IdP:** só o realm da aplicação é publicado. O client de seed existe só no staging, desligado fora do seed.
- **Telemetria e alertas:** sem `workspaceId`, `userId`, perguntas, respostas ou valores. O Telegram recebe só o
  texto do alerta.
- **PIA (Parte F):** registra o staging na AWS em São Paulo, o Telegram como canal de alertas (sem dado pessoal)
  e o provedor do Copilot da Parte E.

## Financial Integrity

Sem mudança de regra financeira, cálculo, schema ou evento.

- O seed passa pela API e pelos eventos, como em produção.
- O rollback não reverte o banco.
- A idempotência do inbox cobre mensagens reprocessadas após um shutdown.
- O teste de restore confere saldos derivados contra a API.

## Operational Impact

- Arquivos novos na Parte D:
  - `frontend/Dockerfile`;
  - `.github/workflows/images.yml` e `deploy-staging.yml`;
  - `infrastructure/staging/`:
    - compose e Caddyfile;
    - realm de staging;
    - `install.sh`;
    - scripts de gate, deploy, backup, restore, reset e seed;
    - units do systemd;
  - `.trivyignore`, vazio no início;
  - provisionamento do Grafana;
  - `infrastructure/.env.staging.example`;
  - runbooks de staging, deploy/rollback, backup/restore e Keycloak.
- Mudanças em arquivos existentes na Parte D:
  - `next.config.ts` ganha `output: 'standalone'`;
  - o `backend/Dockerfile` ganha digest e healthcheck;
  - o `docker-compose.deploy.yml` é substituído pelo compose de staging (produção terá o seu no M14).
- Passos do usuário: contas AWS e domínio, instância e firewall, DNS, env file, usuário `deploy`, bot do
  Telegram, Environment `staging` e seus secrets, e a visibilidade pública dos pacotes no GHCR.
- **Custo estimado:**
  - Lightsail 16 GB: cerca de US$ 84+/mês (confirmar o preço de São Paulo);
  - snapshots: alguns dólares por mês;
  - domínio: cerca de US$ 10 a 15 por ano;
  - GHCR, Let's Encrypt, Telegram e Mailpit: sem custo.
  O Copilot fica à parte (Parte E).

## Related Specs

- `specs/05-system-design/05.5-infrastructure-security.md`
- `specs/05-system-design/05.9-observability-operations.md`
- `specs/05-system-design/05.11-devops-deployment.md`
- `specs/05-system-design/05.12-lgpd-privacy-data-governance.md`
- `docs/adr/ADR-0001-modular-monolith-clean-architecture.md`
- `docs/adr/ADR-0007-frontend-foundation-and-bff-auth.md`
- `docs/adr/ADR-0010-ci-and-openapi-contract-snapshot.md` (adendo)
- `docs/adr/ADR-0039-copilot-ai-model.md`
- `docs/adr/ADR-0042-copilot-evaluation-and-observability.md` (adendo)
- `docs/adr/ADR-0043-production-identity-provider.md` (adendo)
- `specs/06-development/backlog.md` (M14, Fase 9)

## Date

2026-11-16
