# ADR-0002 — Autenticação via IdP OIDC externo e Identity como projeção local

## Status

Accepted

## Context

As specs (05.5, 05.13) definem OAuth2/OIDC, JWT mínimo e autorização por Workspace, mas não definem:

- onde as credenciais do usuário ficam;
- como o usuário do sistema se relaciona com a identidade do IdP;
- quando o usuário local é criado.

O `SecurityConfig` já referencia esta decisão.

## Decision

1. **A API é um OAuth2 Resource Server stateless.** Ela valida JWTs emitidos por um IdP OIDC externo
   (`spring.security.oauth2.resourceserver.jwt.issuer-uri`). A API não armazena senhas.
2. **O IdP em desenvolvimento local é o Keycloak** (`infrastructure/docker-compose.yml`, realm
   `smart-health-finance`, cliente público `shf-web` com PKCE).
   - O password grant fica habilitado apenas no realm local, para testes via `curl`.
   - O IdP de staging/produção será escolhido em ADR próprio.
3. **O módulo Identity mantém uma projeção local do usuário** (`users`), ligada ao IdP pela chave natural
   `(oidc_issuer, oidc_subject)`.
   - O `UserId` interno é um UUIDv7, gerado pela aplicação.
4. **O provisionamento é explícito e idempotente:**
   - `POST /api/v1/users/me` cria o usuário a partir dos claims (201) ou retorna o existente (200);
   - `GET /api/v1/users/me` é somente leitura e retorna 404 `USER_NOT_PROVISIONED` antes do provisionamento.
   - Concorrência resolvida no banco com `INSERT ... ON CONFLICT (oidc_issuer, oidc_subject) DO NOTHING`.
5. **Dono de cada campo:**
   - o e-mail pertence ao IdP e é sincronizado a cada provisionamento;
   - o `displayName` é inicializado pelo IdP e depois é editável localmente, sem ser sobrescrito.
6. **O JWT permanece mínimo:** o token não carrega entitlements nem dados financeiros.
   - Outros módulos obtêm o usuário corrente via `CurrentUserService.requireCurrentUserId()`.

## Alternatives

- **Autenticação própria com senha (Argon2id):** adiada. As specs permitem, mas trazem custo de MFA,
  recuperação de conta e proteção contra credential stuffing.
- **Provisionamento just-in-time no GET:** rejeitado. Faria um GET escrever no banco e esconderia o
  momento do cadastro.
- **Usar o `sub` do IdP como chave primária:** rejeitado. Acopla o modelo ao IdP e impede trocar de
  provedor ou ter mais de um issuer.

## Consequences

- Positivas: nenhuma senha na base da aplicação; troca de IdP sem migrar a chave primária; provisionamento
  auditável e seguro sob concorrência.
- Negativas: dependência operacional do IdP; o frontend precisa chamar `POST /users/me` após o login.

## Security / Privacy

- Nenhuma senha, CVV ou segredo é armazenado pela aplicação.
- O e-mail é dado pessoal (LGPD): não vai para logs; `Email.toString()` é mascarado; há teste que garante
  a ausência do e-mail nos logs.
- Tokens sem `iss`/`sub` resultam em 401. Tokens sem e-mail válido resultam em 422
  `IDENTITY_CLAIMS_INCOMPLETE`. Usuário desativado recebe 403 `USER_DISABLED`.

## Financial Integrity

Sem impacto direto. `UserId` é a base para associar usuários a Workspaces (M1).

## Operational Impact

- Local: Keycloak em `localhost:8180`, com o issuer fixado por `KC_HOSTNAME` para coincidir com o
  `OIDC_ISSUER_URI` da API.
- Staging/produção: `OIDC_ISSUER_URI` via env file / secret manager.
- A API busca as chaves JWK do IdP no primeiro request autenticado.

## Related Specs

- `specs/05-system-design/05.5-infrastructure-security.md`
- `specs/05-system-design/05.6-api-integration.md`
- `specs/05-system-design/05.12-lgpd-privacy-data-governance.md`
- `specs/05-system-design/05.13-consolidated-system-design.md`
- `specs/03-ux/core-user-flows.md` (Onboarding)

## Date

2026-09-28
