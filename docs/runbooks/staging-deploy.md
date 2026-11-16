# Runbook — Staging: deploy por digest e rollback

Referência: ADR-0044 §4–§5, specs 05.11 (deploy por SHA/digest; rollback da aplicação sem rollback do banco).
Preparação do host: `staging.md`.

## Como um commit chega ao staging

1. PR → `main`. Os workflows **CI**, **E2E** e **Images** rodam no push da `main`.
2. **Images** faz build da API e do web, escaneia com o Trivy (HIGH/CRITICAL corrigíveis e segredos bloqueiam) e
   publica `ghcr.io/kn2310/smart-health-finance-{api,web}:sha-<sha>` com SBOM e provenance. O digest aparece no resumo
   do job.
3. Actions → **Deploy staging** → Run workflow → informe o **SHA completo**. O workflow:
   - espera a sua aprovação no Environment `staging` (antes de qualquer passo do job);
   - confere que o SHA está na `main` e que CI, E2E e Images estão verdes para ele (o run mais recente de cada um);
   - lê os digests do artefato `image-digest-{api,web}` do run do **Images** da `main`. Esse artefato só existe se o
     push e o scan do **digest publicado** passaram (90 dias de retenção; depois disso, um rerun do Images no SHA
     o recria). A tag `sha-<sha>` nunca é usada como fonte do digest;
   - conecta por SSH no usuário `deploy`. O gate aceita só `deploy <sha> <digest-api> <digest-web>` e chama
     `sudo shf-deploy`;
   - roda o smoke test externo (`infrastructure/staging/smoke-test.sh`).
4. No host, o `shf-deploy`:
   - faz `pull` por digest e confere o label `org.opencontainers.image.revision` (= SHA);
   - grava `/etc/shf/staging.images` (guarda o anterior em `.previous`);
   - sobe as dependências, recria a **API** (Flyway no startup; espera a readiness) e depois o **web**;
   - recria caddy, lgtm e keycloak se os arquivos montados (Caddyfile, observabilidade, realm) mudaram desde o
     último deploy (o `install.sh` traz a versão nova; o deploy aplica);
   - confere que o web alcança a API e registra o resultado em `/var/lib/shf/deployments.log` (detalhes do último
     deploy em `/var/lib/shf/last-deploy.log`). Uma queda do SSH no meio não interrompe o deploy: confira o resultado
     no log.

Downtime esperado: dezenas de segundos (recreate). Shutdown gracioso de 30 s na API: os consumidores terminam a
mensagem em curso; uma mensagem interrompida volta para a fila e é reprocessada com idempotência (inbox).

## Deploy falhou

- **Antes do deploy** (SHA fora da `main`, workflow não verde, digest ausente): nada mudou no host. Corrija e rode de
  novo.
- **No host**: a mensagem diz o passo (`pull`, `api (readiness)`, `web`). O staging pode ficar com a API nova parada.
  Veja os logs (`sudo docker compose -p shf-staging logs --tail=300 api`) e faça o **rollback**.
- **Smoke test externo**: o deploy no host terminou, mas algo público falhou (TLS, CSP, IdP). Investigue ou faça o
  rollback.

## Rollback

O rollback é **redeploy de um digest anterior**. Não existe rollback automático do banco.

1. No host: `sudo grep ' OK ' /var/lib/shf/deployments.log | tail -n 5` → escolha o último deploy bom.
2. Pelo GitHub (preferido): Actions → **Deploy staging** com aquele SHA.
   Ou no host: `sudo shf-deploy <sha> <digest-api> <digest-web>` (o próprio `shf-deploy` imprime o comando de volta
   quando falha).
3. Antes de voltar, confira o intervalo entre os dois SHAs:
   - **Migrations**: só expansões compatíveis (coluna nova anulável ou com default, tabela nova) permitem voltar. O
     Flyway ignora migrations "futuras" e o `ddl-auto: validate` aceita colunas a mais. Uma contração (remover
     coluna/tabela, tornar `NOT NULL`) no intervalo **impede** o rollback: corrija para frente.
   - **Rebuilds de startup** (`ANALYTICS_REBUILD=missing`, `MERCHANT_RENORMALIZE=outdated`): são idempotentes, mas se a
     versão do normalizador ou da projeção mudou no intervalo, o código antigo pode reprocessar. Veja
     `merchant-renormalization.md` e `analytics-rebuild.md`.

## Regras

- Deploy sempre por digest; nunca `:latest` nem tag mutável.
- Um deploy por vez (lock no host; `concurrency` no workflow).
- O deploy não altera arquivos do host executados como root: compose, Caddyfile e scripts mudam só pelo `install.sh`
  (`staging.md`).
- Nunca rode o seed como parte do deploy: o seed é manual (`sudo shf-seed`).
