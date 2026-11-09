// @vitest-environment node
import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { stubServerEnv } from '@/test/server-helpers'

const getFreshSession = vi.fn()
const GET_BACKEND = vi.fn()
const PUT_BACKEND = vi.fn()

vi.mock('@/server/auth/session', () => ({ getFreshSession: () => getFreshSession() }))
vi.mock('@/server/backend', () => ({ backendClient: () => ({ GET: GET_BACKEND, PUT: PUT_BACKEND }) }))

import { GET as list } from './route'
import { PUT as change } from './[purpose]/route'

const ORIGIN = 'http://app.test'
const session = { accessToken: 'tok', workspaceId: 'ws-da-sessao', displayName: 'Ana', refreshToken: 'r', expiresAt: 0 }
const params = (purpose = 'COPILOT_DATA_USE') => ({ params: Promise.resolve({ purpose }) })
const items = { items: [{ purpose: 'COPILOT_DATA_USE', state: 'ACTIVE' }] }

function put(body: unknown, { origin = ORIGIN }: { origin?: string | null } = {}) {
  const headers = new Headers({ 'content-type': 'application/json' })
  if (origin) headers.set('origin', origin)
  return new NextRequest(`${ORIGIN}/api/bff/consents/COPILOT_DATA_USE`, {
    method: 'PUT',
    headers,
    body: typeof body === 'string' ? body : JSON.stringify(body),
  })
}

beforeEach(() => {
  stubServerEnv()
  getFreshSession.mockReset().mockResolvedValue({ status: 'ok', session })
  GET_BACKEND.mockReset()
  PUT_BACKEND.mockReset()
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

describe('GET /api/bff/consents', () => {
  it('lê os consentimentos do Workspace da sessão, sem cache', async () => {
    GET_BACKEND.mockResolvedValue({ data: items, response: new Response(null, { status: 200 }) })

    const response = await list()

    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toContain('no-store')
    expect(await response.json()).toEqual(items)
    expect(GET_BACKEND).toHaveBeenCalledWith('/api/v1/workspaces/{workspaceId}/consents', {
      params: { path: { workspaceId: 'ws-da-sessao' } },
    })
  })

  it('sem sessão → 401; falha desconhecida → 502 sem corpo do backend', async () => {
    getFreshSession.mockResolvedValueOnce({ status: 'none' })
    expect((await list()).status).toBe(401)

    GET_BACKEND.mockResolvedValue({
      error: { code: 'INTERNAL_ERROR', message: 'stack', traceId: 't' },
      response: new Response(null, { status: 500 }),
    })
    const failed = await list()
    expect(failed.status).toBe(502)
    expect(await failed.json()).toEqual({ code: 'UNAVAILABLE' })
  })
})

describe('PUT /api/bff/consents/{purpose}', () => {
  it('conceder repassa só granted e termsVersion', async () => {
    PUT_BACKEND.mockResolvedValue({ data: items, response: new Response(null, { status: 200 }) })

    const response = await change(put({ granted: true, termsVersion: 'copilot-data-v1', extra: 'x' }), params())

    expect(response.status).toBe(200)
    expect(PUT_BACKEND).toHaveBeenCalledWith('/api/v1/workspaces/{workspaceId}/consents/{purpose}', {
      params: { path: { workspaceId: 'ws-da-sessao', purpose: 'COPILOT_DATA_USE' } },
      body: { granted: true, termsVersion: 'copilot-data-v1' },
    })
  })

  it('revogar nunca leva versão', async () => {
    PUT_BACKEND.mockResolvedValue({ data: items, response: new Response(null, { status: 200 }) })

    await change(put({ granted: false, termsVersion: 'copilot-data-v1' }), params())

    expect(PUT_BACKEND.mock.calls[0]![1].body).toEqual({ granted: false })
  })

  it('recusa outra origem, finalidade desconhecida e corpo inválido sem chamar a API', async () => {
    expect((await change(put({ granted: true }, { origin: 'http://evil.test' }), params())).status).toBe(403)
    expect((await change(put({ granted: true }), params('INSIGHTS_GENERATION'))).status).toBe(404)
    expect((await change(put('não é json'), params())).status).toBe(400)
    const missing = await change(put({ termsVersion: 'copilot-data-v1' }), params())
    expect(missing.status).toBe(400)
    expect(await missing.json()).toEqual({ code: 'VALIDATION_FAILED', details: [{ field: 'granted', code: 'REQUIRED' }] })
    expect((await change(put({ granted: true }), params())).status).toBe(409)
    expect(PUT_BACKEND).not.toHaveBeenCalled()
  })

  it('repassa só os códigos estáveis de conflito', async () => {
    PUT_BACKEND.mockResolvedValue({
      error: { code: 'CONSENT_PREREQUISITE_MISSING', message: 'm', traceId: 't' },
      response: new Response(null, { status: 409 }),
    })
    const conflict = await change(put({ granted: true, termsVersion: 'copilot-data-v1' }), params())
    expect(conflict.status).toBe(409)
    expect(await conflict.json()).toEqual({ code: 'CONSENT_PREREQUISITE_MISSING' })

    PUT_BACKEND.mockResolvedValue({
      error: { code: 'SOMETHING_ELSE', message: 'm' },
      response: new Response(null, { status: 409 }),
    })
    expect((await change(put({ granted: true, termsVersion: 'copilot-data-v1' }), params())).status).toBe(502)
  })
})
