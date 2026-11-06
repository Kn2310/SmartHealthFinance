// @vitest-environment node
import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { stubServerEnv } from '@/test/server-helpers'

const getFreshSession = vi.fn()
const GET_BACKEND = vi.fn()
const POST_BACKEND = vi.fn()
const PUT_BACKEND = vi.fn()
const DELETE_BACKEND = vi.fn()

vi.mock('@/server/auth/session', () => ({ getFreshSession: () => getFreshSession() }))
vi.mock('@/server/backend', () => ({
  backendClient: () => ({ GET: GET_BACKEND, POST: POST_BACKEND, PUT: PUT_BACKEND, DELETE: DELETE_BACKEND }),
}))

import { GET as view } from './[view]/route'
import { GET as expensesList } from '../recurring-expenses/route'
import { GET as expensesDetail } from '../recurring-expenses/[seriesId]/route'
import { POST as expensesConfirm } from '../recurring-expenses/[seriesId]/confirm/route'
import { POST as expensesIgnore } from '../recurring-expenses/[seriesId]/ignore/route'
import { PUT as expensesAdjust, DELETE as expensesReset } from '../recurring-expenses/[seriesId]/adjustment/route'
import { GET as incomesList } from '../recurring-incomes/route'
import { POST as incomesConfirm } from '../recurring-incomes/[seriesId]/confirm/route'
import { PUT as incomesAdjust } from '../recurring-incomes/[seriesId]/adjustment/route'

const ORIGIN = 'http://app.test'
const SERIES = '01922f5e-0000-7000-8000-0000000000d1'
const CARD = '01922f5e-0000-7000-8000-0000000000c1'
const session = { accessToken: 'tok', workspaceId: 'ws-da-sessao', displayName: 'Ana', refreshToken: 'r', expiresAt: 0 }
const params = <T extends Record<string, string>>(value: T) => ({ params: Promise.resolve(value) })
const seriesParams = (seriesId = SERIES) => params({ seriesId })
const get = (path: string) => new NextRequest(`${ORIGIN}${path}`)
const send = (method: string, path: string, body?: unknown, origin: string | null = ORIGIN) => {
  const headers = new Headers({ 'content-type': 'application/json' })
  if (origin) headers.set('origin', origin)
  return new NextRequest(`${ORIGIN}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
}
const apiError = (code: string, details: unknown[] = []) => ({ code, message: 'texto interno', details, traceId: 'abc' })
const ok = { data: { state: 'READY' }, response: { status: 200 } }
const action = { data: { series: { id: SERIES }, forecastState: 'RECALCULATING' }, response: { status: 200 } }

beforeEach(() => {
  stubServerEnv()
  getFreshSession.mockReset().mockResolvedValue({ status: 'ok', session })
  for (const mock of [GET_BACKEND, POST_BACKEND, PUT_BACKEND, DELETE_BACKEND]) mock.mockReset().mockResolvedValue(ok)
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

describe('BFF do Forecast — leituras (ADR-0032 §1)', () => {
  it.each([
    ['month', '/api/v1/workspaces/{workspaceId}/forecast/month'],
    ['next-income', '/api/v1/workspaces/{workspaceId}/forecast/next-income'],
    ['upcoming', '/api/v1/workspaces/{workspaceId}/forecast/upcoming'],
    ['cash-flow', '/api/v1/workspaces/{workspaceId}/forecast/cash-flow'],
    ['status', '/api/v1/workspaces/{workspaceId}/forecast/status'],
  ])('%s: repassa ao Workspace da sessão (ignora o workspaceId da URL), no-store', async (name, backendPath) => {
    const response = await view(get(`/api/bff/forecast/${name}?workspaceId=outro`), params({ view: name }))
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(GET_BACKEND).toHaveBeenCalledWith(backendPath, { params: { path: { workspaceId: 'ws-da-sessao' } } })
  })

  it('invoices: repassa o cardId UUID; sem cardId pede todos os cartões; cardId malformado é 404 sem chamar a API', async () => {
    await view(get(`/api/bff/forecast/invoices?cardId=${CARD}`), params({ view: 'invoices' }))
    expect(GET_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/forecast/invoices', {
      params: { path: { workspaceId: 'ws-da-sessao' }, query: { cardId: CARD } },
    })
    await view(get('/api/bff/forecast/invoices'), params({ view: 'invoices' }))
    expect(GET_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/forecast/invoices', {
      params: { path: { workspaceId: 'ws-da-sessao' }, query: { cardId: undefined } },
    })
    GET_BACKEND.mockClear()
    const bad = await view(get('/api/bff/forecast/invoices?cardId=../x'), params({ view: 'invoices' }))
    expect(bad.status).toBe(404)
    expect(await bad.json()).toEqual({ code: 'CARD_NOT_FOUND' })
    expect(GET_BACKEND).not.toHaveBeenCalled()
  })

  it('leitura desconhecida é 404 sem chamar o backend; sem sessão é 401', async () => {
    expect((await view(get('/api/bff/forecast/goals'), params({ view: 'goals' }))).status).toBe(404)
    getFreshSession.mockResolvedValue({ status: 'none' })
    expect((await view(get('/api/bff/forecast/month'), params({ view: 'month' }))).status).toBe(401)
    expect(GET_BACKEND).not.toHaveBeenCalled()
  })

  it('falhas viram códigos estáveis: cartão inexistente 404, validação com campos conhecidos, o resto 502', async () => {
    GET_BACKEND.mockResolvedValueOnce({ error: apiError('CARD_NOT_FOUND'), response: { status: 404 } })
    const missing = await view(get(`/api/bff/forecast/invoices?cardId=${CARD}`), params({ view: 'invoices' }))
    expect(missing.status).toBe(404)
    expect(await missing.json()).toEqual({ code: 'CARD_NOT_FOUND' })

    GET_BACKEND.mockResolvedValueOnce({
      error: apiError('VALIDATION_FAILED', [{ field: 'cardId', code: 'INVALID' }, { field: 'segredo', code: 'X' }]),
      response: { status: 400 },
    })
    const invalid = await view(get(`/api/bff/forecast/invoices?cardId=${CARD}`), params({ view: 'invoices' }))
    expect(invalid.status).toBe(400)
    expect(await invalid.json()).toEqual({ code: 'VALIDATION_FAILED', details: [{ field: 'cardId', code: 'INVALID' }] })

    GET_BACKEND.mockResolvedValueOnce({ error: apiError('WORKSPACE_NOT_FOUND'), response: { status: 404 } })
    const hidden = await view(get('/api/bff/forecast/month'), params({ view: 'month' }))
    expect(hidden.status).toBe(502)
    expect(await hidden.json()).toEqual({ code: 'UNAVAILABLE' })
  })
})

describe('BFF das recorrências (ADR-0032 §2)', () => {
  it('lista despesas e receitas com status bem formado; status desconhecido é descartado', async () => {
    await expensesList(get('/api/bff/recurring-expenses?status=DETECTED&workspaceId=x'))
    expect(GET_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/recurring-expenses', {
      params: { path: { workspaceId: 'ws-da-sessao' }, query: { status: 'DETECTED' } },
    })
    await incomesList(get('/api/bff/recurring-incomes?status=QUALQUER'))
    expect(GET_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/recurring-incomes', {
      params: { path: { workspaceId: 'ws-da-sessao' }, query: { status: undefined } },
    })
  })

  it('detalhe: id que não é UUID é 404 sem chamar a API; série de outro Workspace é 404', async () => {
    const bad = await expensesDetail(get('/api/bff/recurring-expenses/x'), seriesParams('x'))
    expect(bad.status).toBe(404)
    expect(await bad.json()).toEqual({ code: 'RECURRING_SERIES_NOT_FOUND' })
    expect(GET_BACKEND).not.toHaveBeenCalled()

    GET_BACKEND.mockResolvedValueOnce({ error: apiError('RECURRING_SERIES_NOT_FOUND'), response: { status: 404 } })
    const other = await expensesDetail(get(`/api/bff/recurring-expenses/${SERIES}`), seriesParams())
    expect(other.status).toBe(404)
    expect(GET_BACKEND).toHaveBeenCalledWith('/api/v1/workspaces/{workspaceId}/recurring-expenses/{seriesId}', {
      params: { path: { workspaceId: 'ws-da-sessao', seriesId: SERIES } },
    })
  })

  it('confirmar e ignorar: só da mesma origem; repassa a série e o forecastState', async () => {
    POST_BACKEND.mockResolvedValue(action)
    const confirm = await expensesConfirm(send('POST', `/x`), seriesParams())
    expect(confirm.status).toBe(200)
    expect(await confirm.json()).toEqual({ series: { id: SERIES }, forecastState: 'RECALCULATING' })
    expect(POST_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/recurring-expenses/{seriesId}/confirm', {
      params: { path: { workspaceId: 'ws-da-sessao', seriesId: SERIES } },
    })
    await expensesIgnore(send('POST', `/x`), seriesParams())
    expect(POST_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/recurring-expenses/{seriesId}/ignore', {
      params: { path: { workspaceId: 'ws-da-sessao', seriesId: SERIES } },
    })
    await incomesConfirm(send('POST', `/x`), seriesParams())
    expect(POST_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/recurring-incomes/{seriesId}/confirm', {
      params: { path: { workspaceId: 'ws-da-sessao', seriesId: SERIES } },
    })

    POST_BACKEND.mockClear()
    expect((await expensesConfirm(send('POST', '/x', undefined, 'http://evil.test'), seriesParams())).status).toBe(403)
    expect((await expensesIgnore(send('POST', '/x', undefined, null), seriesParams())).status).toBe(403)
    expect((await expensesConfirm(send('POST', '/x'), seriesParams('nao-uuid'))).status).toBe(404)
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('ajuste: valida o corpo no BFF e repassa só valor decimal simples e dia 1–31', async () => {
    PUT_BACKEND.mockResolvedValue(action)
    const ok = await expensesAdjust(
      send('PUT', '/x', { expectedAmount: { amount: '120.50', currency: 'BRL' }, expectedDay: 12, extra: 'x' }),
      seriesParams(),
    )
    expect(ok.status).toBe(200)
    expect(PUT_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/recurring-expenses/{seriesId}/adjustment', {
      params: { path: { workspaceId: 'ws-da-sessao', seriesId: SERIES } },
      body: { expectedAmount: { amount: '120.50', currency: 'BRL' }, expectedDay: 12 },
    })
    await incomesAdjust(send('PUT', '/x', { expectedDay: 5 }), seriesParams())
    expect(PUT_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/recurring-incomes/{seriesId}/adjustment', {
      params: { path: { workspaceId: 'ws-da-sessao', seriesId: SERIES } },
      body: { expectedDay: 5 },
    })

    PUT_BACKEND.mockClear()
    for (const body of [
      {},
      { expectedDay: 0 },
      { expectedDay: 32 },
      { expectedDay: 1.5 },
      { expectedDay: '5' },
      { expectedAmount: { amount: '1e3', currency: 'BRL' } },
      { expectedAmount: { amount: '10.123', currency: 'BRL' } },
      { expectedAmount: { amount: '-5', currency: 'BRL' } },
      { expectedAmount: 100 },
      [],
    ]) {
      expect((await expensesAdjust(send('PUT', '/x', body), seriesParams())).status, JSON.stringify(body)).toBe(400)
    }
    expect((await expensesAdjust(send('PUT', '/x', { expectedDay: 5 }, 'http://evil.test'), seriesParams())).status).toBe(403)
    expect(PUT_BACKEND).not.toHaveBeenCalled()

    const detail = await expensesAdjust(send('PUT', '/x', { expectedDay: 40 }), seriesParams())
    expect(await detail.json()).toEqual({ code: 'VALIDATION_FAILED', details: [{ field: 'expectedDay', code: 'INVALID' }] })
  })

  it('voltar ao detectado (DELETE): sem corpo, só da mesma origem; erros do backend viram códigos estáveis', async () => {
    DELETE_BACKEND.mockResolvedValue(action)
    const reset = await expensesReset(send('DELETE', '/x'), seriesParams())
    expect(reset.status).toBe(200)
    expect(DELETE_BACKEND).toHaveBeenLastCalledWith('/api/v1/workspaces/{workspaceId}/recurring-expenses/{seriesId}/adjustment', {
      params: { path: { workspaceId: 'ws-da-sessao', seriesId: SERIES } },
    })
    expect((await expensesReset(send('DELETE', '/x', undefined, null), seriesParams())).status).toBe(403)

    DELETE_BACKEND.mockResolvedValueOnce({ error: apiError('RECURRING_SERIES_NOT_FOUND'), response: { status: 404 } })
    expect((await expensesReset(send('DELETE', '/x'), seriesParams())).status).toBe(404)
    DELETE_BACKEND.mockResolvedValueOnce({ error: apiError('BOOM'), response: { status: 500 } })
    const failure = await expensesReset(send('DELETE', '/x'), seriesParams())
    expect(failure.status).toBe(502)
    expect(await failure.json()).toEqual({ code: 'UNAVAILABLE' })
  })

  it('sem sessão é 401', async () => {
    getFreshSession.mockResolvedValue({ status: 'none' })
    expect((await expensesList(get('/api/bff/recurring-expenses'))).status).toBe(401)
    expect((await expensesConfirm(send('POST', '/x'), seriesParams())).status).toBe(401)
  })
})
