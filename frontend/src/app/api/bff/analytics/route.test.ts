// @vitest-environment node
import { NextRequest } from 'next/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const getFreshSession = vi.fn()
const GET_BACKEND = vi.fn()

vi.mock('@/server/auth/session', () => ({ getFreshSession: () => getFreshSession() }))
vi.mock('@/server/backend', () => ({ backendClient: () => ({ GET: GET_BACKEND }) }))

import { GET as view } from './[view]/route'
import { GET as category } from './categories/[categoryCode]/route'
import { GET as summary } from '../transactions/summary/route'

const ORIGIN = 'http://app.test'
const session = { accessToken: 'tok', workspaceId: 'ws-da-sessao', displayName: 'Ana', refreshToken: 'r', expiresAt: 0 }
const request = (path: string) => new NextRequest(`${ORIGIN}${path}`)
const params = <T extends Record<string, string>>(value: T) => ({ params: Promise.resolve(value) })

describe('BFF da Análise (ADR-0015) e resumo do período (ADR-0016)', () => {
  beforeEach(() => {
    getFreshSession.mockReset().mockResolvedValue({ status: 'ok', session })
    GET_BACKEND.mockReset().mockResolvedValue({ data: { state: 'READY' }, response: { status: 200 } })
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it('repassa a aba para o Workspace da sessão só com parâmetros bem formados', async () => {
    const ok = await view(request('/api/bff/analytics/overview?month=2026-09&compareWith=AVERAGE&averageMonths=3&workspaceId=x'),
      params({ view: 'overview' }))
    expect(ok.status).toBe(200)
    expect(ok.headers.get('Cache-Control')).toBe('no-store')
    expect(GET_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/analytics/overview', {
      params: { path: { workspaceId: 'ws-da-sessao' }, query: { month: '2026-09', compareWith: 'AVERAGE', averageMonths: 3 } },
    })

    await view(request('/api/bff/analytics/merchants?month=set&compareWith=MEDIA&averageMonths=99&sort=COUNT'), params({ view: 'merchants' }))
    expect(GET_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/analytics/merchants', {
      params: { path: { workspaceId: 'ws-da-sessao' }, query: { month: undefined, compareWith: undefined, averageMonths: undefined, sort: 'COUNT' } },
    })

    await view(request('/api/bff/analytics/evolution?months=3'), params({ view: 'evolution' }))
    expect(GET_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/analytics/evolution', {
      params: { path: { workspaceId: 'ws-da-sessao' }, query: { month: undefined, months: 3 } },
    })
  })

  it('aba desconhecida é 404 sem chamar o backend; sem sessão é 401', async () => {
    expect((await view(request('/api/bff/analytics/insights'), params({ view: 'insights' }))).status).toBe(404)
    getFreshSession.mockResolvedValue({ status: 'none' })
    expect((await view(request('/api/bff/analytics/overview'), params({ view: 'overview' }))).status).toBe(401)
    expect(GET_BACKEND).not.toHaveBeenCalled()
  })

  it('validação do backend passa só com campos conhecidos; o resto vira UNAVAILABLE', async () => {
    GET_BACKEND.mockResolvedValueOnce({
      error: { code: 'VALIDATION_FAILED', message: 'x', details: [{ field: 'month', code: 'FUTURE', message: 'm' }, { field: 'segredo', code: 'X' }] },
      response: { status: 400 },
    })
    const invalid = await view(request('/api/bff/analytics/overview?month=2027-01'), params({ view: 'overview' }))
    expect(invalid.status).toBe(400)
    expect(await invalid.json()).toEqual({ code: 'VALIDATION_FAILED', details: [{ field: 'month', code: 'FUTURE' }] })

    GET_BACKEND.mockResolvedValueOnce({ error: { code: 'WORKSPACE_NOT_FOUND', message: 'x', details: [] }, response: { status: 404 } })
    const failure = await view(request('/api/bff/analytics/spending'), params({ view: 'spending' }))
    expect(failure.status).toBe(502)
    expect(await failure.json()).toEqual({ code: 'UNAVAILABLE' })
  })

  it('detalhe da categoria: código fora do formato nem chega ao backend', async () => {
    const bad = await category(request('/api/bff/analytics/categories/dining'), params({ categoryCode: 'dining' }))
    expect(bad.status).toBe(400)
    expect(GET_BACKEND).not.toHaveBeenCalled()

    await category(request('/api/bff/analytics/categories/DINING_OUT?month=2026-09'), params({ categoryCode: 'DINING_OUT' }))
    expect(GET_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/analytics/categories/{categoryCode}', {
      params: {
        path: { workspaceId: 'ws-da-sessao', categoryCode: 'DINING_OUT' },
        query: { month: '2026-09', compareWith: undefined, averageMonths: undefined },
      },
    })
  })

  it('resumo do período: os mesmos filtros da lista, sem status nem paginação', async () => {
    await summary(request('/api/bff/transactions/summary?from=2026-09-01&to=2026-09-30&type=EXPENSE&status=POSTED&page=2&q=lume&uncategorized=true'))
    expect(GET_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/transactions/summary', {
      params: {
        path: { workspaceId: 'ws-da-sessao' },
        query: { from: '2026-09-01', to: '2026-09-30', type: 'EXPENSE', accountId: undefined, q: 'lume', categoryCode: undefined, uncategorized: true },
      },
    })
  })
})
