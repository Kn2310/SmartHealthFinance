# Runbook — Keycloak (IdP do staging)

Referência: ADR-0043 (Keycloak autogerenciado, realm endurecido), ADR-0044 §2 (proxy, alias de rede, Mailpit, client
de seed), ADR-0007/0008 (BFF e logout por back-channel). Operação geral do host: `staging.md`.

## O que está publicado

O Caddy publica só `https://auth.staging.<domínio>/realms/smart-health-finance/*` e `/resources/*`. O realm `master`,
`/admin`, `/metrics` e `/health` respondem 404 (o smoke test confere). O console de administração só pelo túnel SSH:
`http://127.0.0.1:8180/admin`, com o admin de bootstrap do env file.

## Realm como código

- Arquivo: `infrastructure/staging/keycloak/realm-smart-health-finance.json` (sem usuários e sem segredos; o segredo
  do client de seed e o nome do app vêm do env file por placeholder).
- Importado **só se o realm não existir** (`start --import-realm`). Mudar o arquivo não altera um realm já
  importado.
- Para aplicar uma mudança no staging: PR no arquivo → aplique a mesma mudança no console (túnel) **ou** faça
  `shf-reset` (reimporta tudo e refaz o seed). Registre no PR qual dos dois foi feito.
- O realm local (`infrastructure/keycloak/realm-smart-health-finance.json`) nunca é usado no staging.

### Configuração (ADR-0043)

| Item | Staging |
|---|---|
| Cadastro | **desligado** (ADR-0044 §2 item 9): só usuários sintéticos |
| Verificação de e-mail / recuperação de senha | ligadas; e-mails no Mailpit |
| Política de senha | 12+ caracteres, diferente de e-mail e usuário, histórico de 3 |
| Força bruta | 5 falhas → espera progressiva até 15 min, sem bloqueio permanente |
| MFA | TOTP opcional (pela conta do usuário, `/realms/smart-health-finance/account`) |
| Tokens | access 5 min; SSO 30 min inativo / 7 dias máximo; rotação de refresh desligada |
| `shf-web` | público, PKCE S256, só fluxo de código, redirect exato `https://<app>/auth/callback`, sem post-logout, sem web origins |
| `shf-staging-seed` | confidencial, password grant, **desligado** fora do `shf-seed` |
| Idioma | pt-BR padrão |

## Verificações depois do primeiro deploy (Parte D)

1. Login pela app com a usuária sintética → `/home`.
2. **Logout por back-channel (ADR-0008)**: sair pela app → `/auth/signed-out`; em seguida, abrir a app de novo pede
   login (sessão SSO encerrada). No console (túnel) → Sessions: a sessão sumiu.
3. **Recuperação de senha**: "Esqueci a senha" com a usuária sintética → o e-mail aparece no Mailpit (túnel) e o
   link funciona.
4. Client de seed: `Clients → shf-staging-seed` aparece **desligado** depois do `shf-seed`.
5. TOTP opcional: configurar na conta do Bruno sintético e confirmar que o login passa a pedir o código.

Registre o resultado no PR/issue da Parte D ou no fechamento da Fase 9.

## Testar o fluxo de cadastro (raro)

O cadastro fica desligado no staging. Para testar a tela: ligue `User registration` no console, cadastre um endereço
`@example.com`, confirme pelo Mailpit e **desligue em seguida**; apague o usuário criado. O fluxo também é coberto
pelo E2E local (ADR-0011).

## Admin de bootstrap

O Keycloak 26 avisa que o admin de bootstrap é temporário. No staging ele é o único admin e fica só no túnel. Trocar a
senha: atualize `KEYCLOAK_ADMIN_PASSWORD` **e** a senha no console (o env só vale no primeiro start do banco).

## Upgrade

Versão fixada por digest no `infrastructure/staging/compose.yml` (linha 26.x, a mesma do local). Upgrade = PR com o
novo digest → `install.sh` → redeploy. Antes: `sudo shf-backup` (o dump do Keycloak permite voltar).
