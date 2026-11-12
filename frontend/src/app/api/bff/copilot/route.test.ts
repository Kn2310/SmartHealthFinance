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

import { DELETE as deleteAll, GET as listConversations } from './conversations/route'
import { DELETE as deleteOne, GET as getOne } from './conversations/[conversationId]/route'
import { POST as ask } from './questions/route'
import { PUT as feedback } from './questions/[requestId]/feedback/route'
import { GET as usage } from './usage/route'

const ORIGIN = 'http://app.test'
const ID = '01922f5e-0000-7000-8000-000000000001'
const CARD = '01922f5e-0000-7000-8000-0000000000c1'
const KEY = '5b0f3b2e-8c43-4d0c-9f0e-2d6a1c9f7a10'
const session = { accessToken: 'tok', workspaceId: 'ws-da-sessao', displayName: 'Ana', refreshToken: 'r', expiresAt: 0 }
const ok = (status = 200) => new Response(null, { status })
const answer = { requestId: ID, code: 'OK' }

function post(
  body: unknown,
  { origin = ORIGIN, key = KEY }: { origin?: string | null; key?: string | null } = {},
) {
  const headers = new Headers({ 'content-type': 'application/json' })
  if (origin) headers.set('origin', origin)
  if (key) headers.set('idempotency-key', key)
  return new NextRequest(`${ORIGIN}/api/bff/copilot/questions`, {
    method: 'POST',
    headers,
    body: typeof body === 'string' ? body : JSON.stringify(body),
  })
}

function mutate(url: string, method: string, body?: unknown, origin: string | null = ORIGIN) {
  const headers = new Headers({ 'content-type': 'application/json' })
  if (origin) headers.set('origin', origin)
  return new NextRequest(`${ORIGIN}${url}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
}

beforeEach(() => {
  stubServerEnv()
  getFreshSession.mockReset().mockResolvedValue({ status: 'ok', session })
  for (const mock of [GET_BACKEND, POST_BACKEND, PUT_BACKEND, DELETE_BACKEND]) mock.mockReset()
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

describe('POST /api/bff/copilot/questions', () => {
  it('repassa a Idempotency-Key e remonta o corpo só com os campos do contrato', async () => {
    POST_BACKEND.mockResolvedValue({ data: answer, response: ok() })

    const response = await ask(
      post({
        question: '  Por que minha fatura subiu?  ',
        previousQuestions: ['Onde gastei?'],
        context: { kind: 'INVOICE', cardId: CARD, month: '2026-10', insightId: ID, extra: 'x' },
        workspaceId: 'outro-workspace',
        userId: 'x',
      }),
    )

    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toContain('no-store')
    expect(await response.json()).toEqual(answer)
    const [path, options] = POST_BACKEND.mock.calls[0]!
    expect(path).toBe('/api/v1/workspaces/{workspaceId}/copilot/questions')
    expect(options.params).toEqual({ path: { workspaceId: 'ws-da-sessao' }, header: { 'Idempotency-Key': KEY } })
    expect(options.body).toEqual({
      question: 'Por que minha fatura subiu?',
      previousQuestions: ['Onde gastei?'],
      context: { kind: 'INVOICE', cardId: CARD, month: '2026-10' },
    })
    // Timeout acima do orçamento de 30 s do pipeline.
    expect(options.signal).toBeInstanceOf(AbortSignal)
  })

  it('continua uma conversa guardada só com o conversationId', async () => {
    POST_BACKEND.mockResolvedValue({ data: answer, response: ok() })
    await ask(post({ question: 'E em agosto?', conversationId: ID }))
    expect(POST_BACKEND.mock.calls[0]![1].body).toEqual({ question: 'E em agosto?', conversationId: ID })
  })

  it.each([
    ['pergunta vazia', { question: '   ' }],
    ['pergunta longa', { question: 'a'.repeat(501) }],
    ['sem pergunta', {}],
    ['mais de 3 anteriores', { question: 'x', previousQuestions: ['a', 'b', 'c', 'd'] }],
    ['anterior longa', { question: 'x', previousQuestions: ['a'.repeat(501)] }],
    ['conversationId com previousQuestions', { question: 'x', conversationId: ID, previousQuestions: ['a'] }],
    ['conversationId inválido', { question: 'x', conversationId: 'nao-uuid' }],
    ['contexto de tipo desconhecido', { question: 'x', context: { kind: 'OTHER' } }],
    ['contexto com id inválido', { question: 'x', context: { kind: 'INSIGHT', insightId: 'abc' } }],
    ['contexto de fatura sem mês', { question: 'x', context: { kind: 'INVOICE', cardId: CARD } }],
    ['contexto de fatura com mês inválido', { question: 'x', context: { kind: 'INVOICE', cardId: CARD, month: '2026-13' } }],
  ])('recusa %s com 400 sem chamar o backend', async (_name, body) => {
    const response = await ask(post(body))
    expect(response.status).toBe(400)
    expect(await response.json()).toEqual({ code: 'VALIDATION_FAILED' })
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('corpo inválido, que não é objeto ou grande demais → 400', async () => {
    for (const body of ['{', '[]', JSON.stringify({ question: 'x', pad: 'y'.repeat(20_000) })]) {
      expect((await ask(post(body))).status).toBe(400)
    }
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('exige Idempotency-Key no formato do backend', async () => {
    expect((await ask(post({ question: 'x' }, { key: null }))).status).toBe(400)
    expect((await ask(post({ question: 'x' }, { key: 'chave com espaço' }))).status).toBe(400)
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('sem Origin ou de outra origem → 403 sem chamar o backend', async () => {
    expect((await ask(post({ question: 'x' }, { origin: null }))).status).toBe(403)
    expect((await ask(post({ question: 'x' }, { origin: 'http://evil.test' }))).status).toBe(403)
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('sem sessão → 401; IdP indisponível ou rede fora → 502', async () => {
    getFreshSession.mockResolvedValueOnce({ status: 'none' })
    expect((await ask(post({ question: 'x' }))).status).toBe(401)
    getFreshSession.mockResolvedValueOnce({ status: 'unavailable' })
    expect((await ask(post({ question: 'x' }))).status).toBe(502)
    POST_BACKEND.mockRejectedValue(new Error('timeout'))
    const response = await ask(post({ question: 'x' }))
    expect(response.status).toBe(502)
    expect(await response.json()).toEqual({ code: 'UNAVAILABLE' })
  })

  it.each([
    [403, 'COPILOT_CONSENT_REQUIRED'],
    [404, 'COPILOT_CONTEXT_NOT_FOUND'],
    [404, 'COPILOT_CONVERSATION_NOT_FOUND'],
    [409, 'COPILOT_REQUEST_IN_PROGRESS'],
    [409, 'COPILOT_ALREADY_ANSWERED'],
    [422, 'IDEMPOTENCY_KEY_REUSED'],
    [503, 'COPILOT_UNAVAILABLE'],
  ])('repassa o código estável %i %s', async (status, code) => {
    POST_BACKEND.mockResolvedValue({ error: { code, message: 'texto do servidor', traceId: 't' }, response: ok(status) })
    const response = await ask(post({ question: 'x' }))
    expect(response.status).toBe(status)
    expect(await response.json()).toEqual({ code })
  })

  it('429 de rate limit repassa o Retry-After no header e no corpo', async () => {
    POST_BACKEND.mockResolvedValue({
      error: { code: 'COPILOT_RATE_LIMITED', message: 'x', details: [] },
      response: new Response(null, { status: 429, headers: { 'Retry-After': '17' } }),
    })
    const response = await ask(post({ question: 'x' }))
    expect(response.status).toBe(429)
    expect(response.headers.get('retry-after')).toBe('17')
    expect(await response.json()).toEqual({ code: 'COPILOT_RATE_LIMITED', retryAfter: 17 })
  })

  it.each([
    ['ausente', undefined],
    ['inválido', 'amanhã'],
    ['zero', '0'],
    ['negativo', '-3'],
    ['decimal', '2.5'],
  ])('429 de rate limit com Retry-After %s → 1 segundo', async (_name, value) => {
    POST_BACKEND.mockResolvedValue({
      error: { code: 'COPILOT_RATE_LIMITED', message: 'x', details: [] },
      response: new Response(null, { status: 429, headers: value === undefined ? {} : { 'Retry-After': value } }),
    })
    const response = await ask(post({ question: 'x' }))
    expect(response.status).toBe(429)
    expect(response.headers.get('retry-after')).toBe('1')
    expect(await response.json()).toEqual({ code: 'COPILOT_RATE_LIMITED', retryAfter: 1 })
  })

  it('429 de rate limit limita o Retry-After a 3600 segundos', async () => {
    POST_BACKEND.mockResolvedValue({
      error: { code: 'COPILOT_RATE_LIMITED', message: 'x', details: [] },
      response: new Response(null, { status: 429, headers: { 'Retry-After': '99999' } }),
    })
    const response = await ask(post({ question: 'x' }))
    expect(await response.json()).toEqual({ code: 'COPILOT_RATE_LIMITED', retryAfter: 3600 })
  })

  it('429 de quota repassa só o resetsAt', async () => {
    POST_BACKEND.mockResolvedValue({
      error: { code: 'COPILOT_QUOTA_EXCEEDED', message: 'x', details: [], resetsAt: '2026-12-01T03:00:00Z' },
      response: ok(429),
    })
    const response = await ask(post({ question: 'x' }))
    expect(response.status).toBe(429)
    expect(await response.json()).toEqual({ code: 'COPILOT_QUOTA_EXCEEDED', resetsAt: '2026-12-01T03:00:00.000Z' })
  })

  it('400 do backend vira VALIDATION_FAILED sem detalhes; código desconhecido → 502 sem vazar', async () => {
    POST_BACKEND.mockResolvedValueOnce({
      error: { code: 'VALIDATION_FAILED', message: 'a pergunta tem 900 caracteres', details: [{ field: 'question' }] },
      response: ok(400),
    })
    const invalid = await ask(post({ question: 'x' }))
    expect(invalid.status).toBe(400)
    expect(await invalid.json()).toEqual({ code: 'VALIDATION_FAILED' })

    POST_BACKEND.mockResolvedValueOnce({ error: { code: 'INTERNAL_ERROR', message: 'stack' }, response: ok(500) })
    const failed = await ask(post({ question: 'Quanto gastei com o Banco X?' }))
    expect(failed.status).toBe(502)
    expect(await failed.json()).toEqual({ code: 'UNAVAILABLE' })
    // Nada de pergunta ou texto do servidor no log.
    expect(JSON.stringify(vi.mocked(console.error).mock.calls)).not.toMatch(/Banco X|stack/)
  })
})

describe('GET /api/bff/copilot/usage e conversas', () => {
  it('usage lê o Workspace da sessão, sem cache', async () => {
    GET_BACKEND.mockResolvedValue({ data: { provisional: true }, response: ok() })
    const response = await usage()
    expect(response.headers.get('cache-control')).toContain('no-store')
    expect(await response.json()).toEqual({ provisional: true })
    expect(GET_BACKEND).toHaveBeenCalledWith('/api/v1/workspaces/{workspaceId}/copilot/usage', {
      params: { path: { workspaceId: 'ws-da-sessao' } },
    })
  })

  it('lista com cursor validado', async () => {
    GET_BACKEND.mockResolvedValue({ data: { items: [] }, response: ok() })
    const response = await listConversations(
      new NextRequest(`${ORIGIN}/api/bff/copilot/conversations?before=abc_DEF-1&limit=500`),
    )
    expect(response.status).toBe(200)
    expect(GET_BACKEND.mock.calls[0]![1].params.query).toEqual({ before: 'abc_DEF-1', limit: 50 })

    const bad = await listConversations(new NextRequest(`${ORIGIN}/api/bff/copilot/conversations?before=%3Cscript%3E`))
    expect(bad.status).toBe(400)
  })

  it('lista sem sessão responde 401 antes de validar a query', async () => {
    getFreshSession.mockResolvedValueOnce({ status: 'none' })
    const response = await listConversations(new NextRequest(`${ORIGIN}/api/bff/copilot/conversations?before=%3Cscript%3E`))
    expect(response.status).toBe(401)
    expect(GET_BACKEND).not.toHaveBeenCalled()
  })

  it('conversa de id inválido → 404 sem chamar o backend; 404 do backend repassa o código', async () => {
    const invalid = await getOne(new NextRequest(`${ORIGIN}/x`), { params: Promise.resolve({ conversationId: 'nao-uuid' }) })
    expect(invalid.status).toBe(404)
    expect(GET_BACKEND).not.toHaveBeenCalled()

    GET_BACKEND.mockResolvedValue({ error: { code: 'COPILOT_CONVERSATION_NOT_FOUND', message: 'x' }, response: ok(404) })
    const missing = await getOne(new NextRequest(`${ORIGIN}/x`), { params: Promise.resolve({ conversationId: ID }) })
    expect(missing.status).toBe(404)
    expect(await missing.json()).toEqual({ code: 'COPILOT_CONVERSATION_NOT_FOUND' })
  })
})

describe('DELETE e feedback', () => {
  it('apaga uma conversa e todas; sem Origin → 403 sem chamar o backend', async () => {
    DELETE_BACKEND.mockResolvedValue({ response: ok(204) })
    const one = await deleteOne(mutate(`/api/bff/copilot/conversations/${ID}`, 'DELETE'), {
      params: Promise.resolve({ conversationId: ID }),
    })
    expect(one.status).toBe(200)
    expect(await one.json()).toEqual({ deleted: true })
    expect(DELETE_BACKEND.mock.calls[0]![0]).toBe('/api/v1/workspaces/{workspaceId}/copilot/conversations/{conversationId}')

    expect((await deleteAll(mutate('/api/bff/copilot/conversations', 'DELETE'))).status).toBe(200)
    expect(DELETE_BACKEND.mock.calls[1]![0]).toBe('/api/v1/workspaces/{workspaceId}/copilot/conversations')

    DELETE_BACKEND.mockClear()
    expect((await deleteAll(mutate('/api/bff/copilot/conversations', 'DELETE', undefined, null))).status).toBe(403)
    expect(
      (
        await deleteOne(mutate(`/api/bff/copilot/conversations/${ID}`, 'DELETE', undefined, 'http://evil.test'), {
          params: Promise.resolve({ conversationId: ID }),
        })
      ).status,
    ).toBe(403)
    expect(DELETE_BACKEND).not.toHaveBeenCalled()
  })

  it('feedback remonta o corpo, descarta motivo em "útil" e valida o enum', async () => {
    PUT_BACKEND.mockResolvedValue({ response: ok(204) })
    const url = `/api/bff/copilot/questions/${ID}/feedback`
    const ctx = { params: Promise.resolve({ requestId: ID }) }

    const notHelpful = await feedback(mutate(url, 'PUT', { rating: 'NOT_HELPFUL', reason: 'CONFUSING', text: 'livre' }), ctx)
    expect(notHelpful.status).toBe(200)
    expect(PUT_BACKEND.mock.calls[0]![1].body).toEqual({ rating: 'NOT_HELPFUL', reason: 'CONFUSING' })
    expect(PUT_BACKEND.mock.calls[0]![1].params.path).toEqual({ workspaceId: 'ws-da-sessao', requestId: ID })

    await feedback(mutate(url, 'PUT', { rating: 'HELPFUL', reason: 'OTHER' }), ctx)
    expect(PUT_BACKEND.mock.calls[1]![1].body).toEqual({ rating: 'HELPFUL' })

    PUT_BACKEND.mockClear()
    expect((await feedback(mutate(url, 'PUT', { rating: 'MEH' }), ctx)).status).toBe(400)
    expect((await feedback(mutate(url, 'PUT', { rating: 'NOT_HELPFUL', reason: 'X' }), ctx)).status).toBe(400)
    expect((await feedback(mutate(url, 'PUT', { rating: 'HELPFUL' }, null), ctx)).status).toBe(403)
    expect(
      (await feedback(mutate(url, 'PUT', { rating: 'HELPFUL' }), { params: Promise.resolve({ requestId: 'nao-uuid' }) }))
        .status,
    ).toBe(404)
    expect(PUT_BACKEND).not.toHaveBeenCalled()
  })

  it('feedback de pergunta que não é da pessoa → 404 repassado', async () => {
    PUT_BACKEND.mockResolvedValue({ error: { code: 'COPILOT_REQUEST_NOT_FOUND', message: 'x' }, response: ok(404) })
    const response = await feedback(mutate(`/api/bff/copilot/questions/${ID}/feedback`, 'PUT', { rating: 'HELPFUL' }), {
      params: Promise.resolve({ requestId: ID }),
    })
    expect(response.status).toBe(404)
    expect(await response.json()).toEqual({ code: 'COPILOT_REQUEST_NOT_FOUND' })
  })
})
