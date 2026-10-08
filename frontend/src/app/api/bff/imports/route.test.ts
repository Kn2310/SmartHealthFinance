// @vitest-environment node
import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { stubServerEnv } from '@/test/server-helpers'

const getFreshSession = vi.fn()
const POST_BACKEND = vi.fn()
const GET_BACKEND = vi.fn()

vi.mock('@/server/auth/session', () => ({ getFreshSession: () => getFreshSession() }))
vi.mock('@/server/backend', () => ({ backendClient: () => ({ POST: POST_BACKEND, GET: GET_BACKEND }) }))

import { POST as upload } from './route'
import { GET as status } from './[importId]/route'
import { GET as records } from './[importId]/records/route'
import { POST as confirm } from './[importId]/confirm/route'
import { POST as cancel } from './[importId]/cancel/route'

const ORIGIN = 'http://app.test'
const ACCOUNT = '01922f5e-0000-7000-8000-0000000000aa'
const IMPORT = '01922f5e-0000-7000-8000-0000000000bb'
const session = { accessToken: 'tok', workspaceId: 'ws-da-sessao', displayName: 'Ana', refreshToken: 'r', expiresAt: 0 }
const ok = { status: 'ok', session }
const params = (importId = IMPORT) => ({ params: Promise.resolve({ importId }) })

function uploadRequest({
  origin = ORIGIN,
  key = 'web:k-1',
  accountId = ACCOUNT,
  file = new File(['data;descricao;valor\n'], 'extrato.csv', { type: 'text/csv' }),
}: { origin?: string | null; key?: string | null; accountId?: string; file?: File | null } = {}) {
  const form = new FormData()
  if (file) form.append('file', file)
  form.append('accountId', accountId)
  const headers = new Headers()
  if (origin) headers.set('origin', origin)
  if (key) headers.set('idempotency-key', key)
  return new NextRequest(`${ORIGIN}/api/bff/imports`, { method: 'POST', body: form, headers })
}

const post = (path: string, origin: string | null = ORIGIN) =>
  new NextRequest(`${ORIGIN}${path}`, { method: 'POST', headers: origin ? { origin } : {} })

const batch = { id: IMPORT, status: 'PREVIEW', lines: { total: 3, valid: 2, invalid: 1, duplicate: 0, imported: 0 } }

beforeEach(() => {
  stubServerEnv()
  getFreshSession.mockReset().mockResolvedValue(ok)
  POST_BACKEND.mockReset()
  GET_BACKEND.mockReset()
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

describe('POST /api/bff/imports (upload)', () => {
  it('repassa o arquivo, a conta e a Idempotency-Key ao Workspace da sessão', async () => {
    POST_BACKEND.mockResolvedValue({ data: batch, response: { status: 201 } })

    const response = await upload(uploadRequest())

    expect(response.status).toBe(201)
    expect(await response.json()).toEqual(batch)
    const [path, options] = POST_BACKEND.mock.calls[0]!
    expect(path).toBe('/api/v1/workspaces/{workspaceId}/imports')
    expect(options.params).toEqual({
      path: { workspaceId: 'ws-da-sessao' },
      query: { accountId: ACCOUNT },
      header: { 'Idempotency-Key': 'web:k-1' },
    })
    expect(options.body).toBeInstanceOf(FormData)
    expect((options.body as FormData).get('file')).toBeInstanceOf(File)
    expect(((options.body as FormData).get('file') as File).name).toBe('extrato.csv')
  })

  it('replay do backend (200) continua 200', async () => {
    POST_BACKEND.mockResolvedValue({ data: batch, response: { status: 200 } })
    expect((await upload(uploadRequest())).status).toBe(200)
  })

  it.each([
    ['sem Origin', { origin: null }],
    ['Origin de outro site', { origin: 'https://evil.test' }],
    ['subdomínio irmão', { origin: 'http://evil.app.test' }],
  ])('recusa %s com 403 sem chamar o backend', async (_label, overrides) => {
    const response = await upload(uploadRequest(overrides))
    expect(response.status).toBe(403)
    expect(await response.json()).toEqual({ code: 'FORBIDDEN' })
    expect(POST_BACKEND).not.toHaveBeenCalled()
    expect(getFreshSession).not.toHaveBeenCalled()
  })

  it.each([
    ['sem Idempotency-Key', { key: null }],
    ['Idempotency-Key com caracteres reservados', { key: 'a b/c' }],
    ['conta que não é UUID', { accountId: "1' or '1'='1" }],
    ['sem arquivo', { file: null }],
  ])('%s: 400 INVALID_REQUEST', async (_label, overrides) => {
    const response = await upload(uploadRequest(overrides))
    expect(response.status).toBe(400)
    expect(await response.json()).toEqual({ code: 'INVALID_REQUEST' })
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('arquivo acima de 10 MB é recusado no BFF', async () => {
    const big = new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'grande.csv')
    const response = await upload(uploadRequest({ file: big }))
    expect(response.status).toBe(413)
    expect(await response.json()).toEqual({ code: 'FILE_TOO_LARGE' })
  })

  it('arquivo recusado: repassa só o código estável do motivo', async () => {
    POST_BACKEND.mockResolvedValue({
      error: { code: 'IMPORT_FILE_REJECTED', message: 'detalhe interno', details: [{ field: 'file', code: 'MISSING_COLUMN' }], traceId: 't-1' },
      response: { status: 422 },
    })
    const response = await upload(uploadRequest())
    expect(response.status).toBe(422)
    expect(await response.json()).toEqual({ code: 'FILE_REJECTED', reason: 'MISSING_COLUMN' })
  })

  it('motivo fora do formato de código estável é descartado', async () => {
    POST_BACKEND.mockResolvedValue({
      error: { code: 'IMPORT_FILE_REJECTED', details: [{ code: '<script>alert(1)</script>' }] },
      response: { status: 422 },
    })
    expect(await (await upload(uploadRequest())).json()).toEqual({ code: 'FILE_REJECTED', reason: 'UNKNOWN' })
  })

  it.each([
    [422, 'IDEMPOTENCY_KEY_REUSED', 422, { code: 'IDEMPOTENCY_KEY_REUSED' }],
    [409, 'ACCOUNT_ARCHIVED', 409, { code: 'ACCOUNT_ARCHIVED' }],
    [404, 'ACCOUNT_NOT_FOUND', 404, { code: 'ACCOUNT_NOT_FOUND' }],
    [404, 'WORKSPACE_NOT_FOUND', 502, { code: 'UNAVAILABLE' }],
    [413, 'PAYLOAD_TOO_LARGE', 413, { code: 'FILE_TOO_LARGE' }],
    [400, 'VALIDATION_FAILED', 400, { code: 'INVALID_REQUEST' }],
    [401, 'UNAUTHENTICATED', 401, { code: 'UNAUTHENTICATED' }],
    [500, 'INTERNAL_ERROR', 502, { code: 'UNAVAILABLE' }],
  ])('backend %s %s → %s', async (upstream, code, expected, body) => {
    POST_BACKEND.mockResolvedValue({ error: { code, message: 'segredo ws-da-sessao' }, response: { status: upstream } })
    const response = await upload(uploadRequest())
    expect(response.status).toBe(expected)
    expect(await response.json()).toEqual(body)
    expect(response.headers.get('cache-control')).toBe('no-store')
  })

  it('sem sessão: 401; backend inacessível: 502 sem detalhe', async () => {
    getFreshSession.mockResolvedValueOnce({ status: 'none' })
    expect((await upload(uploadRequest())).status).toBe(401)

    POST_BACKEND.mockRejectedValue(new Error('ECONNREFUSED 10.0.0.1'))
    const unreachable = await upload(uploadRequest())
    expect(unreachable.status).toBe(502)
    expect(JSON.stringify(await unreachable.json())).not.toContain('ECONNREFUSED')
  })
})

describe('GET /api/bff/imports/{id} e /records', () => {
  it('status: chama o batch no Workspace da sessão', async () => {
    GET_BACKEND.mockResolvedValue({ data: batch, response: { status: 200 } })
    const response = await status(new NextRequest(`${ORIGIN}/api/bff/imports/${IMPORT}`), params())
    expect(response.status).toBe(200)
    expect(GET_BACKEND.mock.calls[0]?.[1].params.path).toEqual({ workspaceId: 'ws-da-sessao', importId: IMPORT })
  })

  it('id que não é UUID nunca chega ao backend', async () => {
    const response = await status(new NextRequest(`${ORIGIN}/api/bff/imports/x`), params('../../accounts'))
    expect(response.status).toBe(404)
    expect(await response.json()).toEqual({ code: 'IMPORT_NOT_FOUND' })
    expect(GET_BACKEND).not.toHaveBeenCalled()
  })

  it('importação de outro Workspace (404 do backend) → IMPORT_NOT_FOUND', async () => {
    GET_BACKEND.mockResolvedValue({ error: { code: 'IMPORT_NOT_FOUND' }, response: { status: 404 } })
    const response = await status(new NextRequest(`${ORIGIN}/api/bff/imports/${IMPORT}`), params())
    expect(await response.json()).toEqual({ code: 'IMPORT_NOT_FOUND' })
  })

  it('records: só status conhecido e página inteira passam adiante', async () => {
    GET_BACKEND.mockResolvedValue({ data: { items: [], page: 0, pageSize: 50, totalItems: 0 }, response: { status: 200 } })

    await records(new NextRequest(`${ORIGIN}/api/bff/imports/${IMPORT}/records?status=INVALID&page=2`), params())
    await records(new NextRequest(`${ORIGIN}/api/bff/imports/${IMPORT}/records?status=DROP&page=-1`), params())

    expect(GET_BACKEND.mock.calls[0]?.[1].params.query).toEqual({ status: 'INVALID', page: 2, pageSize: 50 })
    expect(GET_BACKEND.mock.calls[1]?.[1].params.query).toEqual({ status: undefined, page: 0, pageSize: 50 })
  })
})

describe('POST confirm / cancel', () => {
  it('confirm responde 202 com o batch', async () => {
    POST_BACKEND.mockResolvedValue({ data: { ...batch, status: 'CONFIRMED' }, response: { status: 202 } })
    const response = await confirm(post(`/api/bff/imports/${IMPORT}/confirm`), params())
    expect(response.status).toBe(202)
    expect(POST_BACKEND.mock.calls[0]?.[0]).toBe('/api/v1/workspaces/{workspaceId}/imports/{importId}/confirm')
  })

  it('cancel responde 200; conflito de status vira IMPORT_STATUS_CONFLICT', async () => {
    POST_BACKEND.mockResolvedValueOnce({ data: { ...batch, status: 'CANCELLED' }, response: { status: 200 } })
    expect((await cancel(post(`/api/bff/imports/${IMPORT}/cancel`), params())).status).toBe(200)

    POST_BACKEND.mockResolvedValueOnce({ error: { code: 'IMPORT_STATUS_CONFLICT' }, response: { status: 409 } })
    const conflict = await cancel(post(`/api/bff/imports/${IMPORT}/cancel`), params())
    expect(conflict.status).toBe(409)
    expect(await conflict.json()).toEqual({ code: 'IMPORT_STATUS_CONFLICT' })
  })

  it.each([
    ['confirm', confirm],
    ['cancel', cancel],
  ])('%s exige Origin do app', async (action, handler) => {
    const response = await handler(post(`/api/bff/imports/${IMPORT}/${action}`, 'https://evil.test'), params())
    expect(response.status).toBe(403)
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })
})
