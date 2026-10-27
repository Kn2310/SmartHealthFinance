// @vitest-environment node
import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { stubServerEnv } from '@/test/server-helpers'

const getFreshSession = vi.fn()
const GET_BACKEND = vi.fn()
const PUT_BACKEND = vi.fn()
const POST_BACKEND = vi.fn()

vi.mock('@/server/auth/session', () => ({ getFreshSession: () => getFreshSession() }))
vi.mock('@/server/backend', () => ({ backendClient: () => ({ GET: GET_BACKEND, PUT: PUT_BACKEND, POST: POST_BACKEND }) }))

import { GET as summary } from './summary/route'
import { GET as getCard, PUT as updateCard } from './[cardId]/route'
import { POST as archive } from './[cardId]/archive/route'
import { POST as reactivate } from './[cardId]/reactivate/route'
import { GET as invoices } from './[cardId]/invoices/route'
import { GET as currentInvoice } from './[cardId]/invoices/current/route'
import { GET as futureInvoices } from './[cardId]/invoices/future/route'
import { GET as invoice } from './[cardId]/invoices/[dueMonth]/route'
import { GET as invoiceItems } from './[cardId]/invoices/[dueMonth]/items/route'
import { GET as installments } from './[cardId]/installments/route'
import { GET as installment } from './[cardId]/installments/[transactionId]/route'
import { GET as utilization } from './[cardId]/utilization/route'
import { GET as utilizationHistory } from './[cardId]/utilization/history/route'

const ORIGIN = 'http://app.test'
const CARD = '01922f5e-0000-7000-8000-0000000000c1'
const TXN = '01922f5e-0000-7000-8000-0000000000bb'
const session = { accessToken: 'tok', workspaceId: 'ws-da-sessao', displayName: 'Ana', refreshToken: 'r', expiresAt: 0 }
const params = (cardId = CARD) => ({ params: Promise.resolve({ cardId }) })
const get = (path: string) => new NextRequest(`${ORIGIN}${path}`)
const ok = (data: unknown) => ({ data, response: { status: 200 } })
const apiError = (code: string, details: unknown[] = []) => ({ code, message: 'texto interno', details, traceId: 'abc' })

beforeEach(() => {
  stubServerEnv()
  getFreshSession.mockReset().mockResolvedValue({ status: 'ok', session })
  GET_BACKEND.mockReset().mockResolvedValue(ok({ fine: true }))
  PUT_BACKEND.mockReset()
  POST_BACKEND.mockReset()
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

describe('GET de cartões, faturas, parcelamentos e utilização', () => {
  it('cada rota lê o recurso do Workspace da sessão, com no-store', async () => {
    const cases: [() => Promise<Response>, string, unknown][] = [
      [() => summary(), '/api/v1/workspaces/{workspaceId}/cards/summary', { workspaceId: 'ws-da-sessao' }],
      [() => getCard(get('/x'), params()), '/api/v1/workspaces/{workspaceId}/cards/{cardId}', { workspaceId: 'ws-da-sessao', cardId: CARD }],
      [() => currentInvoice(get('/x'), params()), '/api/v1/workspaces/{workspaceId}/cards/{cardId}/invoices/current', { workspaceId: 'ws-da-sessao', cardId: CARD }],
      [() => futureInvoices(get('/x'), params()), '/api/v1/workspaces/{workspaceId}/cards/{cardId}/invoices/future', { workspaceId: 'ws-da-sessao', cardId: CARD }],
      [() => utilization(get('/x'), params()), '/api/v1/workspaces/{workspaceId}/cards/{cardId}/utilization', { workspaceId: 'ws-da-sessao', cardId: CARD }],
      [
        () => installment(get('/x'), { params: Promise.resolve({ cardId: CARD, transactionId: TXN }) }),
        '/api/v1/workspaces/{workspaceId}/cards/{cardId}/installments/{transactionId}',
        { workspaceId: 'ws-da-sessao', cardId: CARD, transactionId: TXN },
      ],
      [
        () => invoice(get('/x'), { params: Promise.resolve({ cardId: CARD, dueMonth: '2026-10' }) }),
        '/api/v1/workspaces/{workspaceId}/cards/{cardId}/invoices/{dueMonth}',
        { workspaceId: 'ws-da-sessao', cardId: CARD, dueMonth: '2026-10' },
      ],
    ]
    for (const [call, path, expected] of cases) {
      GET_BACKEND.mockClear()
      const response = await call()
      expect(response.status, path).toBe(200)
      expect(response.headers.get('cache-control')).toBe('no-store')
      expect(GET_BACKEND.mock.calls[0]![0]).toBe(path)
      expect(GET_BACKEND.mock.calls[0]![1].params.path).toEqual(expected)
    }
  })

  it('só parâmetros bem formados vão ao backend; o resto cai no padrão', async () => {
    await invoices(get(`/api/bff/cards/${CARD}/invoices?from=2026-05&to=2026-13&workspaceId=outro`), params())
    expect(GET_BACKEND.mock.calls[0]![1].params).toEqual({
      path: { workspaceId: 'ws-da-sessao', cardId: CARD },
      query: { from: '2026-05', to: undefined },
    })
    await invoiceItems(get('/x?page=2&pageSize=500'), { params: Promise.resolve({ cardId: CARD, dueMonth: '2026-10' }) })
    expect(GET_BACKEND.mock.calls[1]![1].params.query).toEqual({ page: 2, pageSize: undefined })
    await installments(get('/x?status=OPEN'), params())
    expect(GET_BACKEND.mock.calls[2]![1].params.query).toEqual({ status: undefined })
    await installments(get('/x?status=FINISHED'), params())
    expect(GET_BACKEND.mock.calls[3]![1].params.query).toEqual({ status: 'FINISHED' })
    await utilizationHistory(get('/x?months=6'), params())
    expect(GET_BACKEND.mock.calls[4]![0]).toBe('/api/v1/workspaces/{workspaceId}/cards/{cardId}/utilization/history')
    expect(GET_BACKEND.mock.calls[4]![1].params.query).toEqual({ months: 6 })
    await utilizationHistory(get('/x?months=99'), params())
    expect(GET_BACKEND.mock.calls[5]![1].params.query).toEqual({ months: undefined })
  })

  it('id malformado é o 404 do cartão sem chamar o backend; mês malformado é VALIDATION_FAILED depois da sessão', async () => {
    const notFound = await utilization(get('/x'), params('../../accounts'))
    expect(notFound.status).toBe(404)
    expect(await notFound.json()).toEqual({ code: 'CARD_NOT_FOUND' })
    const noPlan = await installment(get('/x'), { params: Promise.resolve({ cardId: CARD, transactionId: 'x' }) })
    expect(await noPlan.json()).toEqual({ code: 'TRANSACTION_NOT_FOUND' })

    const month = await invoice(get('/x'), { params: Promise.resolve({ cardId: CARD, dueMonth: '2026-1' }) })
    expect(month.status).toBe(400)
    expect(await month.json()).toEqual({ code: 'VALIDATION_FAILED', details: [{ field: 'dueMonth', code: 'INVALID' }] })
    expect(GET_BACKEND).not.toHaveBeenCalled()

    getFreshSession.mockResolvedValue({ status: 'none' })
    expect((await invoice(get('/x'), { params: Promise.resolve({ cardId: CARD, dueMonth: '2026-1' }) })).status).toBe(401)
  })

  it('erros do backend viram só códigos estáveis; mensagem e traceId nunca passam', async () => {
    GET_BACKEND.mockResolvedValue({ error: apiError('INVOICE_NOT_FOUND'), response: { status: 404 } })
    const missing = await invoice(get('/x'), { params: Promise.resolve({ cardId: CARD, dueMonth: '2026-01' }) })
    expect(missing.status).toBe(404)
    expect(await missing.json()).toEqual({ code: 'INVOICE_NOT_FOUND' })

    GET_BACKEND.mockResolvedValue({ error: apiError('CARD_NOT_FOUND'), response: { status: 404 } })
    expect(await (await getCard(get('/x'), params())).json()).toEqual({ code: 'CARD_NOT_FOUND' })

    GET_BACKEND.mockResolvedValue({
      error: apiError('VALIDATION_FAILED', [{ field: 'to', code: 'RANGE_TOO_LONG', message: 'x' }]),
      response: { status: 400 },
    })
    expect(await (await invoices(get('/x'), params())).json()).toEqual({
      code: 'VALIDATION_FAILED',
      details: [{ field: 'to', code: 'RANGE_TOO_LONG' }],
    })

    GET_BACKEND.mockResolvedValue({ error: apiError('BOOM'), response: { status: 500 } })
    const failed = await summary()
    expect(failed.status).toBe(502)
    expect(await failed.json()).toEqual({ code: 'UNAVAILABLE' })
  })
})

describe('PUT, archive e reactivate do cartão', () => {
  const body = { nickname: 'Aurora', brand: 'VISA', last4: '4821', limit: '12000.00', closingDay: 28, dueDay: 5 }
  const send = (method: 'PUT' | 'POST', payload?: unknown, origin: string | null = ORIGIN) => {
    const headers = new Headers({ 'content-type': 'application/json' })
    if (origin) headers.set('origin', origin)
    return new NextRequest(`${ORIGIN}/api/bff/cards/${CARD}`, {
      method,
      headers,
      body: payload === undefined ? undefined : JSON.stringify(payload),
    })
  }

  it('edição remonta o corpo (sem campos extras) e repassa CONFLICT e CARD_ARCHIVED', async () => {
    PUT_BACKEND.mockResolvedValue(ok({ id: CARD }))
    expect((await updateCard(send('PUT', { ...body, cvv: '123' }), params())).status).toBe(200)
    expect(PUT_BACKEND.mock.calls[0]![1]).toEqual({
      params: { path: { workspaceId: 'ws-da-sessao', cardId: CARD } },
      body: {
        nickname: 'Aurora',
        institutionName: null,
        brand: 'VISA',
        last4: '4821',
        limit: { amount: '12000.00', currency: 'BRL' },
        closingDay: 28,
        dueDay: 5,
        defaultPaymentAccountId: null,
      },
    })
    PUT_BACKEND.mockResolvedValue({ error: apiError('CONFLICT'), response: { status: 409 } })
    expect(await (await updateCard(send('PUT', body), params())).json()).toEqual({ code: 'CONFLICT' })
    PUT_BACKEND.mockResolvedValue({ error: apiError('CARD_ARCHIVED'), response: { status: 409 } })
    expect(await (await updateCard(send('PUT', body), params())).json()).toEqual({ code: 'CARD_ARCHIVED' })
  })

  it('exigem Origin do app; arquivar e reativar chamam as rotas do backend', async () => {
    expect((await updateCard(send('PUT', body, 'http://evil.test'), params())).status).toBe(403)
    expect((await archive(send('POST', undefined, null), params())).status).toBe(403)
    expect(PUT_BACKEND).not.toHaveBeenCalled()
    expect(POST_BACKEND).not.toHaveBeenCalled()

    POST_BACKEND.mockResolvedValue(ok({ id: CARD, status: 'ARCHIVED' }))
    expect((await archive(send('POST'), params())).status).toBe(200)
    expect(POST_BACKEND.mock.calls[0]![0]).toBe('/api/v1/workspaces/{workspaceId}/cards/{cardId}/archive')
    expect((await reactivate(send('POST'), params())).status).toBe(200)
    expect(POST_BACKEND.mock.calls[1]![0]).toBe('/api/v1/workspaces/{workspaceId}/cards/{cardId}/reactivate')
  })
})
