// @vitest-environment node
import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { stubServerEnv } from '@/test/server-helpers'

const getFreshSession = vi.fn()
const GET_BACKEND = vi.fn()
const POST_BACKEND = vi.fn()
const PUT_BACKEND = vi.fn()

vi.mock('@/server/auth/session', () => ({ getFreshSession: () => getFreshSession() }))
vi.mock('@/server/backend', () => ({ backendClient: () => ({ GET: GET_BACKEND, POST: POST_BACKEND, PUT: PUT_BACKEND }) }))

import { GET as list, POST as create } from './route'
import { GET as get, PUT as update } from './[transactionId]/route'
import { POST as postTxn } from './[transactionId]/post/route'
import { POST as cancel } from './[transactionId]/cancel/route'
import { POST as reverse } from './[transactionId]/reverse/route'

const ORIGIN = 'http://app.test'
const ACCOUNT = '01922f5e-0000-7000-8000-0000000000aa'
const TXN = '01922f5e-0000-7000-8000-0000000000bb'
const KEY = 'web:6f1c2a54-8f0e-4c2e-9a39-2b1f5a7d9e10'
const session = { accessToken: 'tok', workspaceId: 'ws-da-sessao', displayName: 'Ana', refreshToken: 'r', expiresAt: 0 }
const params = (transactionId = TXN) => ({ params: Promise.resolve({ transactionId }) })

const transaction = {
  id: TXN,
  workspaceId: 'ws-da-sessao',
  accountId: ACCOUNT,
  destinationAccountId: null,
  type: 'EXPENSE',
  adjustmentDirection: null,
  amount: { amount: '86.40', currency: 'BRL' },
  occurredOn: '2026-10-09',
  description: 'Bistrô Lume',
  status: 'POSTED',
  source: 'MANUAL',
  refundOfTransactionId: null,
  createdAt: '2026-10-09T12:00:00Z',
  updatedAt: '2026-10-09T12:00:00Z',
}

const valid = {
  type: 'EXPENSE',
  accountId: ACCOUNT,
  amount: '86.40',
  occurredOn: '2026-10-09',
  description: '  Bistrô Lume  ',
}

function jsonRequest(
  path: string,
  method: 'POST' | 'PUT',
  body: unknown,
  { origin = ORIGIN, key = KEY }: { origin?: string | null; key?: string | null } = {},
) {
  const headers = new Headers({ 'content-type': 'application/json' })
  if (origin) headers.set('origin', origin)
  if (key) headers.set('idempotency-key', key)
  return new NextRequest(`${ORIGIN}${path}`, {
    method,
    headers,
    body: typeof body === 'string' ? body : JSON.stringify(body),
  })
}

const post = (path: string, origin: string | null = ORIGIN) =>
  new NextRequest(`${ORIGIN}${path}`, { method: 'POST', headers: origin ? { origin } : {} })

const apiError = (code: string, details: unknown[] = []) => ({ code, message: 'texto interno', details, traceId: 'abc' })

let errors: ReturnType<typeof vi.spyOn>

beforeEach(() => {
  stubServerEnv()
  getFreshSession.mockReset().mockResolvedValue({ status: 'ok', session })
  GET_BACKEND.mockReset()
  POST_BACKEND.mockReset()
  PUT_BACKEND.mockReset()
  errors = vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

describe('GET /api/bff/transactions', () => {
  it('lista no Workspace da sessão, com filtros válidos e página fixa', async () => {
    const page = { items: [transaction], page: 1, pageSize: 50, totalItems: 51 }
    GET_BACKEND.mockResolvedValue({ data: page, response: { status: 200 } })
    const response = await list(
      new NextRequest(
        `${ORIGIN}/api/bff/transactions?from=2026-10-01&to=2026-10-31&type=EXPENSE&status=POSTED&accountId=${ACCOUNT}&q=%20bistr%C3%B4%20&page=1&pageSize=100&workspaceId=outro`,
      ),
    )
    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(await response.json()).toEqual(page)
    expect(GET_BACKEND.mock.calls[0]?.[0]).toBe('/api/v1/workspaces/{workspaceId}/transactions')
    expect(GET_BACKEND.mock.calls[0]?.[1].params).toEqual({
      path: { workspaceId: 'ws-da-sessao' },
      query: {
        from: '2026-10-01',
        to: '2026-10-31',
        type: 'EXPENSE',
        status: 'POSTED',
        accountId: ACCOUNT,
        q: 'bistrô',
        page: 1,
        pageSize: 50,
      },
    })
  })

  it('filtros malformados são descartados, nunca repassados', async () => {
    GET_BACKEND.mockResolvedValue({ data: { items: [], page: 0, pageSize: 50, totalItems: 0 }, response: { status: 200 } })
    await list(
      new NextRequest(`${ORIGIN}/api/bff/transactions?from=2026-02-30&to=ontem&type=GIFT&status=x&accountId=1%20OR%201&page=-1`),
    )
    expect(GET_BACKEND.mock.calls[0]?.[1].params.query).toEqual({
      from: undefined,
      to: undefined,
      type: undefined,
      status: undefined,
      accountId: undefined,
      q: undefined,
      page: 0,
      pageSize: 50,
    })
  })

  it('VALIDATION_FAILED do backend (ex.: to antes de from) repassa só field + code', async () => {
    GET_BACKEND.mockResolvedValue({ error: apiError('VALIDATION_FAILED', [{ field: 'to', code: 'BEFORE_FROM', message: 'x' }]), response: { status: 400 } })
    const response = await list(new NextRequest(`${ORIGIN}/api/bff/transactions?from=2026-10-10&to=2026-10-01`))
    expect(response.status).toBe(400)
    expect(await response.json()).toEqual({ code: 'VALIDATION_FAILED', details: [{ field: 'to', code: 'BEFORE_FROM' }] })
  })

  it('sem sessão: 401; backend fora: 502 genérico', async () => {
    getFreshSession.mockResolvedValueOnce({ status: 'none' })
    expect((await list(new NextRequest(`${ORIGIN}/api/bff/transactions`))).status).toBe(401)
    GET_BACKEND.mockRejectedValueOnce(new Error('ECONNREFUSED'))
    const response = await list(new NextRequest(`${ORIGIN}/api/bff/transactions`))
    expect(response.status).toBe(502)
    expect(await response.json()).toEqual({ code: 'UNAVAILABLE' })
  })
})

describe('POST /api/bff/transactions (lançamento manual)', () => {
  it('repassa a Idempotency-Key e só os campos do contrato, com valor em MoneyDto string', async () => {
    POST_BACKEND.mockResolvedValue({ data: transaction, response: { status: 201 } })
    const response = await create(jsonRequest('/api/bff/transactions', 'POST', { ...valid, workspaceId: 'outro', source: 'IMPORT' }))
    expect(response.status).toBe(201)
    expect(response.headers.get('cache-control')).toBe('no-store')
    const [path, options] = POST_BACKEND.mock.calls[0]!
    expect(path).toBe('/api/v1/workspaces/{workspaceId}/transactions')
    expect(options.params).toEqual({ path: { workspaceId: 'ws-da-sessao' }, header: { 'Idempotency-Key': KEY } })
    expect(options.body).toEqual({
      type: 'EXPENSE',
      accountId: ACCOUNT,
      adjustmentDirection: null,
      amount: { amount: '86.40', currency: 'BRL' },
      occurredOn: '2026-10-09',
      description: 'Bistrô Lume',
      status: 'POSTED',
    })
  })

  it('duplo envio com a mesma chave: o replay do backend (200) volta como 200 com a MESMA transação', async () => {
    POST_BACKEND.mockResolvedValueOnce({ data: transaction, response: { status: 201 } })
    POST_BACKEND.mockResolvedValueOnce({ data: transaction, response: { status: 200 } })
    const first = await create(jsonRequest('/api/bff/transactions', 'POST', valid))
    const second = await create(jsonRequest('/api/bff/transactions', 'POST', valid))
    expect([first.status, second.status]).toEqual([201, 200])
    expect(await second.json()).toEqual(await first.json())
    expect(POST_BACKEND.mock.calls.map(([, options]) => options.params.header['Idempotency-Key'])).toEqual([KEY, KEY])
  })

  it('mesma chave com outro conteúdo: IDEMPOTENCY_KEY_REUSED (422)', async () => {
    POST_BACKEND.mockResolvedValue({ error: apiError('IDEMPOTENCY_KEY_REUSED'), response: { status: 422 } })
    const response = await create(jsonRequest('/api/bff/transactions', 'POST', { ...valid, amount: '90.00' }))
    expect(response.status).toBe(422)
    expect(await response.json()).toEqual({ code: 'IDEMPOTENCY_KEY_REUSED' })
  })

  it('ajuste exige direção; receita e despesa não aceitam direção', async () => {
    POST_BACKEND.mockResolvedValue({ data: { ...transaction, type: 'ADJUSTMENT', adjustmentDirection: 'DECREASE' }, response: { status: 201 } })
    const ok = await create(
      jsonRequest('/api/bff/transactions', 'POST', { ...valid, type: 'ADJUSTMENT', adjustmentDirection: 'DECREASE', status: 'PENDING' }),
    )
    expect(ok.status).toBe(201)
    expect(POST_BACKEND.mock.calls[0]?.[1].body).toMatchObject({ type: 'ADJUSTMENT', adjustmentDirection: 'DECREASE', status: 'PENDING' })

    const missing = await create(jsonRequest('/api/bff/transactions', 'POST', { ...valid, type: 'ADJUSTMENT' }))
    expect(await missing.json()).toEqual({ code: 'VALIDATION_FAILED', details: [{ field: 'adjustmentDirection', code: 'REQUIRED' }] })
    const extra = await create(jsonRequest('/api/bff/transactions', 'POST', { ...valid, adjustmentDirection: 'INCREASE' }))
    expect(await extra.json()).toEqual({ code: 'VALIDATION_FAILED', details: [{ field: 'adjustmentDirection', code: 'NOT_ALLOWED' }] })
    expect(POST_BACKEND).toHaveBeenCalledTimes(1)
  })

  it('recusa no BFF, sem chamar o backend: valor numérico (float), tipo fora do escopo, status inválido, campos ausentes', async () => {
    const response = await create(
      jsonRequest('/api/bff/transactions', 'POST', { type: 'TRANSFER', accountId: 'x', amount: 86.4, occurredOn: '2026-13-01', description: ' ', status: 'CANCELLED' }),
    )
    expect(response.status).toBe(400)
    expect(await response.json()).toEqual({
      code: 'VALIDATION_FAILED',
      details: [
        { field: 'type', code: 'INVALID' },
        { field: 'accountId', code: 'INVALID' },
        { field: 'amount', code: 'INVALID_FORMAT' },
        { field: 'occurredOn', code: 'INVALID' },
        { field: 'description', code: 'REQUIRED' },
        { field: 'status', code: 'INVALID' },
      ],
    })
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('exige Origin do app e Idempotency-Key bem formada', async () => {
    expect((await create(jsonRequest('/api/bff/transactions', 'POST', valid, { origin: 'http://evil.test' }))).status).toBe(403)
    expect((await create(jsonRequest('/api/bff/transactions', 'POST', valid, { origin: null }))).status).toBe(403)
    const noKey = await create(jsonRequest('/api/bff/transactions', 'POST', valid, { key: null }))
    expect(noKey.status).toBe(400)
    expect(await noKey.json()).toEqual({ code: 'INVALID_REQUEST' })
    expect((await create(jsonRequest('/api/bff/transactions', 'POST', valid, { key: 'com espaço' }))).status).toBe(400)
    expect((await create(jsonRequest('/api/bff/transactions', 'POST', '{nao-json', {}))).status).toBe(400)
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('conta arquivada (409) e conta de outro Workspace (404) passam com o código estável', async () => {
    POST_BACKEND.mockResolvedValueOnce({ error: apiError('ACCOUNT_ARCHIVED'), response: { status: 409 } })
    POST_BACKEND.mockResolvedValueOnce({ error: apiError('ACCOUNT_NOT_FOUND'), response: { status: 404 } })
    expect(await (await create(jsonRequest('/api/bff/transactions', 'POST', valid))).json()).toEqual({ code: 'ACCOUNT_ARCHIVED' })
    expect(await (await create(jsonRequest('/api/bff/transactions', 'POST', valid))).json()).toEqual({ code: 'ACCOUNT_NOT_FOUND' })
  })

  it('validação do domínio repassa só field conhecido + code estável (currency vira amount)', async () => {
    POST_BACKEND.mockResolvedValue({
      error: apiError('VALIDATION_FAILED', [
        { field: 'amount', code: 'TOO_MANY_DECIMALS', message: 'x' },
        { field: 'currency', code: 'MISMATCH', message: 'x' },
        { field: 'segredo', code: 'X', message: 'x' },
        { field: 'description', code: 'NotBlank', message: 'x' },
      ]),
      response: { status: 400 },
    })
    const response = await create(jsonRequest('/api/bff/transactions', 'POST', valid))
    expect(await response.json()).toEqual({
      code: 'VALIDATION_FAILED',
      details: [
        { field: 'amount', code: 'TOO_MANY_DECIMALS' },
        { field: 'amount', code: 'MISMATCH' },
        { field: 'description', code: 'REQUIRED' },
      ],
    })
  })

  it('falha inesperada: 502 sem valor, descrição ou chave no log', async () => {
    POST_BACKEND.mockResolvedValue({ error: apiError('INTERNAL_ERROR'), response: { status: 500 } })
    const response = await create(jsonRequest('/api/bff/transactions', 'POST', valid))
    expect(response.status).toBe(502)
    const logged = JSON.stringify(errors.mock.calls)
    expect(logged).not.toMatch(/86|Bistr|web:|ws-da-sessao/)
    expect(logged).toContain('500')
  })
})

describe('GET e PUT /api/bff/transactions/{id}', () => {
  it('GET consulta no Workspace da sessão; id inválido nem chega ao backend', async () => {
    GET_BACKEND.mockResolvedValue({ data: transaction, response: { status: 200 } })
    const response = await get(new NextRequest(`${ORIGIN}/api/bff/transactions/${TXN}`), params())
    expect(await response.json()).toEqual(transaction)
    expect(GET_BACKEND.mock.calls[0]?.[1].params).toEqual({ path: { workspaceId: 'ws-da-sessao', transactionId: TXN } })

    const invalid = await get(new NextRequest(`${ORIGIN}/api/bff/transactions/abc`), params('abc'))
    expect(invalid.status).toBe(404)
    expect(await invalid.json()).toEqual({ code: 'TRANSACTION_NOT_FOUND' })
    expect(GET_BACKEND).toHaveBeenCalledTimes(1)
  })

  it('transação de outro Workspace: o mesmo 404', async () => {
    GET_BACKEND.mockResolvedValue({ error: apiError('TRANSACTION_NOT_FOUND'), response: { status: 404 } })
    const response = await get(new NextRequest(`${ORIGIN}/api/bff/transactions/${TXN}`), params())
    expect(response.status).toBe(404)
    expect(await response.json()).toEqual({ code: 'TRANSACTION_NOT_FOUND' })
  })

  it('PUT envia só a descrição aparada; edição concorrente vira CONFLICT', async () => {
    PUT_BACKEND.mockResolvedValueOnce({ data: { ...transaction, description: 'Jantar' }, response: { status: 200 } })
    const ok = await update(jsonRequest(`/api/bff/transactions/${TXN}`, 'PUT', { description: ' Jantar ', amount: '1.00' }), params())
    expect(ok.status).toBe(200)
    expect(PUT_BACKEND.mock.calls[0]?.[1].body).toEqual({ description: 'Jantar' })

    PUT_BACKEND.mockResolvedValueOnce({ error: apiError('CONFLICT'), response: { status: 409 } })
    const conflict = await update(jsonRequest(`/api/bff/transactions/${TXN}`, 'PUT', { description: 'Jantar' }), params())
    expect(conflict.status).toBe(409)
    expect(await conflict.json()).toEqual({ code: 'CONFLICT' })

    const tooLong = await update(jsonRequest(`/api/bff/transactions/${TXN}`, 'PUT', { description: 'x'.repeat(201) }), params())
    expect(await tooLong.json()).toEqual({ code: 'VALIDATION_FAILED', details: [{ field: 'description', code: 'TOO_LONG' }] })
    expect((await update(jsonRequest(`/api/bff/transactions/${TXN}`, 'PUT', { description: 'x' }, { origin: null }), params())).status).toBe(403)
  })
})

describe('POST efetivar / cancelar / estornar', () => {
  it.each([
    ['post', postTxn, '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/post'],
    ['cancel', cancel, '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/cancel'],
    ['reverse', reverse, '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/reverse'],
  ] as const)('%s chama a ação no Workspace da sessão', async (action, handler, path) => {
    POST_BACKEND.mockResolvedValue({ data: transaction, response: { status: 200 } })
    const response = await handler(post(`/api/bff/transactions/${TXN}/${action}`), params())
    expect(response.status).toBe(200)
    expect(POST_BACKEND.mock.calls[0]).toEqual([path, { params: { path: { workspaceId: 'ws-da-sessao', transactionId: TXN } } }])
  })

  it('transição inválida → TRANSACTION_STATUS_CONFLICT; sem Origin → 403; id inválido → 404', async () => {
    POST_BACKEND.mockResolvedValue({ error: apiError('TRANSACTION_STATUS_CONFLICT'), response: { status: 409 } })
    const conflict = await reverse(post(`/api/bff/transactions/${TXN}/reverse`), params())
    expect(conflict.status).toBe(409)
    expect(await conflict.json()).toEqual({ code: 'TRANSACTION_STATUS_CONFLICT' })
    expect((await cancel(post(`/api/bff/transactions/${TXN}/cancel`, null), params())).status).toBe(403)
    expect((await postTxn(post('/api/bff/transactions/x/post'), params('x'))).status).toBe(404)
    expect(POST_BACKEND).toHaveBeenCalledTimes(1)
  })
})
