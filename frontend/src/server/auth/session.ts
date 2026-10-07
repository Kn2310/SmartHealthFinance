import 'server-only'
import { cookies } from 'next/headers'
import { getEnv } from '../env'
import { refreshTokens, type TokenSet } from './oidc'
import { seal, unseal } from './seal'

export const SESSION_COOKIE = 'shf_session'
export const FLOW_COOKIE = 'shf_oidc'

const SESSION_MAX_AGE = 60 * 60 * 24 * 7
const FLOW_MAX_AGE = 60 * 10
/** Renova o access token um pouco antes de expirar. */
const REFRESH_SKEW_MS = 30_000
/** Cookies têm limite de ~4 KB por par nome=valor + atributos; acima disso o browser descarta em silêncio. */
export const MAX_SEALED_SESSION_LENGTH = 3800

export interface Session extends TokenSet {
  workspaceId: string
  displayName: string
}

export interface OidcFlow {
  state: string
  verifier: string
}

/** A sessão selada não cabe em um cookie. Tratado pelos chamadores (nunca vira 500). */
export class SessionTooLargeError extends Error {
  constructor(readonly length: number) {
    super('Sessão grande demais para um cookie')
    this.name = 'SessionTooLargeError'
  }
}

export function cookieOptions(maxAge: number) {
  return {
    httpOnly: true,
    sameSite: 'lax' as const,
    secure: getEnv().secureCookies,
    path: '/',
    maxAge,
  }
}

export async function writeSession(session: Session): Promise<void> {
  const value = seal(session, getEnv().sessionSecret, SESSION_COOKIE)
  if (value.length > MAX_SEALED_SESSION_LENGTH) throw new SessionTooLargeError(value.length)
  ;(await cookies()).set(SESSION_COOKIE, value, cookieOptions(SESSION_MAX_AGE))
}

export async function clearSession(): Promise<void> {
  ;(await cookies()).delete(SESSION_COOKIE)
}

/** Leitura sem efeitos (páginas): não renova tokens, pois Server Components não podem gravar cookies. */
export async function readSession(): Promise<Session | null> {
  const raw = (await cookies()).get(SESSION_COOKIE)?.value
  return raw ? unseal<Session>(raw, getEnv().sessionSecret, SESSION_COOKIE) : null
}

export type FreshSession =
  | { status: 'ok'; session: Session }
  /** Sem sessão válida: o cliente deve refazer o login. */
  | { status: 'none' }
  /** IdP indisponível ao renovar: a sessão é mantida e a requisição falha de forma temporária. */
  | { status: 'unavailable' }

/**
 * Para Route Handlers: devolve uma sessão com access token válido, renovando-o quando necessário.
 *
 * Concorrência (ADR-0007): duas requisições simultâneas podem renovar ao mesmo tempo. Com o realm atual
 * (`revokeRefreshToken` desligado, padrão do Keycloak) as duas renovações são aceitas e o último cookie gravado
 * vence. Não há lock: um lock por processo não protegeria várias instâncias, e a Home faz uma requisição por tela.
 */
export async function getFreshSession(): Promise<FreshSession> {
  const session = await readSession()
  if (!session) return { status: 'none' }
  if (session.expiresAt - REFRESH_SKEW_MS > Date.now()) return { status: 'ok', session }

  const result = await refreshTokens(session.refreshToken)
  if (!result.ok) {
    if (result.reason === 'unavailable') return { status: 'unavailable' }
    await clearSession()
    return { status: 'none' }
  }

  const renewed = { ...session, ...result.tokens }
  try {
    await writeSession(renewed)
  } catch (error) {
    if (!(error instanceof SessionTooLargeError)) throw error
    console.warn('session too large after refresh', { length: error.length })
    await clearSession()
    return { status: 'none' }
  }
  return { status: 'ok', session: renewed }
}

export async function writeFlow(flow: OidcFlow): Promise<void> {
  ;(await cookies()).set(FLOW_COOKIE, seal(flow, getEnv().sessionSecret, FLOW_COOKIE), cookieOptions(FLOW_MAX_AGE))
}

/** Lê e consome o cookie do fluxo (uso único). */
export async function takeFlow(): Promise<OidcFlow | null> {
  const store = await cookies()
  const raw = store.get(FLOW_COOKIE)?.value
  store.delete(FLOW_COOKIE)
  return raw ? unseal<OidcFlow>(raw, getEnv().sessionSecret, FLOW_COOKIE) : null
}
