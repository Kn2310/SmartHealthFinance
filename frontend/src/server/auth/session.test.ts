// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createCookieStore, form, stubFetch, stubServerEnv, type CookieStore } from '@/test/server-helpers'

let store: CookieStore
vi.mock('next/headers', () => ({ cookies: async () => store }))

import { resetOidcCache } from './oidc'
import { seal, unseal } from './seal'
import {
  getFreshSession,
  MAX_SEALED_SESSION_LENGTH,
  readSession,
  SESSION_COOKIE,
  SessionTooLargeError,
  writeSession,
  type Session,
} from './session'

const SECRET = 'test-secret-with-at-least-32-characters!!'
const session = (overrides: Partial<Session> = {}): Session => ({
  accessToken: 'old-access',
  refreshToken: 'old-refresh',
  expiresAt: Date.now() + 10 * 60_000,
  workspaceId: 'ws-1',
  displayName: 'Ana Souza',
  ...overrides,
})
const sealed = (s: Session) => seal(s, SECRET, SESSION_COOKIE)

beforeEach(() => {
  stubServerEnv()
  resetOidcCache()
  store = createCookieStore()
  vi.spyOn(console, 'warn').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('cookie de sessão', () => {
  it('é HttpOnly, SameSite=Lax, path / e 7 dias; sem Secure em http', async () => {
    await writeSession(session())
    expect(store.options.get(SESSION_COOKIE)).toEqual({
      httpOnly: true,
      sameSite: 'lax',
      secure: false,
      path: '/',
      maxAge: 60 * 60 * 24 * 7,
    })
  })

  it('tem Secure quando o app é servido por HTTPS', async () => {
    stubServerEnv({ APP_BASE_URL: 'https://app.example' })
    await writeSession(session())
    expect(store.options.get(SESSION_COOKIE)?.secure).toBe(true)
  })

  it('é selado: o valor não contém tokens nem o Workspace em claro', async () => {
    await writeSession(session())
    const raw = store.values.get(SESSION_COOKIE)!
    expect(raw).not.toMatch(/old-access|old-refresh|ws-1|Ana/)
    expect(unseal(raw, SECRET, SESSION_COOKIE)).toMatchObject({ workspaceId: 'ws-1' })
  })

  it('recusa sessão maior que o limite de um cookie com erro tipado (não grava nada)', async () => {
    const huge = session({ accessToken: crypto.getRandomValues(new Uint8Array(4000)).join('') })
    await expect(writeSession(huge)).rejects.toBeInstanceOf(SessionTooLargeError)
    expect(store.values.has(SESSION_COOKIE)).toBe(false)
    expect(MAX_SEALED_SESSION_LENGTH).toBeLessThan(4096)
  })

  it('cookie adulterado = sem sessão', async () => {
    store = createCookieStore({ [SESSION_COOKIE]: sealed(session()).slice(0, -3) + 'AAA' })
    expect(await readSession()).toBeNull()
  })
})

describe('getFreshSession', () => {
  it('sem cookie: none', async () => {
    expect(await getFreshSession()).toEqual({ status: 'none' })
  })

  it('token válido: devolve a sessão sem chamar o IdP', async () => {
    store = createCookieStore({ [SESSION_COOKIE]: sealed(session()) })
    const { calls } = stubFetch({})
    expect(await getFreshSession()).toMatchObject({ status: 'ok', session: { accessToken: 'old-access' } })
    expect(calls).toHaveLength(0)
  })

  it('token expirando: renova com refresh_token e grava o novo cookie', async () => {
    store = createCookieStore({ [SESSION_COOKIE]: sealed(session({ expiresAt: Date.now() + 5_000 })) })
    const { calls } = stubFetch({
      'http://idp.test/realms/shf/protocol/openid-connect/token': () =>
        Response.json({ access_token: 'new-access', refresh_token: 'new-refresh', expires_in: 300 }),
    })

    const fresh = await getFreshSession()

    expect(fresh).toMatchObject({ status: 'ok', session: { accessToken: 'new-access', workspaceId: 'ws-1' } })
    const tokenCall = calls.find((c) => c.url.endsWith('/token'))!
    expect(form(tokenCall.body)).toEqual({ client_id: 'shf-web', grant_type: 'refresh_token', refresh_token: 'old-refresh' })
    expect(unseal<Session>(store.values.get(SESSION_COOKIE)!, SECRET, SESSION_COOKIE)?.refreshToken).toBe('new-refresh')
  })

  it('refresh recusado pelo IdP: apaga a sessão e devolve none', async () => {
    store = createCookieStore({ [SESSION_COOKIE]: sealed(session({ expiresAt: 0 })) })
    stubFetch({
      'http://idp.test/realms/shf/protocol/openid-connect/token': () => Response.json({ error: 'invalid_grant' }, { status: 400 }),
    })
    expect(await getFreshSession()).toEqual({ status: 'none' })
    expect(store.deleted.has(SESSION_COOKIE)).toBe(true)
  })

  it('IdP fora do ar no refresh: mantém a sessão e devolve unavailable', async () => {
    store = createCookieStore({ [SESSION_COOKIE]: sealed(session({ expiresAt: 0 })) })
    stubFetch({ 'http://idp.test/realms/shf/protocol/openid-connect/token': () => new Response('down', { status: 503 }) })
    expect(await getFreshSession()).toEqual({ status: 'unavailable' })
    expect(store.deleted.has(SESSION_COOKIE)).toBe(false)
  })

  it('tokens renovados grandes demais: sessão encerrada de forma controlada (sem exceção)', async () => {
    store = createCookieStore({ [SESSION_COOKIE]: sealed(session({ expiresAt: 0 })) })
    const big = crypto.getRandomValues(new Uint8Array(4000)).join('')
    stubFetch({
      'http://idp.test/realms/shf/protocol/openid-connect/token': () =>
        Response.json({ access_token: big, refresh_token: 'r', expires_in: 300 }),
    })
    expect(await getFreshSession()).toEqual({ status: 'none' })
    expect(store.deleted.has(SESSION_COOKIE)).toBe(true)
  })
})
