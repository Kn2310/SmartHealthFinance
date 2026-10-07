// @vitest-environment node
import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createCookieStore, stubFetch, stubServerEnv, type CookieStore } from '@/test/server-helpers'

let store: CookieStore
vi.mock('next/headers', () => ({ cookies: async () => store }))

import { resetOidcCache } from '@/server/auth/oidc'
import { seal } from '@/server/auth/seal'
import { SESSION_COOKIE, type Session } from '@/server/auth/session'
import { GET } from './route'

/**
 * Caminho real do BFF: cookie selado → getFreshSession → openapi-fetch (real) → fetch. Só a rede é falsa.
 * Garante URL, query, Authorization e que o Workspace vem da sessão.
 */
const SECRET = 'test-secret-with-at-least-32-characters!!'
const session = (overrides: Partial<Session> = {}): Session => ({
  accessToken: 'session-access',
  refreshToken: 'session-refresh',
  expiresAt: Date.now() + 10 * 60_000,
  workspaceId: 'ws-da-sessao',
  displayName: 'Ana',
  ...overrides,
})
const overviewBody = { state: 'READY', summary: { totalBalance: { amount: '7627.52', currency: 'BRL' } } }

beforeEach(() => {
  stubServerEnv()
  resetOidcCache()
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('GET /api/bff/overview → API real (rede simulada)', () => {
  it('chama /api/v1/workspaces/{workspace da sessão}/overview com o período e o Bearer da sessão', async () => {
    store = createCookieStore({ [SESSION_COOKIE]: seal(session(), SECRET, SESSION_COOKIE) })
    const { calls } = stubFetch({ 'http://api.test/api/v1/workspaces/': () => Response.json(overviewBody) })

    const response = await GET(
      new NextRequest(
        'http://app.test/api/bff/overview?period=CUSTOM&from=2026-09-01&to=2026-09-10&workspaceId=ws-de-outra-pessoa',
      ),
    )

    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(await response.json()).toEqual(overviewBody)

    expect(calls).toHaveLength(1)
    const url = new URL(calls[0]!.url)
    expect(url.origin + url.pathname).toBe('http://api.test/api/v1/workspaces/ws-da-sessao/overview')
    expect(Object.fromEntries(url.searchParams)).toEqual({ period: 'CUSTOM', from: '2026-09-01', to: '2026-09-10' })
    expect(calls[0]!.method).toBe('GET')
    expect(calls[0]!.headers.get('authorization')).toBe('Bearer session-access')
    expect(calls[0]!.url).not.toContain('ws-de-outra-pessoa')
  })

  it('padrão: CURRENT_MONTH sem from/to', async () => {
    store = createCookieStore({ [SESSION_COOKIE]: seal(session(), SECRET, SESSION_COOKIE) })
    const { calls } = stubFetch({ 'http://api.test/api/v1/workspaces/': () => Response.json(overviewBody) })
    await GET(new NextRequest('http://app.test/api/bff/overview'))
    expect(Object.fromEntries(new URL(calls[0]!.url).searchParams)).toEqual({ period: 'CURRENT_MONTH' })
  })

  it('token expirando: renova no IdP e chama a API com o NOVO token', async () => {
    store = createCookieStore({ [SESSION_COOKIE]: seal(session({ expiresAt: 0 }), SECRET, SESSION_COOKIE) })
    const { calls } = stubFetch({
      'http://idp.test/realms/shf/protocol/openid-connect/token': () =>
        Response.json({ access_token: 'renovado', refresh_token: 'r2', expires_in: 300 }),
      'http://api.test/api/v1/workspaces/': () => Response.json(overviewBody),
    })
    const response = await GET(new NextRequest('http://app.test/api/bff/overview'))
    expect(response.status).toBe(200)
    const apiCall = calls.find((c) => c.url.startsWith('http://api.test'))!
    expect(apiCall.headers.get('authorization')).toBe('Bearer renovado')
  })

  it('erro do backend não vaza corpo e mantém no-store', async () => {
    store = createCookieStore({ [SESSION_COOKIE]: seal(session(), SECRET, SESSION_COOKIE) })
    stubFetch({
      'http://api.test/api/v1/workspaces/': () =>
        Response.json({ code: 'WORKSPACE_NOT_FOUND', message: 'interno', traceId: 'abc' }, { status: 404 }),
    })
    const response = await GET(new NextRequest('http://app.test/api/bff/overview'))
    expect(response.status).toBe(502)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(await response.json()).toEqual({ code: 'UNAVAILABLE' })
  })
})
