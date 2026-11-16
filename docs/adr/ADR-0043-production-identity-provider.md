# ADR-0043 — IdP de staging e produção: Keycloak autogerenciado por ambiente

## Status

Accepted

> Decisões aprovadas pelo usuário em 2026-11-16 (Fase 9, Parte B): Keycloak autogerenciado, uma instância por
> ambiente no host do próprio ambiente; MFA por TOTP opcional; rotação de refresh token desligada no MVP. Cumpre o
> "IdP de staging/produção em ADR próprio" do ADR-0002 §2.

## Context

O ADR-0002 deixou o IdP de staging e produção para um ADR próprio. Desde então, outras decisões passaram a depender
do comportamento do IdP:

- **ADR-0007:** BFF Next.js com cliente público `shf-web` e PKCE, sessão em cookie HttpOnly selado (access e refresh
  token, sem e-mail), renovação pelo servidor e gatilho para rever a sessão se a rotação de refresh token for ligada.
- **ADR-0008:** o logout encerra a sessão SSO de servidor para servidor (`end_session_endpoint` com `client_id` e
  `refresh_token`, comportamento do Keycloak) e revoga o refresh token (RFC 7009). A página final é do app, o que
  mantém a CSP com `form-action 'self'`.
- **ADR-0002 §3–4:** a projeção local do usuário usa a chave `(oidc_issuer, oidc_subject)`, e o provisionamento é
  `POST /api/v1/users/me`.
- **05.11 e 05.5:** single host no MVP, ambientes isolados, dados sintéticos em staging, segredos injetados, acesso
  restrito.
- **05.12:** o IdP guarda dados pessoais (e-mail, nome, hash de senha, segredo de TOTP) e entra na PIA. Hospedá-lo
  fora do Brasil é transferência internacional.

Foram comparados, com custo, operação, região, MFA, recuperação, rotação, logout e migração:

- Keycloak autogerenciado;
- Keycloak gerenciado (Skycloak, Phase Two, Cloud-IAM);
- IdPs SaaS: Amazon Cognito (`sa-east-1`), Auth0 e Zitadel Cloud.

## Decision

### 1. Provedor

1. **Keycloak autogerenciado**, na mesma linha de versão do ambiente local (26.x). A imagem oficial
   `quay.io/keycloak/keycloak` é fixada por versão e digest, como as demais imagens (05.11). O upgrade é deliberado,
   com runbook.
2. **Uma instância por ambiente**, no host do próprio ambiente (staging e produção não compartilham IdP, banco,
   realm, usuários nem segredos). A topologia (proxy, rede e recursos) é decidida no ADR da Parte C.
3. **Banco próprio:** um database e um usuário de banco só do Keycloak, separados do banco da aplicação. Nada da
   aplicação lê as tabelas do Keycloak.
4. **Nenhuma mudança de código na API nem no BFF.** O provisionamento (ADR-0002), a sessão (ADR-0007) e o logout
   (ADR-0008) continuam como estão. O logout por back-channel deve ser verificado de novo no staging.

### 2. Endereços, realm e cliente por ambiente

5. O IdP é publicado só pelo proxy reverso com TLS, num subdomínio próprio do ambiente (ex.: `auth.<domínio>` e
   `auth.staging.<domínio>`; os nomes finais saem da Parte C).
   - Configuração: `--hostname https://auth.<domínio>`, `--proxy-headers xforwarded` e `--http-enabled true`
     (TLS terminado no proxy, conforme a documentação do Keycloak). Os cabeçalhos `X-Forwarded-*` são reescritos
     pelo proxy.
   - **Console de administração fora da internet:** acesso só pela rede interna ou por túnel SSH. O proxy publica
     só `/realms/*` (discovery, login, `token`, `logout`, `revoke`, `certs`) e os recursos das telas; bloqueia
     `/admin`, `/metrics` e `/health`.
   - O discovery devolve endpoints com o hostname público. A API e o BFF, no mesmo host, chegam ao IdP por esse
     hostname, passando pelo proxy. Se o tráfego deve ficar dentro do host (resolução interna do hostname, sem mudar
     o issuer), isso é decidido na Parte C.
6. **Realm `smart-health-finance`** em cada ambiente. O issuer é diferente por ambiente:
   - API: `OIDC_ISSUER_URI=https://auth.<domínio>/realms/smart-health-finance`;
   - BFF: `OIDC_ISSUER_URI` e `OIDC_CLIENT_ID=shf-web`.
   - Um token de staging nunca é aceito em produção (issuer e chaves distintos).
7. **Cliente `shf-web`:** público, com PKCE S256 obrigatório e só o fluxo de código.
   - `directAccessGrants` desligado (o password grant fica só no realm local, ADR-0002 §2).
   - `redirectUris`: exatamente `https://<app>/auth/callback`, sem curinga.
   - `post.logout.redirect.uris`: vazio. O logout é só de servidor para servidor (ADR-0008) e nunca redireciona o
     navegador ao IdP, então nenhum destino de saída é permitido (menor privilégio). O realm local continua com o
     curinga de `localhost`.
   - `webOrigins`: vazio. O browser nunca fala com o IdP por XHR (ADR-0007, `connect-src 'self'`).
8. **Realm como código:** um modelo versionado por ambiente, sem usuários nem segredos, importado no primeiro
   start só se o realm não existir. Mudanças posteriores no realm entram por runbook e são registradas no
   repositório. O realm local continua como está.

### 3. Conta, senha e MFA

9. **Cadastro:** aberto (`registrationAllowed`), com e-mail como usuário e **verificação de e-mail obrigatória**
   (`verifyEmail`).
10. **Recuperação de senha** por e-mail (`resetPasswordAllowed`). Os e-mails do Keycloak (verificação e
    recuperação) exigem um provedor SMTP, decidido no ADR da Parte C. `registrationAllowed`, `verifyEmail` e
    `resetPasswordAllowed` só são ligados depois que o SMTP do ambiente estiver configurado e testado (critério de
    aceite da Parte D). Antes disso, só existem os usuários fictícios do seed.
11. **Política de senha:** no mínimo 12 caracteres, diferente do e-mail e do usuário, histórico das últimas 3,
    hash padrão do Keycloak 26.
12. **Proteção contra força bruta** ligada: bloqueio temporário progressivo depois de falhas seguidas, sem bloqueio
    permanente.
13. **MFA por TOTP opcional:** a pessoa pode configurar um app autenticador. Quem configurou passa a ter o TOTP pedido
    em todo login. Exigir MFA de todos fica para decisão do M13. WebAuthn fica para depois.
14. **Idioma:** internacionalização ligada, com pt-BR como padrão nas telas e nos e-mails do Keycloak.

### 4. Tokens e sessão

15. **Access token: 5 min** (igual ao local). É a janela residual depois do logout (ADR-0008).
16. **Sessão SSO:** inatividade de 30 min e duração máxima de 7 dias.
    - **Limite efetivo:** quem fica mais de 30 min sem nenhuma requisição faz login de novo. O refresh token está
      ligado à sessão SSO: o Keycloak recusa a renovação (`invalid_grant`), o BFF apaga a sessão e responde 401
      (ADR-0007 §4). Nada se perde além do login. Com uso contínuo, cada renovação (a cada ~5 min) reinicia o tempo de
      inatividade, até o teto de 7 dias. O cookie `shf_session` de 7 dias (ADR-0007 §3) é só o teto, nunca o limite.
    - O realm local não define esses valores e usa os padrões do Keycloak (30 min de inatividade, 10 h no máximo).
    - Uma sessão longa ("lembrar de mim") é decisão futura.
17. **Rotação de refresh token desligada no MVP** (`revokeRefreshToken=false`). Os tokens ficam só no servidor
    Next.js, dentro do cookie selado, nunca no JavaScript do browser. O gatilho do ADR-0007 §4 continua valendo: ligar
    a rotação (com `refreshTokenMaxReuse`) exige rever a sessão (sessão no servidor, ADR-0008, alternativa B).
18. **Claims mínimos:** só `sub`, `email`, `email_verified` e os de nome, como hoje (ADR-0002 §6). Sem roles de
    negócio nem dados financeiros no token.

### 5. Isolamento entre staging e produção

19. Hosts, instâncias, bancos, realms, chaves de assinatura, segredos de administração e SMTP separados. Nenhum
    export de produção é importado em staging.
20. **Staging só com usuários fictícios** (05.11), criados pelo seed da Parte D. Produção não é criada nesta fase.

### 6. Migração a partir do local

21. Do Keycloak local para o de staging não há migração de usuários (não há usuários reais). O que muda é só o
    realm (modelo endurecido acima) e as variáveis de ambiente.
22. Para trocar de IdP no futuro, a chave `(oidc_issuer, oidc_subject)` (ADR-0002 §3) permite adotar um novo issuer
    sem migrar a chave primária. A migração de contas exigiria um ADR próprio.

## Alternatives

- **Keycloak gerenciado (Skycloak, Phase Two, Cloud-IAM):** mesma compatibilidade, sem a operação. Preterido pelo
  custo (de cerca de US$ 29 a € 225 por mês, por ambiente) e pelos dados nos EUA ou na UE (transferência
  internacional).
- **Amazon Cognito em São Paulo:** dados no Brasil, grátis até 10 mil MAU, MFA e e-mail embutidos, sem operação.
  Preterido porque o logout por servidor com o refresh token não existe nele: seria preciso revogar e redirecionar o
  navegador para o `/logout` do domínio do Cognito, o que muda o ADR-0008 e conflita com `form-action 'self'` da
  CSP (ADR-0007). Também traz conta e dependência da AWS e telas de login menos customizáveis. Continua sendo a
  alternativa se a operação do Keycloak pesar.
- **Auth0 e Zitadel Cloud:** sem região no Brasil (transferência internacional); Auth0 cobra por MAU desde o
  começo. Preteridos.
- **MFA obrigatório já no MVP:** mais seguro, mas aumenta o atrito e os pedidos de recuperação. Adiado para o M13.
- **Rotação de refresh token com 1 reuso:** tolera renovações em paralelo, mas é mudança de comportamento da sessão
  (gatilho do ADR-0007). Adiada.
- **Um Keycloak compartilhado com dois realms (staging e produção):** rejeitado; mistura ambientes (05.11).

## Consequences

- Positivas:
  - nenhuma mudança de código; os ADRs 0002, 0007 e 0008 valem como estão;
  - dados do IdP no mesmo país e host do ambiente (Brasil, se o VPS da Parte C for no Brasil);
  - custo marginal (memória do host), sem cobrança por usuário;
  - realm como código, revisável em PR.
- Negativas:
  - a operação do IdP é nossa: atualizações de segurança, upgrade de versão, backup e restore do banco do Keycloak;
  - no single host, o IdP cai junto com o host: login e renovação param, e a sessão já aberta continua até o
    refresh ser necessário (ADR-0007 §4, IdP indisponível = 502 temporário);
  - depende de um provedor SMTP para cadastro e recuperação;
  - sem rotação de refresh token: um cookie copiado antes do logout vale até o access token expirar (5 min); um
    cookie copiado sem logout vale enquanto a sessão SSO estiver viva (até 30 min de inatividade, no máximo 7 dias),
    mitigado pelo cookie HttpOnly e selado;
  - novo login depois de 30 min sem uso (§16).

## Security / Privacy

- **Dados pessoais no Keycloak:** e-mail, nome, hash de senha, segredo de TOTP, eventos de login. Entram na PIA
  (Parte F), na política de retenção e nos backups. A exclusão de conta (M13) precisa apagar também o usuário do
  Keycloak.
- **Eventos de login:** se ligados, só pelo tempo necessário à segurança (proposta de retenção na PIA); nunca vão aos
  logs da aplicação.
- Console de administração sem exposição pública; credencial de bootstrap do admin trocada no primeiro acesso e
  guardada como segredo do ambiente; nenhum segredo no realm versionado.
- Cliente público sem segredo; PKCE obrigatório; redirect URIs exatas; sem password grant fora do local.
- TLS só no proxy, com o Keycloak na rede interna; `sslRequired` = `external` no mínimo e o cookie do Keycloak com
  `Secure`.

## Financial Integrity

Sem impacto. O IdP não conhece Workspaces, saldos nem dados financeiros (JWT mínimo, ADR-0002 §6).

## Operational Impact

- Variáveis já existentes: `OIDC_ISSUER_URI` (API e BFF) e `OIDC_CLIENT_ID` (BFF) por ambiente. Variáveis novas
  do próprio Keycloak (banco, hostname, admin de bootstrap e SMTP) entram no `.env.staging.example` da Parte D.
- Healthcheck do Keycloak (`KC_HEALTH_ENABLED`) e métricas na porta de gerenciamento, só na rede interna.
- Runbook novo na Parte D: upgrade e atualização de segurança, backup e restore do banco do Keycloak, rotação da
  credencial de admin, desbloqueio de conta e reenvio de verificação.
- Na Parte D, verificar em staging: login, cadastro com e-mail, recuperação de senha, TOTP opcional, logout
  (`end_session` com refresh token → 204; refresh seguinte → `invalid_grant`).

## Related Specs

- `docs/adr/ADR-0002-oidc-identity-provider.md` (§2: IdP de staging/produção em ADR próprio)
- `docs/adr/ADR-0007-frontend-foundation-and-bff-auth.md` (§4 gatilho da rotação; §3 cookie; CSP)
- `docs/adr/ADR-0008-production-logout.md` (back-channel exigido do IdP de produção)
- `specs/05-system-design/05.5-infrastructure-security.md`, `05.11-devops-deployment.md`,
  `05.12-lgpd-privacy-data-governance.md`
- `infrastructure/keycloak/realm-smart-health-finance.json` (realm local, inalterado)

## Date

2026-11-16
