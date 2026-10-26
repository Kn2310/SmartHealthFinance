// @vitest-environment node
import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { stubServerEnv } from '@/test/server-helpers'

const getFreshSession = vi.fn()
const GET_BACKEND = vi.fn()
const POST_BACKEND = vi.fn()

vi.mock('@/server/auth/session', () => ({ getFreshSession: () => getFreshSession() }))
vi.mock('@/server/backend', () => ({ backendClient: () => ({ GET: GET_BACKEND, POST: POST_BACKEND }) }))

import { GET as list, POST as create } from './route'
import { POST as purchase } from './[cardId]/purchases/route'
import { POST as payment } from './[cardId]/payments/route'
import { POST as credit } from './[cardId]/credits/route'
import { POST as reconcile } from './[cardId]/payments/reconcile/route'

const ORIGIN = 'http://app.test'
const CARD = '01922f5e-0000-7000-8000-0000000000c1'
const ACCOUNT = '01922f5e-0000-7000-8000-0000000000aa'
const TXN = '01922f5e-0000-7000-8000-0000000000bb'
const KEY = 'web:6f1c2a54-8f0e-4c2e-9a39-2b1f5a7d9e10'
const session = { accessToken: 'tok', workspaceId: 'ws-da-sessao', displayName: 'Ana', refreshToken: 'r', expiresAt: 0 }
const params = (cardId = CARD) => ({ params: Promise.resolve({ cardId }) })

const card = {
  id: CARD,
  workspaceId: 'ws-da-sessao',
  nickname: 'Aurora Platinum',
  institutionName: null,
  brand: 'MASTERCARD',
  last4: '4821',
  limit: { amount: '12000.00', currency: 'BRL' },
  closingDay: 28,
  dueDay: 5,
  defaultPaymentAccountId: null,
  status: 'ACTIVE',
  createdAt: '2026-10-22T12:00:00Z',
  updatedAt: '2026-10-22T12:00:00Z',
}

const validCard = {
  nickname: '  Aurora Platinum ',
  brand: 'MASTERCARD',
  last4: '4821',
  limit: '12000.00',
  closingDay: 28,
  dueDay: 5,
}

function jsonRequest(
  path: string,
  body: unknown,
  { origin = ORIGIN, key = KEY }: { origin?: string | null; key?: string | null } = {},
) {
  const headers = new Headers({ 'content-type': 'application/json' })
  if (origin) headers.set('origin', origin)
  if (key) headers.set('idempotency-key', key)
  return new NextRequest(`${ORIGIN}${path}`, { method: 'POST', headers, body: JSON.stringify(body) })
}

const apiError = (code: string, details: unknown[] = []) => ({ code, message: 'texto interno', details, traceId: 'abc' })

beforeEach(() => {
  stubServerEnv()
  getFreshSession.mockReset().mockResolvedValue({ status: 'ok', session })
  GET_BACKEND.mockReset()
  POST_BACKEND.mockReset()
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

describe('GET e POST /api/bff/cards', () => {
  it('lista os cartões do Workspace da sessão (arquivados só com includeArchived)', async () => {
    GET_BACKEND.mockResolvedValue({ data: { items: [card] }, response: { status: 200 } })
    const response = await list(new NextRequest(`${ORIGIN}/api/bff/cards?includeArchived=true&workspaceId=outro`))
    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(GET_BACKEND.mock.calls[0]![1].params).toEqual({
      path: { workspaceId: 'ws-da-sessao' },
      query: { includeArchived: true },
    })
  })

  it('cadastra só com os campos do contrato: número completo, CVV e validade nunca passam', async () => {
    POST_BACKEND.mockResolvedValue({ data: card, response: { status: 201 } })
    const response = await create(
      jsonRequest('/api/bff/cards', { ...validCard, number: '4111111111111111', cvv: '123', expiry: '12/29' }),
    )
    expect(response.status).toBe(201)
    expect(POST_BACKEND.mock.calls[0]![1].body).toEqual({
      nickname: 'Aurora Platinum',
      institutionName: null,
      brand: 'MASTERCARD',
      last4: '4821',
      limit: { amount: '12000.00', currency: 'BRL' },
      closingDay: 28,
      dueDay: 5,
      defaultPaymentAccountId: null,
    })
  })

  it('recusa no BFF, sem chamar o backend: mais de 4 dígitos, bandeira e dias inválidos, limite numérico', async () => {
    const response = await create(
      jsonRequest('/api/bff/cards', { nickname: ' ', brand: 'DINERS', last4: '41111', limit: 12000, closingDay: 0, dueDay: '5' }),
    )
    expect(response.status).toBe(400)
    expect(await response.json()).toEqual({
      code: 'VALIDATION_FAILED',
      details: [
        { field: 'nickname', code: 'REQUIRED' },
        { field: 'brand', code: 'INVALID' },
        { field: 'last4', code: 'INVALID' },
        { field: 'limit', code: 'INVALID_FORMAT' },
        { field: 'closingDay', code: 'OUT_OF_RANGE' },
        { field: 'dueDay', code: 'OUT_OF_RANGE' },
      ],
    })
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('exige Origin do app e repassa só códigos estáveis', async () => {
    expect((await create(jsonRequest('/api/bff/cards', validCard, { origin: 'http://evil.test' }))).status).toBe(403)
    POST_BACKEND.mockResolvedValue({
      error: apiError('VALIDATION_FAILED', [{ field: 'dueDay', code: 'CONFLICTS_WITH_CLOSING', message: 'x' }]),
      response: { status: 400 },
    })
    expect(await (await create(jsonRequest('/api/bff/cards', validCard))).json()).toEqual({
      code: 'VALIDATION_FAILED',
      details: [{ field: 'dueDay', code: 'CONFLICTS_WITH_CLOSING' }],
    })
    POST_BACKEND.mockResolvedValue({ error: apiError('ALGO_NOVO'), response: { status: 500 } })
    const unknown = await create(jsonRequest('/api/bff/cards', validCard))
    expect(unknown.status).toBe(502)
    expect(await unknown.json()).toEqual({ code: 'UNAVAILABLE' })
  })
})

describe('POST compras, pagamentos e estornos no cartão', () => {
  it('compra parcelada: repassa a Idempotency-Key e remonta o corpo', async () => {
    POST_BACKEND.mockResolvedValue({ data: { transaction: {}, installments: [] }, response: { status: 201 } })
    const response = await purchase(
      jsonRequest(`/api/bff/cards/${CARD}/purchases`, {
        amount: '300.00', occurredOn: '2026-10-22', description: ' Notebook ', installmentCount: 3, cardNumber: '4111',
      }),
      params(),
    )
    expect(response.status).toBe(201)
    const [path, options] = POST_BACKEND.mock.calls[0]!
    expect(path).toBe('/api/v1/workspaces/{workspaceId}/cards/{cardId}/purchases')
    expect(options.params).toEqual({ path: { workspaceId: 'ws-da-sessao', cardId: CARD }, header: { 'Idempotency-Key': KEY } })
    expect(options.body).toEqual({
      amount: { amount: '300.00', currency: 'BRL' },
      occurredOn: '2026-10-22',
      description: 'Notebook',
      installmentCount: 3,
    })
  })

  it('pagamento de fatura (transferência) e estorno vinculado', async () => {
    POST_BACKEND.mockResolvedValue({ data: { transaction: {} }, response: { status: 200 } })
    const paid = await payment(
      jsonRequest(`/api/bff/cards/${CARD}/payments`, { accountId: ACCOUNT, amount: '100.00', occurredOn: '2026-10-22' }),
      params(),
    )
    expect(paid.status).toBe(200)
    expect(POST_BACKEND.mock.calls[0]![1].body).toEqual({
      accountId: ACCOUNT,
      amount: { amount: '100.00', currency: 'BRL' },
      occurredOn: '2026-10-22',
      description: null,
    })
    await credit(
      jsonRequest(`/api/bff/cards/${CARD}/credits`, {
        amount: '10.00', occurredOn: '2026-10-22', description: 'Estorno', refundOfTransactionId: TXN,
      }),
      params(),
    )
    expect(POST_BACKEND.mock.calls[1]![1].body).toMatchObject({ refundOfTransactionId: TXN })
  })

  it('recusa sem chamar o backend: parcelas fora de 1..48, sem chave, cartão malformado', async () => {
    const tooMany = await purchase(
      jsonRequest(`/api/bff/cards/${CARD}/purchases`, { amount: '10.00', occurredOn: '2026-10-22', description: 'X', installmentCount: 49 }),
      params(),
    )
    expect(await tooMany.json()).toEqual({ code: 'VALIDATION_FAILED', details: [{ field: 'installmentCount', code: 'OUT_OF_RANGE' }] })
    const noKey = await purchase(
      jsonRequest(`/api/bff/cards/${CARD}/purchases`, { amount: '10.00', occurredOn: '2026-10-22', description: 'X' }, { key: null }),
      params(),
    )
    expect(noKey.status).toBe(400)
    const badCard = await payment(jsonRequest('/api/bff/cards/x/payments', {}), params('nao-e-uuid'))
    expect(badCard.status).toBe(404)
    expect(await badCard.json()).toEqual({ code: 'CARD_NOT_FOUND' })
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('cartão arquivado, chave reaproveitada e vínculo de outro cartão passam com o código estável', async () => {
    const body = { amount: '10.00', occurredOn: '2026-10-22', description: 'X' }
    POST_BACKEND.mockResolvedValueOnce({ error: apiError('CARD_ARCHIVED'), response: { status: 409 } })
    expect(await (await purchase(jsonRequest(`/api/bff/cards/${CARD}/purchases`, body), params())).json()).toEqual({
      code: 'CARD_ARCHIVED',
    })
    POST_BACKEND.mockResolvedValueOnce({ error: apiError('IDEMPOTENCY_KEY_REUSED'), response: { status: 422 } })
    expect((await purchase(jsonRequest(`/api/bff/cards/${CARD}/purchases`, body), params())).status).toBe(422)
    POST_BACKEND.mockResolvedValueOnce({
      error: apiError('VALIDATION_FAILED', [{ field: 'refundOfTransactionId', code: 'NOT_SAME_CARD' }]),
      response: { status: 400 },
    })
    expect(await (await credit(jsonRequest(`/api/bff/cards/${CARD}/credits`, body), params())).json()).toEqual({
      code: 'VALIDATION_FAILED',
      details: [{ field: 'refundOfTransactionId', code: 'NOT_SAME_CARD' }],
    })
  })
})

describe('POST /api/bff/cards/{id}/payments/reconcile', () => {
  const path = `/api/bff/cards/${CARD}/payments/reconcile`

  it('repassa só o id do débito, sem Idempotency-Key, e mantém 201/200', async () => {
    const payment = { payment: { id: TXN, type: 'TRANSFER' }, matchedExisting: false }
    POST_BACKEND.mockResolvedValueOnce({ data: payment, response: { status: 201 } })

    const response = await reconcile(jsonRequest(path, { transactionId: TXN, extra: 'x' }, { key: null }), params())

    expect(response.status).toBe(201)
    expect(await response.json()).toEqual(payment)
    const [url, options] = POST_BACKEND.mock.calls[0]!
    expect(url).toBe('/api/v1/workspaces/{workspaceId}/cards/{cardId}/payments/reconcile')
    expect(options).toEqual({
      params: { path: { workspaceId: 'ws-da-sessao', cardId: CARD } },
      body: { transactionId: TXN },
    })

    POST_BACKEND.mockResolvedValueOnce({ data: { ...payment, matchedExisting: true }, response: { status: 200 } })
    expect((await reconcile(jsonRequest(path, { transactionId: TXN }), params())).status).toBe(200)
  })

  it('recusa sem chamar o backend: id malformado, cartão malformado e Origin de fora', async () => {
    expect(await (await reconcile(jsonRequest(path, { transactionId: 'x' }), params())).json()).toEqual({
      code: 'VALIDATION_FAILED',
      details: [{ field: 'transactionId', code: 'INVALID' }],
    })
    expect((await reconcile(jsonRequest(path, { transactionId: TXN }), params('nope'))).status).toBe(404)
    expect((await reconcile(jsonRequest(path, { transactionId: TXN }, { origin: 'https://evil.test' }), params()))
      .status).toBe(403)
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('débito inelegível, já estornado ou de outro Workspace passam com o código estável', async () => {
    POST_BACKEND.mockResolvedValueOnce({
      error: apiError('VALIDATION_FAILED', [{ field: 'transactionId', code: 'NOT_RECLASSIFIABLE' }]),
      response: { status: 400 },
    })
    expect(await (await reconcile(jsonRequest(path, { transactionId: TXN }), params())).json()).toEqual({
      code: 'VALIDATION_FAILED',
      details: [{ field: 'transactionId', code: 'NOT_RECLASSIFIABLE' }],
    })
    POST_BACKEND.mockResolvedValueOnce({ error: apiError('TRANSACTION_STATUS_CONFLICT'), response: { status: 409 } })
    expect((await reconcile(jsonRequest(path, { transactionId: TXN }), params())).status).toBe(409)
    POST_BACKEND.mockResolvedValueOnce({ error: apiError('TRANSACTION_NOT_FOUND'), response: { status: 404 } })
    expect(await (await reconcile(jsonRequest(path, { transactionId: TXN }), params())).json()).toEqual({
      code: 'TRANSACTION_NOT_FOUND',
    })
  })
})
