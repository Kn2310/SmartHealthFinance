import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MoreView } from '@/features/transactions/MoreView'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import type { Consent } from './api'
import { ConsentsView } from './ConsentsView'
import { formatConsentDate } from './messages'
import { ProfileFrame } from './ProfileFrame'

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/profile/consents',
  useSearchParams: () => new URLSearchParams(),
}))

const data = (overrides: Partial<Consent> = {}): Consent => ({
  purpose: 'COPILOT_DATA_USE',
  state: 'PENDING',
  termsVersion: null,
  currentTermsVersion: 'copilot-data-v1',
  grantedAt: null,
  revokedAt: null,
  requires: [],
  ...overrides,
})
const history = (overrides: Partial<Consent> = {}): Consent =>
  data({ purpose: 'COPILOT_HISTORY', currentTermsVersion: 'copilot-history-v1', requires: ['COPILOT_DATA_USE'], ...overrides })
const active = (consent: Consent): Consent => ({
  ...consent,
  state: 'ACTIVE',
  termsVersion: consent.currentTermsVersion,
  grantedAt: '2026-04-02T15:00:00Z',
})
const revoked = (consent: Consent): Consent => ({
  ...consent,
  state: 'REVOKED',
  termsVersion: consent.currentTermsVersion,
  grantedAt: '2026-04-02T15:00:00Z',
  revokedAt: '2026-11-09T15:00:00Z',
})

const body = (init: RequestInit | undefined) => JSON.parse(String(init?.body)) as unknown

afterEach(() => vi.unstubAllGlobals())

describe('/profile/consents — D-ProfileConsents', () => {
  it('mostra os dois consentimentos do Copilot, pendentes, com o histórico bloqueado até o uso de dados', async () => {
    stubBff({ 'GET /api/bff/consents': () => json({ items: [data(), history()] }) })
    const { container } = render(
      <ProfileFrame>
        <ConsentsView />
      </ProfileFrame>,
    )

    const dataSwitch = await screen.findByRole('switch', { name: 'Permitir que o Copilot use meus dados' })
    expect(dataSwitch).toHaveAttribute('aria-checked', 'false')
    expect(dataSwitch).toBeEnabled()
    const historySwitch = screen.getByRole('switch', { name: 'Guardar histórico de conversas do Copilot' })
    expect(historySwitch).toBeDisabled()
    expect(screen.getByText('Não concedido · exige permitir que o Copilot use seus dados')).toBeInTheDocument()
    expect(screen.queryByText(/Gerar insights/)).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Perfil' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Consentimentos' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByText('Você pode revogar a qualquer momento')).toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('concede o uso de dados com a versão vigente do termo e mostra a data', async () => {
    const calls = stubBff({
      'GET /api/bff/consents': () => json({ items: [data(), history()] }),
      'PUT /api/bff/consents/COPILOT_DATA_USE': () => json({ items: [active(data()), history()] }),
    })
    const user = userEvent.setup()
    render(<ConsentsView />)

    await user.click(await screen.findByRole('switch', { name: 'Permitir que o Copilot use meus dados' }))

    await waitFor(() =>
      expect(screen.getByRole('switch', { name: 'Permitir que o Copilot use meus dados' })).toHaveAttribute(
        'aria-checked',
        'true',
      ),
    )
    expect(body(calls.find((c) => c.method === 'PUT')?.init)).toEqual({ granted: true, termsVersion: 'copilot-data-v1' })
    expect(screen.getByText('Concedido em 02 abr 2026 · o Copilot só lê dados dentro do app')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Guardar histórico de conversas do Copilot' })).toBeEnabled()
  })

  it('revogar o uso de dados com histórico ativo pede confirmação e revoga os dois', async () => {
    const calls = stubBff({
      'GET /api/bff/consents': () => json({ items: [active(data()), active(history())] }),
      'PUT /api/bff/consents/COPILOT_DATA_USE': () => json({ items: [revoked(data()), revoked(history())] }),
    })
    const user = userEvent.setup()
    render(<ConsentsView />)

    await user.click(await screen.findByRole('switch', { name: 'Permitir que o Copilot use meus dados' }))
    expect(calls.filter((c) => c.method === 'PUT')).toHaveLength(0)
    expect(screen.getByText('Revogar e apagar o histórico?')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Revogar' }))

    await waitFor(() =>
      expect(screen.getAllByRole('switch').map((s) => s.getAttribute('aria-checked'))).toEqual(['false', 'false']),
    )
    expect(body(calls.find((c) => c.method === 'PUT')?.init)).toEqual({ granted: false })
    expect(screen.getByText('Revogado em 09 nov 2026 · o Copilot só lê dados dentro do app')).toBeInTheDocument()
  })

  it('revogar o histórico pede confirmação; cancelar mantém tudo', async () => {
    const calls = stubBff({
      'GET /api/bff/consents': () => json({ items: [active(data()), active(history())] }),
      'PUT /api/bff/consents/COPILOT_HISTORY': () => json({ items: [active(data()), revoked(history())] }),
    })
    const user = userEvent.setup()
    render(<ConsentsView />)

    const historySwitch = await screen.findByRole('switch', { name: 'Guardar histórico de conversas do Copilot' })
    await user.click(historySwitch)
    expect(screen.getByRole('alertdialog', { name: /Apagar o histórico do Copilot\?/ })).toHaveFocus()
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(calls.filter((c) => c.method === 'PUT')).toHaveLength(0)
    expect(screen.queryByText('Apagar o histórico do Copilot?')).not.toBeInTheDocument()
    await waitFor(() => expect(historySwitch).toHaveFocus())
  })

  it('histórico expirado também é apagado pela cascata: revogar o uso de dados pede confirmação', async () => {
    const calls = stubBff({
      'GET /api/bff/consents': () =>
        json({ items: [active(data()), { ...active(history()), state: 'EXPIRED', termsVersion: 'copilot-history-v0' }] }),
    })
    const user = userEvent.setup()
    render(<ConsentsView />)

    await user.click(await screen.findByRole('switch', { name: 'Permitir que o Copilot use meus dados' }))

    expect(screen.getByText('Revogar e apagar o histórico?')).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'PUT')).toHaveLength(0)
  })

  it('erro ao salvar mostra texto próprio e preserva o estado', async () => {
    stubBff({
      'GET /api/bff/consents': () => json({ items: [data(), history()] }),
      'PUT /api/bff/consents/COPILOT_DATA_USE': () => json({ code: 'CONSENT_TERMS_OUTDATED' }, 409),
    })
    const user = userEvent.setup()
    render(<ConsentsView />)

    await user.click(await screen.findByRole('switch', { name: 'Permitir que o Copilot use meus dados' }))

    expect(await screen.findByText(/Os termos foram atualizados/)).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Permitir que o Copilot use meus dados' })).toHaveAttribute(
      'aria-checked',
      'false',
    )
  })

  it('falha ao carregar oferece tentar de novo', async () => {
    let attempts = 0
    stubBff({
      'GET /api/bff/consents': () =>
        ++attempts === 1 ? json({ code: 'UNAVAILABLE' }, 502) : json({ items: [data(), history()] }),
    })
    const user = userEvent.setup()
    const { container } = render(<ConsentsView />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Não conseguimos carregar seus consentimentos agora')
    expect(await axeViolations(container)).toEqual([])
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }))

    expect(await screen.findByRole('switch', { name: 'Permitir que o Copilot use meus dados' })).toBeInTheDocument()
  })

  it('data no fuso de negócio: 02:00 UTC ainda é o dia anterior em Brasília', () => {
    expect(formatConsentDate('2026-04-03T02:00:00Z')).toBe('02 abr 2026')
    expect(formatConsentDate('inválida')).toBe('')
  })
})

describe('M-More — seção Perfil', () => {
  it('Consentimentos leva a /profile/consents; os demais itens seguem "Em breve" sem link', () => {
    render(<MoreView />)

    expect(screen.getByRole('link', { name: 'Consentimentos' })).toHaveAttribute('href', '/profile/consents')
    expect(screen.queryByRole('link', { name: /Segurança/ })).not.toBeInTheDocument()
    expect(screen.getByText('Segurança')).toBeInTheDocument()
  })
})
