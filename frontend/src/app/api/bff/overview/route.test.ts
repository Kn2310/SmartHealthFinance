// @vitest-environment node
import { NextRequest } from 'next/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const getFreshSession = vi.fn()
const GET_OVERVIEW = vi.fn()

vi.mock('@/server/auth/session', () => ({ getFreshSession: () => getFreshSession() }))
vi.mock('@/server/backend', () => ({ backendClient: () => ({ GET: GET_OVERVIEW }) }))

import { GET } from './route'

const request = (qs = '') => new NextRequest(`http://localhost:3000/api/bff/overview${qs}`)
const session = { accessToken: 'tok', workspaceId: 'ws-1', displayName: 'Ana', refreshToken: 'r', expiresAt: 0 }
const ok = { status: 'ok', session }

describe('GET /api/bff/overview (mapeamento de respostas)', () => {
  beforeEach(() => {
    getFreshSession.mockReset()
    GET_OVERVIEW.mockReset()
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it('sem sessão: 401 e nenhuma chamada ao backend', async () => {
    getFreshSession.mockResolvedValue({ status: 'none' })
    const response = await GET(request())
    expect(response.status).toBe(401)
    expect(GET_OVERVIEW).not.toHaveBeenCalled()
  })

  it('IdP indisponível ao renovar: 502 temporário (a sessão não é descartada)', async () => {
    getFreshSession.mockResolvedValue({ status: 'unavailable' })
    const response = await GET(request())
    expect(response.status).toBe(502)
    expect(await response.json()).toEqual({ code: 'UNAVAILABLE' })
  })

  it('período desconhecido cai em CURRENT_MONTH', async () => {
    getFreshSession.mockResolvedValue(ok)
    GET_OVERVIEW.mockResolvedValue({ data: {}, response: { status: 200 } })
    await GET(request('?period=DROP_TABLE'))
    expect(GET_OVERVIEW.mock.calls[0]?.[1].params.query.period).toBe('CURRENT_MONTH')
  })

  it('400 do backend vira INVALID_PERIOD sem repassar a mensagem', async () => {
    getFreshSession.mockResolvedValue(ok)
    GET_OVERVIEW.mockResolvedValue({ error: { message: 'segredo interno' }, response: { status: 400 } })
    const response = await GET(request('?period=CUSTOM&from=2020-01-01&to=2026-01-01'))
    expect(response.status).toBe(400)
    expect(await response.json()).toEqual({ code: 'INVALID_PERIOD' })
  })

  it('falhas do backend viram 502 genérico (sem stack, SQL ou IDs)', async () => {
    getFreshSession.mockResolvedValue(ok)
    GET_OVERVIEW.mockResolvedValue({ error: { message: 'PSQLException ... workspace ws-1' }, response: { status: 500 } })
    const response = await GET(request())
    expect(response.status).toBe(502)
    expect(await response.json()).toEqual({ code: 'UNAVAILABLE' })

    GET_OVERVIEW.mockRejectedValue(new Error('ECONNREFUSED'))
    const unreachable = await GET(request())
    expect(unreachable.status).toBe(502)
    expect(JSON.stringify(await unreachable.json())).not.toContain('ECONNREFUSED')
  })

  it('exceção inesperada na sessão também vira 502 controlado', async () => {
    getFreshSession.mockRejectedValue(new Error('boom'))
    const response = await GET(request())
    expect(response.status).toBe(502)
  })

  it.each([
    ['200', ok, { data: { state: 'READY' }, response: { status: 200 } }, 200],
    ['400', ok, { error: {}, response: { status: 400 } }, 400],
    ['401 (backend)', ok, { error: {}, response: { status: 401 } }, 401],
    ['401 (sem sessão)', { status: 'none' }, undefined, 401],
    ['502', ok, { error: {}, response: { status: 503 } }, 502],
  ])('resposta %s é Cache-Control: no-store', async (_label, fresh, upstream, status) => {
    getFreshSession.mockResolvedValue(fresh)
    if (upstream) GET_OVERVIEW.mockResolvedValue(upstream)
    const response = await GET(request())
    expect(response.status).toBe(status)
    expect(response.headers.get('cache-control')).toBe('no-store')
  })
})
