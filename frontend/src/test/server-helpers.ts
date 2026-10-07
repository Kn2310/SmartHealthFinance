import { vi } from 'vitest'

/** Opções gravadas por `cookies().set` (subconjunto que nos interessa nos testes). */
export interface CookieOptions {
  httpOnly?: boolean
  sameSite?: string
  secure?: boolean
  path?: string
  maxAge?: number
}

/** Substituto do cookie store de `next/headers` que registra o que foi gravado e apagado. */
export function createCookieStore(initial: Record<string, string> = {}) {
  const values = new Map(Object.entries(initial))
  const options = new Map<string, CookieOptions>()
  const deleted = new Set<string>()
  return {
    values,
    options,
    deleted,
    get: (name: string) => (values.has(name) ? { name, value: values.get(name)! } : undefined),
    set: (name: string, value: string, opts: CookieOptions = {}) => {
      values.set(name, value)
      options.set(name, opts)
      deleted.delete(name)
    },
    delete: (name: string) => {
      values.delete(name)
      deleted.add(name)
    },
  }
}
export type CookieStore = ReturnType<typeof createCookieStore>

export const TEST_ENV = {
  APP_BASE_URL: 'http://app.test',
  OIDC_ISSUER_URI: 'http://idp.test/realms/shf',
  OIDC_CLIENT_ID: 'shf-web',
  API_BASE_URL: 'http://api.test',
  SESSION_SECRET: 'test-secret-with-at-least-32-characters!!',
}

export function stubServerEnv(overrides: Partial<Record<keyof typeof TEST_ENV, string>> = {}) {
  for (const [key, value] of Object.entries({ ...TEST_ENV, ...overrides })) vi.stubEnv(key, value)
}

export const DISCOVERY = {
  authorization_endpoint: 'http://idp.test/realms/shf/protocol/openid-connect/auth',
  token_endpoint: 'http://idp.test/realms/shf/protocol/openid-connect/token',
  end_session_endpoint: 'http://idp.test/realms/shf/protocol/openid-connect/logout',
  revocation_endpoint: 'http://idp.test/realms/shf/protocol/openid-connect/revoke',
}

export interface RecordedRequest {
  url: string
  method: string
  headers: Headers
  body: string
}

type Handler = (request: RecordedRequest) => Response | Promise<Response>

/**
 * `fetch` global falso, roteado por URL (prefixo). Registra cada chamada. O discovery do IdP já vem respondido.
 * Rotas não mapeadas respondem 404, para nenhum teste depender de rede real.
 */
export function stubFetch(routes: Record<string, Handler>) {
  const calls: RecordedRequest[] = []
  const all: Record<string, Handler> = {
    'http://idp.test/realms/shf/.well-known/openid-configuration': () => Response.json(DISCOVERY),
    ...routes,
  }
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(input, init)
    const recorded: RecordedRequest = {
      url: request.url,
      method: request.method,
      headers: request.headers,
      body: request.body ? await request.text() : '',
    }
    calls.push(recorded)
    const match = Object.keys(all)
      .sort((a, b) => b.length - a.length)
      .find((prefix) => recorded.url.startsWith(prefix))
    return match ? all[match]!(recorded) : new Response('not found', { status: 404 })
  })
  vi.stubGlobal('fetch', fetchMock)
  return { calls, fetchMock }
}

export const form = (body: string) => Object.fromEntries(new URLSearchParams(body))
