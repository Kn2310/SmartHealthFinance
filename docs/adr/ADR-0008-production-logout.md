# ADR-0008 — Logout de produção: encerramento da sessão no IdP pelo BFF

## Status

Accepted

## Context

A auditoria da Frontend Foundation (ADR-0007) classificou o logout como **P1**:

```text
POST /auth/logout → apaga o cookie local → redirect para o end_session do Keycloak
```

- Sem `id_token_hint`, o Keycloak mostra a tela "Deseja sair?". Se o usuário fechar a aba, a sessão SSO
  continua ativa e o próximo login entra **sem senha** (dispositivo compartilhado).
- O refresh token não é revogado. Uma cópia anterior do cookie selado continuaria renovando tokens até a sessão
  do IdP expirar.
- A sessão é um cookie selado sem estado no servidor (ADR-0007): o BFF não tem como "lembrar" que um cookie foi
  invalidado.

Restrições:

- cookie com limite prático de ~4 KB (o código recusa sessões seladas acima de 3.800 caracteres);
- cliente público `shf-web`, sem segredo (ADR-0002);
- IdP local Keycloak; IdP de produção ainda não escolhido (ADR-0002 §2);
- sem infraestrutura nova no frontend nesta etapa.

## Decision drivers

1. O logout precisa encerrar a sessão SSO **sem depender de interação** do usuário.
2. Um cookie copiado antes do logout deve parar de funcionar em pouco tempo.
3. Não aumentar o cookie para perto do limite.
4. Não introduzir armazenamento de sessão no frontend sem necessidade.

## Alternatives

### A. Guardar o `id_token` na sessão selada e enviar `id_token_hint` no redirect

- **Tamanho (medido no Keycloak local, usuária sintética, 2026-10-07):** access token 1.125, refresh token 693 e
  `id_token` 1.078 caracteres. Sessão selada atual: **1.727** caracteres; com o `id_token`: **2.408** (limite
  do código: 3.800). **Cabe**, com ~37% de folga — o tamanho não é impeditivo hoje. A folga diminui com claims
  novos (roles, grupos) e com um IdP de produção que emita tokens maiores.
- **Segurança (o motivo decisivo):** o `id_token_hint` só elimina a tela de confirmação do front-channel.
  **Não revoga o refresh token**: o driver 2 continua aberto. E guardar e enviar o `id_token` passaria a exigir
  validá-lo (assinatura, `aud`, `nonce`), coisa que hoje não é necessária porque o `id_token` é ignorado.
- **Expiração/revogação:** inalteradas; o cookie copiado segue válido até a sessão do IdP expirar.
- **Compatível com AES-GCM:** sim, mas só piora o tamanho.

### B. Sessão no servidor (id opaco no cookie, tokens em Redis/PostgreSQL)

- **Complexidade:** store de sessão, serialização, TTL, limpeza, tratamento de indisponibilidade.
- **Armazenamento:** Redis existe na infraestrutura, mas hoje só a API o usa. O Next.js passaria a depender de
  Redis (credenciais, rede, observabilidade).
- **Escalabilidade:** boa com Redis compartilhado; ruim com memória local (perde sessões a cada deploy, não
  funciona com mais de uma instância).
- **Invalidação:** imediata e completa: o melhor resultado para os drivers 1 e 2.
- **Infraestrutura:** nova dependência operacional do frontend. Desproporcional para o estágio atual (uma tela,
  um usuário por Workspace, sem gestão de dispositivos).

### C. Encerramento da sessão no IdP pelo BFF (back-channel), com o refresh token que já está na sessão

No `POST /auth/logout`, o servidor Next.js:

1. lê a sessão selada (se houver);
2. chama o IdP **de servidor para servidor** para encerrar a sessão do usuário usando o refresh token:
   - `POST {end_session_endpoint}` com `client_id` + `refresh_token` (logout iniciado pelo cliente,
     suportado pelo Keycloak, inclusive para clientes públicos); e
   - se o IdP anunciar `revocation_endpoint` (RFC 7009), revoga também o refresh token;
3. **sempre** apaga o cookie local, mesmo se o IdP falhar;
4. redireciona para `/auth/signed-out`, uma página do próprio app ("Você saiu da sua conta").

- **Tamanho:** nenhum byte a mais no cookie.
- **Segurança:** a sessão SSO termina sem a tela de confirmação (driver 1). O refresh token deixa de valer no IdP,
  então uma cópia antiga do cookie só funciona até o access token expirar (~5 min no realm local, `accessTokenLifespan`)
  e não consegue renovar (driver 2).
- **Infraestrutura:** nenhuma.
- **Limitações:**
  - o access token (JWT) continua aceito pela API até expirar: a API é um Resource Server stateless
    (ADR-0002). Janela residual = vida do access token;
  - se o IdP estiver indisponível no logout, o cookie local é apagado mas a sessão SSO só termina pelo timeout
    do IdP. A falha é registrada em log (sem tokens);
  - o encerramento por `refresh_token` no `end_session_endpoint` é comportamento do Keycloak; um IdP de produção
    diferente precisa oferecer o equivalente (revogação RFC 7009 ou back-channel). Isso entra no ADR do IdP de
    produção.

## Decision

**Alternativa C.** Resolve os dois problemas do P1 sem aumentar o cookie e sem nova infraestrutura. A sessão no
servidor (B) fica registrada como a evolução quando houver necessidade de revogação imediata, gestão de
dispositivos ou tokens grandes demais para o cookie. A alternativa A foi rejeitada: caberia no cookie (medido),
mas não revoga nada e traria a obrigação de validar o `id_token`.

**Verificado contra o Keycloak local (2026-10-07):** `POST end_session_endpoint` com `client_id=shf-web` +
`refresh_token` → **204**; um `refresh_token` usado em seguida → **400 `invalid_grant`** (sessão SSO encerrada).

Detalhes:

- logout continua **somente por `POST`** (um link externo não encerra a sessão);
- o redirect final é sempre para uma rota do app (`/auth/signed-out`), nunca para uma URL vinda da requisição;
- todas as respostas de `/auth/*` levam `Cache-Control: no-store`;
- o `id_token` continua não sendo usado; por isso o fluxo não usa `nonce` (ver ADR-0007).

## Consequences

- Positivas: logout encerra o SSO de verdade; refresh token morto no IdP; cookie inalterado; testável sem o IdP.
- Negativas: depende do comportamento de back-channel do IdP; janela residual igual à vida do access token; uma
  chamada de rede a mais no logout.

## Security / Privacy

- Os tokens só trafegam entre o servidor Next.js e o IdP (TLS em produção).
- Logs registram apenas o status da chamada ao IdP, nunca tokens nem identificadores pessoais.
- Falha do IdP não impede o logout local.

## Financial Integrity

Sem impacto.

## Operational Impact

- Nenhuma variável nova.
- O IdP precisa aceitar `post_logout` por back-channel para o cliente `shf-web` (Keycloak: padrão).
- Monitorar falhas de logout no IdP pelo log `logout idp call failed` (status apenas).

## Related

- `docs/adr/ADR-0002-oidc-identity-provider.md`
- `docs/adr/ADR-0007-frontend-foundation-and-bff-auth.md`
- `specs/05-system-design/05.5-infrastructure-security.md`

## Date

2026-10-07
