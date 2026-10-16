// @vitest-environment node
import { NextRequest } from 'next/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const getFreshSession = vi.fn()
const GET_BACKEND = vi.fn()

vi.mock('@/server/auth/session', () => ({ getFreshSession: () => getFreshSession() }))
vi.mock('@/server/backend', () => ({ backendClient: () => ({ GET: GET_BACKEND }) }))

import { GET } from './[view]/route'

const ORIGIN = 'http://app.test'
const session = { accessToken: 'tok', workspaceId: 'ws-da-sessao', displayName: 'Ana', refreshToken: 'r', expiresAt: 0 }
const request = (path: string) => new NextRequest(`${ORIGIN}${path}`)
const params = (view: string) => ({ params: Promise.resolve({ view }) })

describe('BFF do Financial Health (ADR-0019)', () => {
  beforeEach(() => {
    getFreshSession.mockReset().mockResolvedValue({ status: 'ok', session })
    GET_BACKEND.mockReset().mockResolvedValue({ data: { state: 'READY' }, response: { status: 200 } })
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it('repassa cada visão para o Workspace da sessão só com parâmetros bem formados', async () => {
    const ok = await GET(request('/api/bff/health/summary?month=2026-09&workspaceId=outro'), params('summary'))
    expect(ok.status).toBe(200)
    expect(ok.headers.get('Cache-Control')).toBe('no-store')
    expect(GET_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/health', {
      params: { path: { workspaceId: 'ws-da-sessao' }, query: { month: '2026-09' } },
    })

    await GET(request('/api/bff/health/evolution?month=set&months=13'), params('evolution'))
    expect(GET_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/health/evolution', {
      params: { path: { workspaceId: 'ws-da-sessao' }, query: { month: undefined, months: undefined } },
    })
    await GET(request('/api/bff/health/evolution?months=6'), params('evolution'))
    expect(GET_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/health/evolution', {
      params: { path: { workspaceId: 'ws-da-sessao' }, query: { month: undefined, months: 6 } },
    })

    await GET(request('/api/bff/health/method'), params('method'))
    expect(GET_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/health/method', {
      params: { path: { workspaceId: 'ws-da-sessao' } },
    })
    await GET(request('/api/bff/health/status'), params('status'))
    expect(GET_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/health/status', {
      params: { path: { workspaceId: 'ws-da-sessao' } },
    })
  })

  it('visão desconhecida é 404 sem chamar o backend; sem sessão é 401', async () => {
    expect((await GET(request('/api/bff/health/score'), params('score'))).status).toBe(404)
    getFreshSession.mockResolvedValue({ status: 'none' })
    expect((await GET(request('/api/bff/health/summary'), params('summary'))).status).toBe(401)
    expect(GET_BACKEND).not.toHaveBeenCalled()
  })

  it('validação passa só com campos conhecidos; outras falhas viram UNAVAILABLE', async () => {
    GET_BACKEND.mockResolvedValueOnce({
      error: { code: 'VALIDATION_FAILED', message: 'x', details: [{ field: 'month', code: 'FUTURE', message: 'm' }] },
      response: { status: 400 },
    })
    const invalid = await GET(request('/api/bff/health/summary?month=2099-01'), params('summary'))
    expect(invalid.status).toBe(400)
    expect(await invalid.json()).toEqual({ code: 'VALIDATION_FAILED', details: [{ field: 'month', code: 'FUTURE' }] })

    GET_BACKEND.mockResolvedValueOnce({ error: { code: 'BOOM', message: 'x', details: [] }, response: { status: 500 } })
    const failure = await GET(request('/api/bff/health/summary'), params('summary'))
    expect(failure.status).toBe(502)
    expect(await failure.json()).toEqual({ code: 'UNAVAILABLE' })
  })
})
