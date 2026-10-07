# ADR-0007 — Fundação do frontend: Next.js BFF com sessão em cookie HttpOnly

## Status

Accepted

> Registra as decisões da primeira implementação do frontend (Home / Financial Overview), revisadas após a
> auditoria técnica de 2026-10-07 e aprovadas na mesma data. A direção "Next.js BFF + cookie HttpOnly" foi
> aprovada fora do repositório; este ADR a formaliza e documenta os detalhes que ela não definia. O logout de
> produção está no ADR-0008.

## Context

As specs (05.1, 05.13 §20) definem Next.js/TypeScript, API client tipado e "frontend não calcula regras
financeiras". O ADR-0002 define a API como Resource Server stateless e o Keycloak local com cliente público
`shf-web` (PKCE). Não estavam definidos: onde os tokens vivem no browser, quem chama a API, como o Workspace é
descoberto, como o período chega ao Overview, a política de CSP e como estilizar com os tokens de `design/`.

## Decision

### 1. BFF no próprio Next.js (Route Handlers), sem serviço novo

O browser só fala com `/auth/*` e `/api/bff/*`; apenas o servidor Next.js fala com a API Spring e com o IdP.
Nenhum token chega ao JavaScript do browser (nem localStorage/sessionStorage). Verificado no browser: o cookie de
sessão não aparece em `document.cookie`.

### 2. Login: authorization code + PKCE (S256), `state`, sem `nonce`

- `GET /auth/login` gera `state` (24 bytes) e `code_verifier` (48 bytes), guarda os dois num cookie de fluxo
  selado (`shf_oidc`, 10 min, uso único) e redireciona ao IdP com `code_challenge` S256.
- `GET /auth/callback` exige o cookie de fluxo e `state` igual; troca o código com o `code_verifier`; chama
  `POST /api/v1/users/me` (ADR-0002/0003) e grava a sessão com o `workspaceId` pessoal. Qualquer falha vai para
  `/auth/error` (mensagem genérica), nunca para 500.
- **Sem `nonce`, por decisão.** O `nonce` protege contra injeção/replay de **`id_token`**. Este fluxo não consome
  o `id_token`: identidade e Workspace vêm do provisionamento na API, que valida o access token. A ligação entre
  a requisição de login e o callback é feita por `state` (CSRF de login) + PKCE (código interceptado não serve sem
  o verifier); o código é de uso único no IdP e o cookie de fluxo é consumido. **Gatilho para rever:** se o app
  passar a ler claims do `id_token` (ou a enviá-lo como `id_token_hint`), o `nonce` passa a ser obrigatório,
  junto com a validação do `id_token`.

### 3. Sessão = cookie HttpOnly selado

- `shf_session`: HttpOnly, SameSite=Lax, Path=/, 7 dias; **Secure sempre que `APP_BASE_URL` é HTTPS**.
- JSON → deflate → AES-256-GCM com chave derivada (HKDF) de `SESSION_SECRET` e finalidade (`shf_session` ×
  `shf_oidc` não se misturam). Adulteração ou segredo errado = sem sessão.
- Conteúdo: access token, refresh token, expiração, `workspaceId`, nome de exibição. **Sem e-mail.**
- **Tamanho:** medido no Keycloak local, a sessão selada tem 1.727 caracteres (limite do código: 3.800). Acima do
  limite, `SessionTooLargeError` é tratado: no login vira `/auth/error?reason=session`; no refresh, a sessão é
  encerrada e o cliente recebe 401 (novo login). Nunca 500.

### 4. Ciclo de vida do token

- O BFF renova o access token quando faltam menos de 30 s (`refresh_token`).
- Refresh **recusado** pelo IdP (4xx) → apaga a sessão → 401 → novo login.
- IdP **indisponível** (rede/5xx) → a sessão é mantida e a requisição falha com 502 temporário (não desloga o
  usuário por uma oscilação do IdP).
- **Concorrência (classificação: Low, só documentar):** duas requisições simultâneas podem renovar ao mesmo tempo.
  No realm atual `revokeRefreshToken` está desligado (padrão do Keycloak): as duas renovações são aceitas e o
  último cookie gravado vence — sem efeito para o usuário. Um lock por processo não protegeria várias instâncias e
  a Home faz uma requisição por tela. **Gatilho para rever:** ligar a rotação de refresh token no IdP de produção
  ou várias telas com requisições paralelas; aí a opção é sessão no servidor (ADR-0008, alternativa B).

### 5. Logout

Decidido no **ADR-0008**: `POST /auth/logout` encerra a sessão no IdP por back-channel (refresh token), revoga o
refresh token, apaga o cookie e volta para `/auth/signed-out`.

### 6. CSRF

- Rotas que mudam estado: só `POST /auth/logout` (destino fixo). O cookie é SameSite=Lax: não vai em POSTs
  cross-site. Um POST forjado no máximo desloga o usuário (impacto baixo).
- `GET /auth/login` é seguro de disparar (só inicia um fluxo; o callback exige o cookie de fluxo).
- **Regra para novos endpoints do BFF que alterem dados:** exigir `POST`/`PUT`/`DELETE` + checagem de `Origin`
  igual a `APP_BASE_URL` (ou token CSRF). SameSite=Lax sozinho não cobre subdomínios irmãos.

### 7. Workspace

O `workspaceId` nunca vem do browser: o BFF usa o da sessão selada; um `workspaceId` na query é ignorado (há
teste). O browser não tem token, então não consegue chamar a API diretamente com outro Workspace; a API ainda
checa a membership (ADR-0003).

### 8. BFF por necessidade de tela, sem cache

`GET /api/bff/overview?period&from&to` repassa uma única chamada a `GET /api/v1/workspaces/{id}/overview` e
devolve o corpo do backend sem recálculo. **Toda resposta do BFF e de `/auth/*` é `Cache-Control: no-store`**
(200, 400, 401, 502 e redirects). Falhas viram códigos genéricos (`UNAUTHENTICATED`, `INVALID_PERIOD`,
`UNAVAILABLE`); mensagens, IDs e detalhes do backend não são repassados nem logados.

### 9. Configuração segura

`NODE_ENV=production` com `APP_BASE_URL` em `http://` **impede o servidor de subir** (validação no
`instrumentation.ts`; verificado: "Failed to prepare server … APP_BASE_URL precisa usar https://"). Não há
correção automática. Exceção explícita e restrita: `SHF_ALLOW_INSECURE_LOCALHOST=true` **e** host de loopback
(`localhost`, `127.0.0.1`, `[::1]`), para rodar o build de produção na própria máquina.

### 10. Content Security Policy

HttpOnly impede o roubo do token, mas um XSS poderia usar o BFF como o próprio usuário. Política aplicada por
`src/proxy.ts` com **nonce por requisição** (todas as páginas são dinâmicas: `await connection()` no layout raiz):

```text
default-src 'self'; script-src 'self' 'nonce-…' 'strict-dynamic'; style-src 'self' 'nonce-…';
img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self';
form-action 'self'; frame-ancestors 'none'; upgrade-insecure-requests (só em HTTPS)
```

- **Produção:** sem `unsafe-inline` e sem `unsafe-eval`. Verificado no build de produção contra o Keycloak e a
  API locais: zero violações, todos os scripts com nonce, fontes carregadas, login, navegação e logout
  funcionando. O app não usa atributo `style` (o Skeleton usa classes) justamente para não precisar de
  `unsafe-inline`.
- **Exceções só em desenvolvimento (`next dev`):** `'unsafe-eval'` em `script-src` (o React usa `eval` para
  reconstruir stacks de erro — documentação do Next.js) e `style-src 'self' 'unsafe-inline'` sem nonce (o
  Turbopack/HMR injeta CSS inline sem nonce; verificado que a política estrita o bloqueia).
- `connect-src 'self'`: o browser não fala com o IdP nem com a API. Navegações para o IdP (login) não são
  restringidas pela CSP; `form-action 'self'` vale porque o logout termina em rota do app (ADR-0008).

### 11. Contrato da API

- `src/lib/api/schema.d.ts` é o **contrato manual e temporário** do frontend. Motivo verificado: o OpenAPI do
  springdoc gera `state?: string` e `totalBalance?: MoneyDto` — enums viram `string` e os nulos desaparecem, o
  que apagaria a distinção null ≠ zero.
- Drift: `contract.ts` espelha o contrato em tempo de execução (com checagem de tipos contra o `schema.d.ts`) e
  `contract.test.ts` compara com os records e enums Java do backend (monorepo). Campo ou valor de enum novo no
  backend quebra o teste.
- `pnpm api:generate` (script Node, funciona no Windows) gera `openapi.generated.d.ts` **separado**, não
  versionado e não importado, só para comparação. **Gatilho para virar fonte de verdade:** o backend anotar
  enums e nulos no OpenAPI.
- Enums desconhecidos não quebram a UI: rótulo e ícone neutros, sem sinal, estado desconhecido com mensagem
  neutra (há testes).

### 12. Dinheiro

Valores chegam como string decimal e são apenas reformatados (`lib/format/money.ts`), sem `number`. `null` (sem
dados) nunca vira zero. Casas além da 2ª, se existirem, são preservadas (sem truncar nem arredondar).

### 13. Estilo

CSS Modules + CSS variables. `scripts/sync-design.mjs` copia `design/tokens/tokens.css` e materializa a escala
tipográfica e o grid a partir de `design/tokens/tokens.json` (`--type-*`, `--font-size-*`, `--grid-*`). Cópias
não versionadas; nenhuma escala paralela no frontend. Breakpoints só do DS (640 / 1024 / 1440). Sem Tailwind.

### 14. Estado de servidor e rotas

`fetch` + hook (`useOverview`), com o período na URL; sem biblioteca de cache. Rotas ainda inexistentes
referenciadas pela Home respondem "Em breve" (estado *Unavailable* do DS), não 404.

## Alternatives

- **Browser chamando a API com o token (SPA pública + PKCE no browser):** rejeitado; expõe o token ao JS.
- **Sessão no servidor (Redis):** adiada (ADR-0008, alternativa B).
- **Auth.js/NextAuth:** rejeitado para não esconder o fluxo já definido (ADR-0002).
- **CSP com `unsafe-inline` em produção:** rejeitado; anularia a proteção contra XSS.
- **CSP estática no `next.config` (sem nonce):** rejeitada; o App Router precisa de nonce para os scripts inline
  do próprio Next sem `unsafe-inline`.
- **Gerar o schema do OpenAPI atual:** rejeitado até o backend declarar enums e nulos.
- **Tailwind / biblioteca de componentes:** rejeitados; o DS próprio define tokens e componentes.

## Consequences

- Positivas: tokens fora do alcance do JS; CSP estrita em produção; isolamento de Workspace no servidor;
  produção não sobe em HTTP; contrato protegido contra drift; uma requisição por Home.
- Negativas: todas as páginas são renderizadas dinamicamente (exigência do nonce); o cookie selado não é
  revogável individualmente (mitigado pelo ADR-0008); o contrato manual exige o teste de drift.

## Security / Privacy

- Cookie HttpOnly + SameSite=Lax + Secure (HTTPS); logout só por POST; `state` + PKCE; cookie de fluxo de uso
  único; sessão autenticada (AES-GCM) e sem e-mail.
- `SESSION_SECRET` só em ambiente (`.env.local`, ignorado pelo Git; modelo em `.env.example`).
- Logs do BFF só com status HTTP — sem valores, tokens, nomes ou IDs de usuário.
- Cabeçalhos: CSP, `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`; `no-store` em BFF e `/auth/*`.

## Financial Integrity

Nenhuma regra financeira no frontend: saldo, fluxo, estados (`NO_ACCOUNTS`, `NO_TRANSACTIONS`,
`NO_ACTIVITY_IN_PERIOD`, `READY`) e sinais (`flow`) vêm do backend (ADR-0006). PENDING aparece separado do
realizado. A UI só formata e apresenta.

## Operational Impact

- Variáveis: `APP_BASE_URL`, `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, `API_BASE_URL`, `SESSION_SECRET` (e, só
  local, `SHF_ALLOW_INSECURE_LOCALHOST`).
- O servidor Next.js precisa alcançar o IdP pelo `OIDC_ISSUER_URI` (discovery, token, logout).
- Staging/produção: o reverse proxy (specs 05.5) expõe o Next.js com TLS e mantém a API fora do alcance do browser.

## Related Specs

- `specs/05-system-design/05.13-consolidated-system-design.md` (§20), `05.5-infrastructure-security.md`
- `specs/04-design-system/design-system.md`, `accessibility.md`
- `design/specs/01-foundations.md`, `02-components.md`, `03-screens.md`, `05-states.md`, `06-responsive.md`
- `docs/adr/ADR-0002`, `ADR-0003`, `ADR-0006`, `ADR-0008`

## Date

2026-10-07
