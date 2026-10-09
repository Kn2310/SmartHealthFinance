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
import { GET as get, PUT as update } from './[accountId]/route'
import { POST as archive } from './[accountId]/archive/route'
import { POST as reactivate } from './[accountId]/reactivate/route'

const ORIGIN = 'http://app.test'
const ACCOUNT = '01922f5e-0000-7000-8000-0000000000aa'
const session = { accessToken: 'tok', workspaceId: 'ws-da-sessao', displayName: 'Ana', refreshToken: 'r', expiresAt: 0 }
const params = (accountId = ACCOUNT) => ({ params: Promise.resolve({ accountId }) })

const account = {
  id: ACCOUNT,
  workspaceId: 'ws-da-sessao',
  name: 'Banco Aurora',
  type: 'CHECKING',
  institutionName: 'Aurora',
  currency: 'BRL',
  includedInTotal: true,
  status: 'ACTIVE',
  createdAt: '2026-10-01T12:00:00Z',
  updatedAt: '2026-10-01T12:00:00Z',
}

const valid = { name: 'Banco Aurora', type: 'CHECKING', institutionName: 'Aurora', includedInTotal: true }

function jsonRequest(
  path: string,
  method: 'POST' | 'PUT',
  body: unknown,
  { origin = ORIGIN }: { origin?: string | null } = {},
) {
  const headers = new Headers({ 'content-type': 'application/json' })
  if (origin) headers.set('origin', origin)
  return new NextRequest(`${ORIGIN}${path}`, {
    method,
    headers,
    body: typeof body === 'string' ? body : JSON.stringify(body),
  })
}

const post = (path: string, origin: string | null = ORIGIN) =>
  new NextRequest(`${ORIGIN}${path}`, { method: 'POST', headers: origin ? { origin } : {} })

beforeEach(() => {
  stubServerEnv()
  getFreshSession.mockReset().mockResolvedValue({ status: 'ok', session })
  GET_BACKEND.mockReset()
  POST_BACKEND.mockReset()
  PUT_BACKEND.mockReset()
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

describe('GET /api/bff/accounts', () => {
  it('sem parâmetro lista só as ativas do Workspace da sessão (uso da importação)', async () => {
    GET_BACKEND.mockResolvedValue({ data: { items: [account] }, response: { status: 200 } })
    const response = await list(new NextRequest(`${ORIGIN}/api/bff/accounts`))
    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(await response.json()).toEqual({ items: [account] })
    expect(GET_BACKEND.mock.calls[0]?.[1].params).toEqual({ path: { workspaceId: 'ws-da-sessao' }, query: { includeArchived: false } })
  })

  it('includeArchived=true inclui as arquivadas; qualquer outro valor não', async () => {
    GET_BACKEND.mockResolvedValue({ data: { items: [] }, response: { status: 200 } })
    await list(new NextRequest(`${ORIGIN}/api/bff/accounts?includeArchived=true`))
    await list(new NextRequest(`${ORIGIN}/api/bff/accounts?includeArchived=1`))
    expect(GET_BACKEND.mock.calls[0]?.[1].params.query).toEqual({ includeArchived: true })
    expect(GET_BACKEND.mock.calls[1]?.[1].params.query).toEqual({ includeArchived: false })
  })

  it('o Workspace vem da sessão, nunca da query', async () => {
    GET_BACKEND.mockResolvedValue({ data: { items: [] }, response: { status: 200 } })
    await list(new NextRequest(`${ORIGIN}/api/bff/accounts?workspaceId=outro`))
    expect(GET_BACKEND.mock.calls[0]?.[1].params.path).toEqual({ workspaceId: 'ws-da-sessao' })
  })

  it('falha do backend: 502 genérico; sem sessão: 401', async () => {
    GET_BACKEND.mockResolvedValue({ error: { code: 'INTERNAL_ERROR', message: 'interno' }, response: { status: 500 } })
    const failure = await list(new NextRequest(`${ORIGIN}/api/bff/accounts`))
    expect(failure.status).toBe(502)
    expect(await failure.json()).toEqual({ code: 'UNAVAILABLE' })

    getFreshSession.mockResolvedValue({ status: 'none' })
    GET_BACKEND.mockClear()
    expect((await list(new NextRequest(`${ORIGIN}/api/bff/accounts`))).status).toBe(401)
    expect(GET_BACKEND).not.toHaveBeenCalled()
  })
})

describe('POST /api/bff/accounts (criar)', () => {
  it('cria no Workspace da sessão só com os campos do contrato (nome e instituição aparados)', async () => {
    POST_BACKEND.mockResolvedValue({ data: account, response: { status: 201 } })
    const response = await create(
      jsonRequest('/api/bff/accounts', 'POST', {
        name: '  Banco Aurora ',
        type: 'CHECKING',
        institutionName: '   ',
        workspaceId: 'outro-workspace',
        status: 'ARCHIVED',
      }),
    )
    expect(response.status).toBe(201)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(await response.json()).toEqual(account)
    const [path, options] = POST_BACKEND.mock.calls[0]!
    expect(path).toBe('/api/v1/workspaces/{workspaceId}/accounts')
    expect(options.params).toEqual({ path: { workspaceId: 'ws-da-sessao' } })
    // includedInTotal ausente no criar = true (padrão do backend); campos extras não passam.
    expect(options.body).toEqual({ name: 'Banco Aurora', type: 'CHECKING', institutionName: null, includedInTotal: true })
  })

  it.each([
    ['sem Origin', null],
    ['Origin de outro site', 'https://evil.test'],
    ['subdomínio irmão', 'http://evil.app.test'],
  ])('recusa %s com 403 sem chamar o backend', async (_label, origin) => {
    const response = await create(jsonRequest('/api/bff/accounts', 'POST', valid, { origin }))
    expect(response.status).toBe(403)
    expect(await response.json()).toEqual({ code: 'FORBIDDEN' })
    expect(POST_BACKEND).not.toHaveBeenCalled()
    expect(getFreshSession).not.toHaveBeenCalled()
  })

  it.each([
    ['nome vazio', { ...valid, name: '   ' }, [{ field: 'name', code: 'REQUIRED' }]],
    ['nome longo', { ...valid, name: 'x'.repeat(101) }, [{ field: 'name', code: 'TOO_LONG' }]],
    ['nome com caractere de controle', { ...valid, name: 'a\u0007b' }, [{ field: 'name', code: 'INVALID_CHARACTERS' }]],
    ['nome que não é texto', { ...valid, name: 42 }, [{ field: 'name', code: 'INVALID' }]],
    ['tipo fora do enum', { ...valid, type: 'CREDIT_CARD' }, [{ field: 'type', code: 'INVALID' }]],
    ['sem tipo', { name: 'A' }, [{ field: 'type', code: 'REQUIRED' }]],
    ['instituição longa', { ...valid, institutionName: 'y'.repeat(101) }, [{ field: 'institutionName', code: 'TOO_LONG' }]],
    ['inclusão que não é booleano', { ...valid, includedInTotal: 'sim' }, [{ field: 'includedInTotal', code: 'INVALID' }]],
  ])('%s: 400 VALIDATION_FAILED com o campo, sem chamar o backend', async (_label, body, details) => {
    const response = await create(jsonRequest('/api/bff/accounts', 'POST', body))
    expect(response.status).toBe(400)
    expect(await response.json()).toEqual({ code: 'VALIDATION_FAILED', details })
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('aceita exatamente 100 caracteres', async () => {
    POST_BACKEND.mockResolvedValue({ data: account, response: { status: 201 } })
    const response = await create(jsonRequest('/api/bff/accounts', 'POST', { ...valid, name: 'x'.repeat(100) }))
    expect(response.status).toBe(201)
  })

  it.each([
    ['JSON malformado', '{"name":'],
    ['lista em vez de objeto', '[]'],
    ['corpo grande demais', JSON.stringify({ ...valid, padding: 'z'.repeat(5000) })],
  ])('%s: 400 INVALID_REQUEST', async (_label, body) => {
    const response = await create(jsonRequest('/api/bff/accounts', 'POST', body))
    expect(response.status).toBe(400)
    expect(await response.json()).toEqual({ code: 'INVALID_REQUEST' })
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('VALIDATION_FAILED do backend: repassa só field + code estáveis e conhecidos', async () => {
    POST_BACKEND.mockResolvedValue({
      error: {
        code: 'VALIDATION_FAILED',
        message: 'A requisição contém dados inválidos.',
        details: [
          { field: 'name', code: 'Size', message: 'tamanho deve ser entre 0 e 100' },
          { field: 'type', code: 'INVALID', message: 'Valor inválido.' },
          { field: 'institutionName', code: '<script>', message: 'x' },
          { field: 'workspaceId', code: 'REQUIRED' },
          { code: 'GLOBAL' },
        ],
        traceId: 't-1',
      },
      response: { status: 400 },
    })
    const response = await create(jsonRequest('/api/bff/accounts', 'POST', valid))
    expect(response.status).toBe(400)
    expect(await response.json()).toEqual({
      code: 'VALIDATION_FAILED',
      details: [
        { field: 'name', code: 'TOO_LONG' },
        { field: 'type', code: 'INVALID' },
        { field: 'institutionName', code: 'INVALID' },
      ],
    })
  })

  it.each([
    [400, 'MALFORMED_REQUEST', 400, { code: 'INVALID_REQUEST' }],
    [404, 'WORKSPACE_NOT_FOUND', 502, { code: 'UNAVAILABLE' }],
    [401, 'UNAUTHENTICATED', 401, { code: 'UNAUTHENTICATED' }],
    [500, 'INTERNAL_ERROR', 502, { code: 'UNAVAILABLE' }],
  ])('backend %s %s → %s', async (upstream, code, expected, body) => {
    POST_BACKEND.mockResolvedValue({ error: { code, message: 'segredo ws-da-sessao' }, response: { status: upstream } })
    const response = await create(jsonRequest('/api/bff/accounts', 'POST', valid))
    expect(response.status).toBe(expected)
    expect(await response.json()).toEqual(body)
    expect(response.headers.get('cache-control')).toBe('no-store')
  })

  it('backend inacessível: 502 sem detalhe e sem dados da conta no log', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    POST_BACKEND.mockRejectedValue(new Error('ECONNREFUSED 10.0.0.1'))
    const response = await create(jsonRequest('/api/bff/accounts', 'POST', valid))
    expect(response.status).toBe(502)
    expect(JSON.stringify(await response.json())).not.toContain('ECONNREFUSED')
    expect(JSON.stringify(log.mock.calls)).not.toContain('Banco Aurora')
  })
})

describe('GET e PUT /api/bff/accounts/{id}', () => {
  it('GET consulta a conta no Workspace da sessão', async () => {
    GET_BACKEND.mockResolvedValue({ data: account, response: { status: 200 } })
    const response = await get(new NextRequest(`${ORIGIN}/api/bff/accounts/${ACCOUNT}`), params())
    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(GET_BACKEND.mock.calls[0]?.[0]).toBe('/api/v1/workspaces/{workspaceId}/accounts/{accountId}')
    expect(GET_BACKEND.mock.calls[0]?.[1].params.path).toEqual({ workspaceId: 'ws-da-sessao', accountId: ACCOUNT })
  })

  it('id que não é UUID nunca chega ao backend', async () => {
    const response = await get(new NextRequest(`${ORIGIN}/api/bff/accounts/x`), params('../imports'))
    expect(response.status).toBe(404)
    expect(await response.json()).toEqual({ code: 'ACCOUNT_NOT_FOUND' })
    expect(GET_BACKEND).not.toHaveBeenCalled()
  })

  it('conta de outro Workspace (404 do backend) → ACCOUNT_NOT_FOUND', async () => {
    GET_BACKEND.mockResolvedValue({ error: { code: 'ACCOUNT_NOT_FOUND', traceId: 't' }, response: { status: 404 } })
    const response = await get(new NextRequest(`${ORIGIN}/api/bff/accounts/${ACCOUNT}`), params())
    expect(response.status).toBe(404)
    expect(await response.json()).toEqual({ code: 'ACCOUNT_NOT_FOUND' })
  })

  it('PUT substitui os 4 campos; includedInTotal é obrigatório', async () => {
    PUT_BACKEND.mockResolvedValue({ data: { ...account, name: 'Aurora PJ' }, response: { status: 200 } })
    const ok = await update(
      jsonRequest(`/api/bff/accounts/${ACCOUNT}`, 'PUT', { ...valid, name: 'Aurora PJ', includedInTotal: false }),
      params(),
    )
    expect(ok.status).toBe(200)
    const [path, options] = PUT_BACKEND.mock.calls[0]!
    expect(path).toBe('/api/v1/workspaces/{workspaceId}/accounts/{accountId}')
    expect(options.params.path).toEqual({ workspaceId: 'ws-da-sessao', accountId: ACCOUNT })
    expect(options.body).toEqual({ name: 'Aurora PJ', type: 'CHECKING', institutionName: 'Aurora', includedInTotal: false })

    const missing = await update(
      jsonRequest(`/api/bff/accounts/${ACCOUNT}`, 'PUT', { name: 'A', type: 'OTHER', institutionName: null }),
      params(),
    )
    expect(missing.status).toBe(400)
    expect(await missing.json()).toEqual({ code: 'VALIDATION_FAILED', details: [{ field: 'includedInTotal', code: 'REQUIRED' }] })
    expect(PUT_BACKEND).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['ACCOUNT_ARCHIVED', 409],
    ['CONFLICT', 409],
  ])('PUT: %s → 409 com o código', async (code, status) => {
    PUT_BACKEND.mockResolvedValue({ error: { code, message: 'x' }, response: { status } })
    const response = await update(jsonRequest(`/api/bff/accounts/${ACCOUNT}`, 'PUT', valid), params())
    expect(response.status).toBe(409)
    expect(await response.json()).toEqual({ code })
  })

  it('PUT exige Origin do app e id válido', async () => {
    expect((await update(jsonRequest(`/api/bff/accounts/${ACCOUNT}`, 'PUT', valid, { origin: null }), params())).status).toBe(403)
    expect((await update(jsonRequest('/api/bff/accounts/x', 'PUT', valid), params('x'))).status).toBe(404)
    expect(PUT_BACKEND).not.toHaveBeenCalled()
  })
})

describe('POST archive / reactivate', () => {
  it.each([
    ['archive', archive, 'ARCHIVED'],
    ['reactivate', reactivate, 'ACTIVE'],
  ] as const)('%s chama a ação no Workspace da sessão', async (action, handler, status) => {
    POST_BACKEND.mockResolvedValue({ data: { ...account, status }, response: { status: 200 } })
    const response = await handler(post(`/api/bff/accounts/${ACCOUNT}/${action}`), params())
    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect((await response.json()).status).toBe(status)
    expect(POST_BACKEND.mock.calls[0]?.[0]).toBe(`/api/v1/workspaces/{workspaceId}/accounts/{accountId}/${action}`)
    expect(POST_BACKEND.mock.calls[0]?.[1].params.path).toEqual({ workspaceId: 'ws-da-sessao', accountId: ACCOUNT })
  })

  it.each([
    ['archive', archive],
    ['reactivate', reactivate],
  ])('%s exige Origin do app e id válido', async (action, handler) => {
    expect((await handler(post(`/api/bff/accounts/${ACCOUNT}/${action}`, 'https://evil.test'), params())).status).toBe(403)
    expect((await handler(post(`/api/bff/accounts/x/${action}`), params('x'))).status).toBe(404)
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('conta de outro Workspace → ACCOUNT_NOT_FOUND', async () => {
    POST_BACKEND.mockResolvedValue({ error: { code: 'ACCOUNT_NOT_FOUND' }, response: { status: 404 } })
    const response = await archive(post(`/api/bff/accounts/${ACCOUNT}/archive`), params())
    expect(response.status).toBe(404)
    expect(await response.json()).toEqual({ code: 'ACCOUNT_NOT_FOUND' })
  })
})
