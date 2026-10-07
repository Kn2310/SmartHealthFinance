// @vitest-environment node
import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createCookieStore, form, stubFetch, stubServerEnv, type CookieStore } from '@/test/server-helpers'

let store: CookieStore
vi.mock('next/headers', () => ({ cookies: async () => store }))

import { resetOidcCache } from '@/server/auth/oidc'
import { seal, unseal } from '@/server/auth/seal'
import { FLOW_COOKIE, SESSION_COOKIE, type Session } from '@/server/auth/session'
import { GET as callback } from './callback/route'
import { GET as login } from './login/route'
import { POST as logout } from './logout/route'

const SECRET = 'test-secret-with-at-least-32-characters!!'
const TOKEN_URL = 'http://idp.test/realms/shf/protocol/openid-connect/token'
const USERS_ME = 'http://api.test/api/v1/users/me'

beforeEach(() => {
  stubServerEnv()
  resetOidcCache()
  store = createCookieStore()
  vi.spyOn(console, 'warn').mockImplementation(() => {})
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('GET /auth/login', () => {
  it('redireciona ao IdP com code + PKCE S256 + state e grava o cookie de fluxo selado', async () => {
    stubFetch({})
    const response = await login()

    expect(response.status).toBe(307)
    expect(response.headers.get('cache-control')).toBe('no-store')
    const location = new URL(response.headers.get('location')!)
    expect(`${location.origin}${location.pathname}`).toBe('http://idp.test/realms/shf/protocol/openid-connect/auth')
    expect(Object.fromEntries(location.searchParams)).toMatchObject({
      response_type: 'code',
      client_id: 'shf-web',
      redirect_uri: 'http://app.test/auth/callback',
      code_challenge_method: 'S256',
      scope: 'openid profile email',
    })

    const flow = unseal<{ state: string; verifier: string }>(store.values.get(FLOW_COOKIE)!, SECRET, FLOW_COOKIE)!
    expect(location.searchParams.get('state')).toBe(flow.state)
    // challenge = base64url(SHA-256(verifier))
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(flow.verifier))
    expect(location.searchParams.get('code_challenge')).toBe(Buffer.from(digest).toString('base64url'))
    expect(store.options.get(FLOW_COOKIE)).toMatchObject({ httpOnly: true, sameSite: 'lax', maxAge: 600 })
  })

  it('IdP indisponível: página de erro genérica', async () => {
    stubFetch({ 'http://idp.test/realms/shf/.well-known/openid-configuration': () => new Response('down', { status: 503 }) })
    const response = await login()
    expect(response.headers.get('location')).toBe('http://app.test/auth/error?reason=idp')
  })
})

describe('GET /auth/callback', () => {
  const flowCookie = (state = 'good-state') =>
    seal({ state, verifier: 'the-verifier' }, SECRET, FLOW_COOKIE)
  const request = (qs: string) => new NextRequest(`http://app.test/auth/callback${qs}`)
  const happyRoutes = {
    [TOKEN_URL]: () => Response.json({ access_token: 'acc', refresh_token: 'ref', expires_in: 300 }),
    [USERS_ME]: () =>
      Response.json({ id: 'u1', email: 'ana@example.test', displayName: 'Ana Souza', status: 'ACTIVE', createdAt: 'x', workspaceId: 'ws-9' }),
  }

  it('sem cookie de fluxo: erro (state) e nenhuma troca de código', async () => {
    const { calls } = stubFetch(happyRoutes)
    const response = await callback(request('?code=c&state=good-state'))
    expect(response.headers.get('location')).toBe('http://app.test/auth/error?reason=state')
    expect(calls.some((c) => c.url === TOKEN_URL)).toBe(false)
  })

  it('state diferente do cookie: erro (state)', async () => {
    store = createCookieStore({ [FLOW_COOKIE]: flowCookie() })
    stubFetch(happyRoutes)
    const response = await callback(request('?code=c&state=forjado'))
    expect(response.headers.get('location')).toBe('http://app.test/auth/error?reason=state')
    expect(store.deleted.has(FLOW_COOKIE)).toBe(true)
  })

  it('IdP recusa o código (ex.: replay): erro (token)', async () => {
    store = createCookieStore({ [FLOW_COOKIE]: flowCookie() })
    stubFetch({ ...happyRoutes, [TOKEN_URL]: () => Response.json({ error: 'invalid_grant' }, { status: 400 }) })
    const response = await callback(request('?code=c&state=good-state'))
    expect(response.headers.get('location')).toBe('http://app.test/auth/error?reason=token')
  })

  it('falha no provisionamento: erro (provision) e nenhuma sessão', async () => {
    store = createCookieStore({ [FLOW_COOKIE]: flowCookie() })
    stubFetch({ ...happyRoutes, [USERS_ME]: () => Response.json({ code: 'USER_DISABLED' }, { status: 403 }) })
    const response = await callback(request('?code=c&state=good-state'))
    expect(response.headers.get('location')).toBe('http://app.test/auth/error?reason=provision')
    expect(store.values.has(SESSION_COOKIE)).toBe(false)
  })

  it('sucesso: troca o código com o verifier, provisiona com Bearer e grava a sessão com o Workspace', async () => {
    store = createCookieStore({ [FLOW_COOKIE]: flowCookie() })
    const { calls } = stubFetch(happyRoutes)

    const response = await callback(request('?code=the-code&state=good-state'))

    expect(response.status).toBe(307)
    expect(response.headers.get('location')).toBe('http://app.test/home')
    expect(response.headers.get('cache-control')).toBe('no-store')

    const tokenCall = calls.find((c) => c.url === TOKEN_URL)!
    expect(form(tokenCall.body)).toEqual({
      client_id: 'shf-web',
      grant_type: 'authorization_code',
      code: 'the-code',
      code_verifier: 'the-verifier',
      redirect_uri: 'http://app.test/auth/callback',
    })
    const provision = calls.find((c) => c.url === USERS_ME)!
    expect(provision.method).toBe('POST')
    expect(provision.headers.get('authorization')).toBe('Bearer acc')

    const saved = unseal<Session>(store.values.get(SESSION_COOKIE)!, SECRET, SESSION_COOKIE)!
    expect(saved).toMatchObject({ accessToken: 'acc', refreshToken: 'ref', workspaceId: 'ws-9', displayName: 'Ana Souza' })
    expect(saved).not.toHaveProperty('email')
    expect(store.deleted.has(FLOW_COOKIE)).toBe(true)
  })

  it('sessão grande demais: erro controlado (session), sem 500', async () => {
    store = createCookieStore({ [FLOW_COOKIE]: flowCookie() })
    const big = crypto.getRandomValues(new Uint8Array(4000)).join('')
    stubFetch({ ...happyRoutes, [TOKEN_URL]: () => Response.json({ access_token: big, refresh_token: 'r', expires_in: 300 }) })
    const response = await callback(request('?code=c&state=good-state'))
    expect(response.status).toBe(307)
    expect(response.headers.get('location')).toBe('http://app.test/auth/error?reason=session')
  })
})

describe('POST /auth/logout (ADR-0008)', () => {
  const session: Session = {
    accessToken: 'acc',
    refreshToken: 'ref-123',
    expiresAt: Date.now() + 60_000,
    workspaceId: 'ws-1',
    displayName: 'Ana',
  }

  it('encerra a sessão no IdP pelo back-channel, revoga o refresh token, apaga o cookie e volta ao app', async () => {
    store = createCookieStore({ [SESSION_COOKIE]: seal(session, SECRET, SESSION_COOKIE) })
    const { calls } = stubFetch({
      'http://idp.test/realms/shf/protocol/openid-connect/logout': () => new Response(null, { status: 204 }),
      'http://idp.test/realms/shf/protocol/openid-connect/revoke': () => new Response(null, { status: 200 }),
    })

    const response = await logout()

    expect(response.status).toBe(303)
    expect(response.headers.get('location')).toBe('http://app.test/auth/signed-out')
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(store.deleted.has(SESSION_COOKIE)).toBe(true)

    const endSession = calls.find((c) => c.url.endsWith('/logout'))!
    expect(endSession.method).toBe('POST')
    expect(form(endSession.body)).toEqual({ client_id: 'shf-web', refresh_token: 'ref-123' })
    const revoke = calls.find((c) => c.url.endsWith('/revoke'))!
    expect(form(revoke.body)).toEqual({ client_id: 'shf-web', token: 'ref-123', token_type_hint: 'refresh_token' })
  })

  it('IdP fora do ar: o cookie local é apagado mesmo assim e não há 500', async () => {
    store = createCookieStore({ [SESSION_COOKIE]: seal(session, SECRET, SESSION_COOKIE) })
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')))
    const response = await logout()
    expect(response.status).toBe(303)
    expect(store.deleted.has(SESSION_COOKIE)).toBe(true)
  })

  it('sem sessão: só redireciona, sem chamar o IdP', async () => {
    const { calls } = stubFetch({})
    const response = await logout()
    expect(response.headers.get('location')).toBe('http://app.test/auth/signed-out')
    expect(calls).toHaveLength(0)
  })
})
