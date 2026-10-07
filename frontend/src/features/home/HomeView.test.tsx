import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readyOverview } from './fixtures'
import { HomeView } from './HomeView'

const replace = vi.fn()
let search = ''

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
  usePathname: () => '/home',
  useSearchParams: () => new URLSearchParams(search),
}))

const json = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))

describe('HomeView', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    search = ''
    replace.mockReset()
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('mostra skeleton (sem spinner) enquanto carrega e depois o dashboard, com uma única chamada', async () => {
    fetchMock.mockReturnValue(json(readyOverview()))
    render(<HomeView firstName="Ana" />)

    expect(screen.getByRole('status')).toHaveTextContent('Carregando seu resumo financeiro')
    expect(screen.getByRole('heading', { level: 1, name: 'Olá, Ana' })).toBeInTheDocument()

    expect(await screen.findByText('R$ 7.627,52')).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.getByText('Resumo de 1 a 27 de setembro')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/bff/overview')
  })

  it('envia o período da URL ao BFF', async () => {
    search = 'period=CUSTOM&from=2026-08-01&to=2026-08-15'
    fetchMock.mockReturnValue(json(readyOverview()))
    render(<HomeView firstName="Ana" />)

    await screen.findByText('R$ 7.627,52')
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/bff/overview?period=CUSTOM&from=2026-08-01&to=2026-08-15')
  })

  it('trocar o período atualiza a URL', async () => {
    fetchMock.mockReturnValue(json(readyOverview()))
    render(<HomeView firstName="Ana" />)
    await screen.findByText('R$ 7.627,52')

    await userEvent.click(screen.getByRole('button', { name: 'Mês anterior' }))
    expect(replace).toHaveBeenCalledWith('/home?period=PREVIOUS_MONTH', { scroll: false })
  })

  it('erro de rede: mensagem amigável e "Tentar novamente" refaz a chamada', async () => {
    fetchMock.mockRejectedValueOnce(new Error('connect ECONNREFUSED 10.0.0.1:8080')).mockReturnValueOnce(json(readyOverview()))
    render(<HomeView firstName="Ana" />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Não foi possível carregar seu resumo financeiro')
    expect(alert).not.toHaveTextContent(/ECONNREFUSED|10\.0\.0\.1|Exception/)

    await userEvent.click(screen.getByRole('button', { name: 'Tentar novamente' }))
    expect(await screen.findByText('R$ 7.627,52')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('erro 502 do BFF não vaza detalhes e oferece nova tentativa', async () => {
    fetchMock.mockReturnValue(json({ code: 'UNAVAILABLE' }, 502))
    render(<HomeView firstName="Ana" />)
    expect(await screen.findByRole('button', { name: 'Tentar novamente' })).toBeInTheDocument()
  })

  it('período inválido (400) oferece voltar ao mês atual', async () => {
    search = 'period=CUSTOM&from=2020-01-01&to=2026-01-01'
    fetchMock.mockReturnValue(json({ code: 'INVALID_PERIOD' }, 400))
    render(<HomeView firstName="Ana" />)

    await userEvent.click(await screen.findByRole('button', { name: 'Voltar para este mês' }))
    expect(replace).toHaveBeenCalledWith('/home', { scroll: false })
  })

  it('sessão expirada (401) redireciona ao login do BFF', async () => {
    const assign = vi.fn()
    vi.stubGlobal('location', { ...window.location, assign })
    fetchMock.mockReturnValue(json({ code: 'UNAUTHENTICATED' }, 401))
    render(<HomeView firstName="Ana" />)

    await waitFor(() => expect(assign).toHaveBeenCalledWith('/auth/login'))
  })
})
