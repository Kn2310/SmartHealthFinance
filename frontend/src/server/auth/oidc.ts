import 'server-only'
import { createHash, randomBytes } from 'node:crypto'
import { getEnv } from '../env'

/**
 * Cliente OIDC mínimo (authorization code + PKCE S256, cliente público `shf-web` — ADR-0002/0007).
 * Sem `nonce`: o `id_token` não é consumido; a ligação request↔callback é feita por `state` + PKCE (ADR-0007).
 */

interface Endpoints {
  authorization_endpoint: string
  token_endpoint: string
  end_session_endpoint?: string
  revocation_endpoint?: string
}

export interface TokenSet {
  accessToken: string
  refreshToken: string
  /** epoch ms em que o access token expira */
  expiresAt: number
}

/** `rejected`: o IdP recusou (refresh inválido/expirado). `unavailable`: rede/5xx — a sessão não deve ser descartada. */
export type TokenResult = { ok: true; tokens: TokenSet } | { ok: false; reason: 'rejected' | 'unavailable' }

const IDP_TIMEOUT_MS = 5_000

let cachedEndpoints: { issuer: string; value: Endpoints } | null = null

async function endpoints(): Promise<Endpoints> {
  const { issuer } = getEnv()
  if (cachedEndpoints?.issuer === issuer) return cachedEndpoints.value
  const res = await fetch(`${issuer}/.well-known/openid-configuration`, {
    cache: 'no-store',
    signal: AbortSignal.timeout(IDP_TIMEOUT_MS),
  })
  if (!res.ok) throw new Error('OIDC discovery indisponível')
  const value = (await res.json()) as Endpoints
  cachedEndpoints = { issuer, value }
  return value
}

/** Só para testes: esquece o discovery em cache. */
export function resetOidcCache() {
  cachedEndpoints = null
}

export const redirectUri = () => `${getEnv().appBaseUrl}/auth/callback`

export function createPkce() {
  const verifier = randomBytes(48).toString('base64url')
  const challenge = createHash('sha256').update(verifier).digest('base64url')
  return { verifier, challenge, state: randomBytes(24).toString('base64url') }
}

export async function buildAuthorizationUrl(challenge: string, state: string): Promise<string> {
  const { authorization_endpoint } = await endpoints()
  const url = new URL(authorization_endpoint)
  url.search = new URLSearchParams({
    response_type: 'code',
    client_id: getEnv().clientId,
    redirect_uri: redirectUri(),
    scope: 'openid profile email',
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  }).toString()
  return url.toString()
}

interface TokenResponse {
  access_token?: string
  refresh_token?: string
  expires_in?: number
}

async function postForm(url: string, params: Record<string, string>): Promise<Response> {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: getEnv().clientId, ...params }),
    cache: 'no-store',
    signal: AbortSignal.timeout(IDP_TIMEOUT_MS),
  })
}

async function requestTokens(params: Record<string, string>): Promise<TokenResult> {
  let res: Response
  try {
    res = await postForm((await endpoints()).token_endpoint, params)
  } catch {
    return { ok: false, reason: 'unavailable' }
  }
  if (res.status >= 500) return { ok: false, reason: 'unavailable' }
  if (!res.ok) return { ok: false, reason: 'rejected' }
  const body = (await res.json().catch(() => ({}))) as TokenResponse
  if (!body.access_token || !body.refresh_token) return { ok: false, reason: 'rejected' }
  return {
    ok: true,
    tokens: {
      accessToken: body.access_token,
      refreshToken: body.refresh_token,
      expiresAt: Date.now() + (body.expires_in ?? 300) * 1000,
    },
  }
}

export const exchangeCode = (code: string, verifier: string) =>
  requestTokens({ grant_type: 'authorization_code', code, code_verifier: verifier, redirect_uri: redirectUri() })

export const refreshTokens = (refreshToken: string) =>
  requestTokens({ grant_type: 'refresh_token', refresh_token: refreshToken })

/**
 * ADR-0008: encerra a sessão do usuário no IdP de servidor para servidor, com o refresh token da sessão.
 * - `end_session_endpoint` com `refresh_token` (logout iniciado pelo cliente; Keycloak encerra a sessão SSO);
 * - `revocation_endpoint` (RFC 7009), quando anunciado, revoga o refresh token.
 * Nunca lança: o logout local acontece de qualquer forma. Retorna se o IdP confirmou o encerramento.
 */
export async function endIdpSession(refreshToken: string): Promise<boolean> {
  try {
    const { end_session_endpoint, revocation_endpoint } = await endpoints()
    let ended = false
    if (end_session_endpoint) {
      const res = await postForm(end_session_endpoint, { refresh_token: refreshToken })
      ended = res.ok
      if (!res.ok) console.warn('logout idp call failed', { endpoint: 'end_session', status: res.status })
    }
    if (revocation_endpoint) {
      const res = await postForm(revocation_endpoint, { token: refreshToken, token_type_hint: 'refresh_token' })
      ended = ended || res.ok
      if (!res.ok) console.warn('logout idp call failed', { endpoint: 'revocation', status: res.status })
    }
    return ended
  } catch {
    console.warn('logout idp call failed', { endpoint: 'unreachable' })
    return false
  }
}
