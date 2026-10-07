// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest'

const readSession = vi.fn()
const redirect = vi.fn((url: string) => {
  throw new Error(`NEXT_REDIRECT:${url}`)
})

vi.mock('@/server/auth/session', () => ({ readSession: () => readSession() }))
vi.mock('next/navigation', () => ({ redirect: (url: string) => redirect(url), usePathname: () => '/home' }))

import AuthenticatedLayout from './layout'

describe('área autenticada', () => {
  beforeEach(() => {
    readSession.mockReset()
    redirect.mockClear()
  })

  it('sem sessão: redireciona para o login do BFF antes de renderizar qualquer dado', async () => {
    readSession.mockResolvedValue(null)
    await expect(AuthenticatedLayout({ children: 'conteúdo' })).rejects.toThrow('NEXT_REDIRECT:/auth/login')
    expect(redirect).toHaveBeenCalledWith('/auth/login')
  })

  it('com sessão: renderiza o shell com o nome de exibição (nada de token)', async () => {
    readSession.mockResolvedValue({ displayName: 'Ana Souza', accessToken: 'segredo', workspaceId: 'ws' })
    const element = (await AuthenticatedLayout({ children: 'conteúdo' })) as { props: Record<string, unknown> }
    expect(redirect).not.toHaveBeenCalled()
    expect(element.props).toEqual({ displayName: 'Ana Souza', children: 'conteúdo' })
  })
})
