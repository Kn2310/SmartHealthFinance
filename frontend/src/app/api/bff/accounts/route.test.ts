// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest'

const getFreshSession = vi.fn()
const GET_BACKEND = vi.fn()

vi.mock('@/server/auth/session', () => ({ getFreshSession: () => getFreshSession() }))
vi.mock('@/server/backend', () => ({ backendClient: () => ({ GET: GET_BACKEND }) }))

import { GET } from './route'

const session = { accessToken: 'tok', workspaceId: 'ws-1', displayName: 'Ana', refreshToken: 'r', expiresAt: 0 }

describe('GET /api/bff/accounts', () => {
  beforeEach(() => {
    getFreshSession.mockReset().mockResolvedValue({ status: 'ok', session })
    GET_BACKEND.mockReset()
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it('lista só as contas ativas do Workspace da sessão', async () => {
    GET_BACKEND.mockResolvedValue({ data: { items: [] }, response: { status: 200 } })
    const response = await GET()
    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(GET_BACKEND.mock.calls[0]?.[1].params).toEqual({ path: { workspaceId: 'ws-1' }, query: { includeArchived: false } })
  })

  it('falha do backend: 502 genérico', async () => {
    GET_BACKEND.mockResolvedValue({ error: { message: 'interno' }, response: { status: 500 } })
    const response = await GET()
    expect(response.status).toBe(502)
    expect(await response.json()).toEqual({ code: 'UNAVAILABLE' })
  })

  it('sem sessão: 401', async () => {
    getFreshSession.mockResolvedValue({ status: 'none' })
    expect((await GET()).status).toBe(401)
    expect(GET_BACKEND).not.toHaveBeenCalled()
  })
})
