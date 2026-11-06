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
import { GET as recent } from './recent-contributions/route'
import { POST as preview } from './preview/route'
import { GET as get, PUT as update } from './[goalId]/route'
import { PUT as plan } from './[goalId]/plan/route'
import { POST as action } from './[goalId]/[action]/route'
import { GET as history, POST as contribute } from './[goalId]/contributions/route'
import { POST as simulate } from './[goalId]/simulations/route'
import { GET as projection } from './[goalId]/projection/route'

const ORIGIN = 'http://app.test'
const GOAL = '01922f5e-0000-7000-8000-00000000a001'
const ACCOUNT = '01922f5e-0000-7000-8000-0000000000a1'
const OTHER = '01922f5e-0000-7000-8000-0000000000a2'
const session = { accessToken: 'tok', workspaceId: 'ws-da-sessao', displayName: 'Ana', refreshToken: 'r', expiresAt: 0 }
const params = (goalId = GOAL) => ({ params: Promise.resolve({ goalId }) })
const brl = (amount: string) => ({ amount, currency: 'BRL' })

function request(path: string, method: 'GET' | 'POST' | 'PUT' = 'GET', body?: unknown, headers: Record<string, string> = {}, origin: string | null = ORIGIN) {
  const h = new Headers({ 'content-type': 'application/json', ...headers })
  if (origin && method !== 'GET') h.set('origin', origin)
  return new NextRequest(`${ORIGIN}${path}`, {
    method,
    headers: h,
    body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
  })
}

const validGoal = {
  name: '  Viagem para Lisboa ',
  type: 'PURCHASE',
  targetAmount: brl('12000.00'),
  plannedMonthly: brl('700.00'),
  deadline: '2027-07',
  sourceAccountId: ACCOUNT,
  destinationAccountId: OTHER,
  origin: { kind: 'MANUAL' },
}

beforeEach(() => {
  stubServerEnv()
  getFreshSession.mockReset().mockResolvedValue({ status: 'ok', session })
  for (const fn of [GET_BACKEND, POST_BACKEND, PUT_BACKEND]) fn.mockReset()
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

describe('GET /api/bff/goals', () => {
  it('lista do Workspace da sessão; só status conhecido vira filtro; no-store', async () => {
    GET_BACKEND.mockResolvedValue({ data: { summary: {}, goals: [] }, response: { status: 200 } })
    const response = await list(request('/api/bff/goals?status=COMPLETED&workspaceId=outro'))
    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(GET_BACKEND.mock.calls[0]?.[1].params).toEqual({ path: { workspaceId: 'ws-da-sessao' }, query: { status: 'COMPLETED' } })
    await list(request('/api/bff/goals?status=DROP'))
    expect(GET_BACKEND.mock.calls[1]?.[1].params.query).toEqual({})
  })

  it('sem sessão 401; IdP fora ou exceção 502', async () => {
    getFreshSession.mockResolvedValueOnce({ status: 'none' })
    expect((await list(request('/api/bff/goals'))).status).toBe(401)
    getFreshSession.mockResolvedValueOnce({ status: 'unavailable' })
    expect((await list(request('/api/bff/goals'))).status).toBe(502)
    GET_BACKEND.mockRejectedValueOnce(new Error('down'))
    const failed = await list(request('/api/bff/goals'))
    expect(failed.status).toBe(502)
    expect(await failed.json()).toEqual({ code: 'UNAVAILABLE' })
  })

  it('aportes recentes: limit só de 1 a 20', async () => {
    GET_BACKEND.mockResolvedValue({ data: { contributions: [] }, response: { status: 200 } })
    await recent(request('/api/bff/goals/recent-contributions?limit=5'))
    expect(GET_BACKEND.mock.calls[0]?.[1].params.query).toEqual({ limit: 5 })
    await recent(request('/api/bff/goals/recent-contributions?limit=500'))
    expect(GET_BACKEND.mock.calls[1]?.[1].params.query).toEqual({})
  })
})

describe('POST /api/bff/goals', () => {
  it('remonta o corpo só com os campos do contrato (nome aparado, moeda BRL fixa)', async () => {
    POST_BACKEND.mockResolvedValue({ data: { id: GOAL }, response: { status: 201 } })
    const response = await create(request('/api/bff/goals', 'POST', { ...validGoal, workspaceId: 'outro', extra: 1, targetAmount: { amount: '12000.00', currency: 'USD' } }))
    expect(response.status).toBe(201)
    const call = POST_BACKEND.mock.calls[0]!
    expect(call[0]).toBe('/api/v1/workspaces/{workspaceId}/goals')
    expect(call[1].params).toEqual({ path: { workspaceId: 'ws-da-sessao' } })
    expect(call[1].body).toEqual({
      name: 'Viagem para Lisboa',
      type: 'PURCHASE',
      targetAmount: brl('12000.00'),
      plannedMonthly: brl('700.00'),
      deadline: '2027-07',
      sourceAccountId: ACCOUNT,
      destinationAccountId: OTHER,
      origin: { kind: 'MANUAL' },
    })
  })

  it('sem Origin ou de outra origem: 403 sem chamar o backend', async () => {
    expect((await create(request('/api/bff/goals', 'POST', validGoal, {}, null))).status).toBe(403)
    expect((await create(request('/api/bff/goals', 'POST', validGoal, {}, 'http://evil.test'))).status).toBe(403)
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('formato inválido: 400 por campo, sem chamar o backend', async () => {
    const response = await create(request('/api/bff/goals', 'POST', {
      name: 'x'.repeat(61), type: 'CAR', targetAmount: brl('10.123'), plannedMonthly: 700, deadline: '2027-13',
      destinationAccountId: 'nope', origin: { kind: 'INSIGHT' },
    }))
    expect(response.status).toBe(400)
    const { details } = await response.json() as { details: { field: string; code: string }[] }
    expect(details).toEqual(expect.arrayContaining([
      { field: 'name', code: 'TOO_LONG' },
      { field: 'type', code: 'INVALID' },
      { field: 'targetAmount', code: 'INVALID' },
      { field: 'plannedMonthly', code: 'INVALID' },
      { field: 'deadline', code: 'INVALID' },
      { field: 'destinationAccountId', code: 'INVALID' },
      { field: 'origin.insightId', code: 'REQUIRED' },
    ]))
    expect((await create(request('/api/bff/goals', 'POST', 'não é json'))).status).toBe(400)
    expect((await create(request('/api/bff/goals', 'POST', { name: 'a'.repeat(5000) }))).status).toBe(400)
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('falhas do backend: códigos conhecidos passam; campos desconhecidos e outros status não vazam', async () => {
    POST_BACKEND.mockResolvedValueOnce({ error: { code: 'GOAL_RESERVE_ALREADY_EXISTS', message: 'segredo' }, response: { status: 409 } })
    const conflict = await create(request('/api/bff/goals', 'POST', validGoal))
    expect(conflict.status).toBe(409)
    expect(await conflict.json()).toEqual({ code: 'GOAL_RESERVE_ALREADY_EXISTS' })
    POST_BACKEND.mockResolvedValueOnce({
      error: { code: 'VALIDATION_FAILED', details: [{ field: 'deadline', code: 'PAST' }, { field: 'targetAmount.currency', code: 'UNSUPPORTED' }, { field: 'secret', code: 'X' }] },
      response: { status: 400 },
    })
    const invalid = await create(request('/api/bff/goals', 'POST', validGoal))
    expect(await invalid.json()).toEqual({
      code: 'VALIDATION_FAILED',
      details: [{ field: 'deadline', code: 'PAST' }, { field: 'targetAmount', code: 'UNSUPPORTED' }],
    })
    POST_BACKEND.mockResolvedValueOnce({ error: { code: 'BOOM', message: 'stack' }, response: { status: 500 } })
    const failed = await create(request('/api/bff/goals', 'POST', validGoal))
    expect(failed.status).toBe(502)
    expect(await failed.json()).toEqual({ code: 'UNAVAILABLE' })
    expect(console.error).toHaveBeenCalledWith('goal create upstream failure', { status: 500 })
  })
})

describe('/api/bff/goals/[goalId]', () => {
  it('id que não é UUID: o mesmo 404 do inexistente, sem chamar o backend', async () => {
    for (const response of [
      await get(request('/api/bff/goals/x'), params('x')),
      await update(request('/api/bff/goals/x', 'PUT', validGoal), params('x')),
      await plan(request('/api/bff/goals/x/plan', 'PUT', { plannedMonthly: brl('1.00') }), params('x')),
      await projection(request('/api/bff/goals/x/projection'), params('x')),
      await history(request('/api/bff/goals/x/contributions'), params('x')),
    ]) {
      expect(response.status).toBe(404)
      expect(await response.json()).toEqual({ code: 'GOAL_NOT_FOUND' })
    }
    expect(GET_BACKEND).not.toHaveBeenCalled()
    expect(PUT_BACKEND).not.toHaveBeenCalled()
  })

  it('detalhe e 404 de outro Workspace repassado', async () => {
    GET_BACKEND.mockResolvedValueOnce({ data: { id: GOAL }, response: { status: 200 } })
    expect((await get(request(`/api/bff/goals/${GOAL}`), params())).status).toBe(200)
    expect(GET_BACKEND.mock.calls[0]?.[1].params).toEqual({ path: { workspaceId: 'ws-da-sessao', goalId: GOAL } })
    GET_BACKEND.mockResolvedValueOnce({ error: { code: 'GOAL_NOT_FOUND' }, response: { status: 404 } })
    const missing = await get(request(`/api/bff/goals/${GOAL}`), params())
    expect(missing.status).toBe(404)
  })

  it('edição: tipo e origem não vão ao backend', async () => {
    PUT_BACKEND.mockResolvedValue({ data: { id: GOAL }, response: { status: 200 } })
    await update(request(`/api/bff/goals/${GOAL}`, 'PUT', validGoal), params())
    expect(PUT_BACKEND.mock.calls[0]?.[1].body).toEqual({
      name: 'Viagem para Lisboa', targetAmount: brl('12000.00'), plannedMonthly: brl('700.00'), deadline: '2027-07',
      sourceAccountId: ACCOUNT, destinationAccountId: OTHER,
    })
  })

  it('"Aplicar ao objetivo": só plannedMonthly e deadline; exige a mesma origem', async () => {
    PUT_BACKEND.mockResolvedValue({ data: { id: GOAL }, response: { status: 200 } })
    expect((await plan(request(`/api/bff/goals/${GOAL}/plan`, 'PUT', { plannedMonthly: brl('770.00') }, {}, null), params())).status).toBe(403)
    await plan(request(`/api/bff/goals/${GOAL}/plan`, 'PUT', { plannedMonthly: brl('770.00'), deadline: '2027-07', name: 'x' }), params())
    expect(PUT_BACKEND.mock.calls[0]?.[0]).toBe('/api/v1/workspaces/{workspaceId}/goals/{goalId}/plan')
    expect(PUT_BACKEND.mock.calls[0]?.[1].body).toEqual({ plannedMonthly: brl('770.00'), deadline: '2027-07' })
  })

  it('ações de status: só as quatro conhecidas', async () => {
    POST_BACKEND.mockResolvedValue({ data: { id: GOAL }, response: { status: 200 } })
    const ctx = (a: string, goalId = GOAL) => ({ params: Promise.resolve({ goalId, action: a }) })
    expect((await action(request(`/api/bff/goals/${GOAL}/pause`, 'POST'), ctx('pause'))).status).toBe(200)
    expect(POST_BACKEND.mock.calls[0]?.[0]).toBe('/api/v1/workspaces/{workspaceId}/goals/{goalId}/pause')
    expect((await action(request(`/api/bff/goals/${GOAL}/delete`, 'POST'), ctx('delete'))).status).toBe(404)
    expect((await action(request('/api/bff/goals/x/archive', 'POST'), ctx('archive', 'x'))).status).toBe(404)
    expect((await action(request(`/api/bff/goals/${GOAL}/archive`, 'POST', undefined, {}, null), ctx('archive'))).status).toBe(403)
    POST_BACKEND.mockResolvedValueOnce({ error: { code: 'GOAL_STATUS_CONFLICT' }, response: { status: 409 } })
    expect((await action(request(`/api/bff/goals/${GOAL}/resume`, 'POST'), ctx('resume'))).status).toBe(409)
    expect(POST_BACKEND).toHaveBeenCalledTimes(2)
  })
})

describe('aporte, simulação, prévia e projeção', () => {
  const contribution = { sourceAccountId: ACCOUNT, amount: brl('700.00'), occurredOn: '2026-09-21', note: 'x' }

  it('aporte exige Idempotency-Key e a repassa; 201 e 200 (replay) preservados', async () => {
    expect((await contribute(request(`/api/bff/goals/${GOAL}/contributions`, 'POST', contribution), params())).status).toBe(400)
    expect(POST_BACKEND).not.toHaveBeenCalled()
    POST_BACKEND.mockResolvedValueOnce({ data: { id: 't1' }, response: { status: 201 } })
    const created = await contribute(request(`/api/bff/goals/${GOAL}/contributions`, 'POST', contribution, { 'Idempotency-Key': 'web:k1' }), params())
    expect(created.status).toBe(201)
    const call = POST_BACKEND.mock.calls[0]!
    expect(call[1].params).toEqual({ path: { workspaceId: 'ws-da-sessao', goalId: GOAL }, header: { 'Idempotency-Key': 'web:k1' } })
    expect(call[1].body).toEqual({ sourceAccountId: ACCOUNT, destinationAccountId: null, amount: brl('700.00'), occurredOn: '2026-09-21', description: null })
    POST_BACKEND.mockResolvedValueOnce({ data: { id: 't1' }, response: { status: 200 } })
    expect((await contribute(request(`/api/bff/goals/${GOAL}/contributions`, 'POST', contribution, { 'Idempotency-Key': 'web:k1' }), params())).status).toBe(200)
    POST_BACKEND.mockResolvedValueOnce({ error: { code: 'IDEMPOTENCY_KEY_REUSED' }, response: { status: 422 } })
    expect((await contribute(request(`/api/bff/goals/${GOAL}/contributions`, 'POST', contribution, { 'Idempotency-Key': 'web:k1' }), params())).status).toBe(422)
  })

  it('aporte com data ou conta mal formadas: 400 sem chamar o backend', async () => {
    const response = await contribute(
      request(`/api/bff/goals/${GOAL}/contributions`, 'POST', { sourceAccountId: 'x', amount: brl('-1'), occurredOn: '21/09/2026' }, { 'Idempotency-Key': 'web:k2' }),
      params(),
    )
    expect(response.status).toBe(400)
    expect((await response.json()).details).toEqual([
      { field: 'sourceAccountId', code: 'INVALID' },
      { field: 'amount', code: 'INVALID' },
      { field: 'occurredOn', code: 'INVALID' },
    ])
    expect(POST_BACKEND).not.toHaveBeenCalled()
  })

  it('simulação: 1 a 5 cenários, só campos do contrato', async () => {
    POST_BACKEND.mockResolvedValue({ data: { basis: 'SIMULATED' }, response: { status: 200 } })
    await simulate(request(`/api/bff/goals/${GOAL}/simulations`, 'POST', {
      scenarios: [{}, { deadline: '2027-07' }, { monthlyAmount: brl('900.00'), reduction: { categoryCode: 'DINING_OUT', amount: brl('100.00') }, x: 1 }],
    }), params())
    expect(POST_BACKEND.mock.calls[0]?.[1].body).toEqual({
      scenarios: [{}, { deadline: '2027-07' }, { monthlyAmount: brl('900.00'), reduction: { categoryCode: 'DINING_OUT', amount: brl('100.00') } }],
    })
    expect((await simulate(request(`/api/bff/goals/${GOAL}/simulations`, 'POST', { scenarios: Array(6).fill({}) }), params())).status).toBe(400)
    expect((await simulate(request(`/api/bff/goals/${GOAL}/simulations`, 'POST', { scenarios: [] }), params())).status).toBe(400)
    expect((await simulate(request(`/api/bff/goals/${GOAL}/simulations`, 'POST', { scenarios: [{}] }, {}, null), params())).status).toBe(403)
    expect(POST_BACKEND).toHaveBeenCalledTimes(1)
  })

  it('prévia: Workspace da sessão e corpo remontado', async () => {
    POST_BACKEND.mockResolvedValue({ data: { alreadySaved: brl('0.00') }, response: { status: 200 } })
    await preview(request('/api/bff/goals/preview', 'POST', { type: 'EMERGENCY_RESERVE', plannedMonthly: brl('500.00'), workspaceId: 'x' }))
    expect(POST_BACKEND.mock.calls[0]?.[0]).toBe('/api/v1/workspaces/{workspaceId}/goals/simulations/preview')
    expect(POST_BACKEND.mock.calls[0]?.[1]).toEqual({
      params: { path: { workspaceId: 'ws-da-sessao' } },
      body: { type: 'EMERGENCY_RESERVE', targetAmount: null, plannedMonthly: brl('500.00'), deadline: null, destinationAccountId: null },
    })
  })

  it('projeção e histórico: leitura do Workspace da sessão; página validada', async () => {
    GET_BACKEND.mockResolvedValue({ data: {}, response: { status: 200 } })
    expect((await projection(request(`/api/bff/goals/${GOAL}/projection`), params())).status).toBe(200)
    await history(request(`/api/bff/goals/${GOAL}/contributions?page=1&pageSize=500`), params())
    expect(GET_BACKEND.mock.calls[1]?.[1].params).toEqual({ path: { workspaceId: 'ws-da-sessao', goalId: GOAL }, query: { page: 1 } })
  })
})
