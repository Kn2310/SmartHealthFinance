import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import { NewCardView } from './NewCardView'

const push = vi.fn()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  usePathname: () => '/cards/new',
  useSearchParams: () => new URLSearchParams(),
}))

const AURORA = '01922f5e-0000-7000-8000-0000000000a1'
const accounts = {
  items: [
    {
      id: AURORA,
      workspaceId: 'ws',
      name: 'Conta Aurora',
      type: 'CHECKING',
      institutionName: 'Banco Aurora',
      currency: 'BRL',
      includedInTotal: true,
      status: 'ACTIVE',
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-01T00:00:00Z',
    },
  ],
}
const body = (init: RequestInit | undefined) => JSON.parse(String(init?.body)) as Record<string, unknown>

beforeEach(() => push.mockReset())
afterEach(() => vi.unstubAllGlobals())

async function fill(user: ReturnType<typeof userEvent.setup>, { last4 = '4821' } = {}) {
  await user.type(await screen.findByLabelText('Apelido'), 'Aurora Platinum')
  await user.selectOptions(screen.getByLabelText('Bandeira'), 'MASTERCARD')
  await user.type(screen.getByLabelText('4 últimos dígitos'), last4)
  await user.type(screen.getByLabelText('Limite'), '12.000,00')
  await user.type(screen.getByLabelText('Dia de fechamento'), '28')
  await user.type(screen.getByLabelText('Dia de vencimento'), '5')
}

describe('/cards/new — cadastro mínimo de cartão (ADR-0026 §10)', () => {
  it('só pede os 4 últimos dígitos; envia o contrato e volta para a compra no cartão', async () => {
    const calls = stubBff({
      'GET /api/bff/accounts': () => json(accounts),
      'POST /api/bff/cards': () => json({}, 201),
    })
    const user = userEvent.setup()
    const { container } = render(<NewCardView />)

    expect(await screen.findByLabelText('4 últimos dígitos')).toHaveAccessibleDescription(
      'Nunca pedimos o número completo do cartão, o CVV, a validade ou a senha.',
    )
    expect(screen.queryByLabelText(/CVV|número do cartão|validade|senha/i)).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])

    await fill(user)
    await user.selectOptions(screen.getByLabelText('Conta que paga a fatura (opcional)'), AURORA)
    await user.click(screen.getByRole('button', { name: 'Cadastrar cartão' }))

    await waitFor(() => expect(push).toHaveBeenCalledWith('/transactions/new?type=CARD_PURCHASE'))
    expect(body(calls.find((c) => c.method === 'POST')!.init)).toEqual({
      nickname: 'Aurora Platinum',
      institutionName: null,
      brand: 'MASTERCARD',
      last4: '4821',
      limit: '12000.00',
      closingDay: 28,
      dueDay: 5,
      defaultPaymentAccountId: AURORA,
    })
  })

  it('validação local dos 4 dígitos e dos dias, com foco no primeiro erro e sem chamar o BFF', async () => {
    const calls = stubBff({ 'GET /api/bff/accounts': () => json(accounts) })
    const user = userEvent.setup()
    render(<NewCardView />)

    await fill(user, { last4: '48' })
    await user.clear(screen.getByLabelText('Dia de vencimento'))
    await user.type(screen.getByLabelText('Dia de vencimento'), '28')
    await user.click(screen.getByRole('button', { name: 'Cadastrar cartão' }))

    await waitFor(() => expect(screen.getByLabelText('4 últimos dígitos')).toHaveFocus())
    expect(screen.getByText('Use exatamente os 4 últimos dígitos, só números.')).toBeInTheDocument()
    expect(screen.getByText('O vencimento precisa ser num dia diferente do fechamento.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
  })

  it('regra do ciclo recusada pelo servidor aparece no campo do vencimento', async () => {
    stubBff({
      'GET /api/bff/accounts': () => json(accounts),
      'POST /api/bff/cards': () =>
        json({ code: 'VALIDATION_FAILED', details: [{ field: 'dueDay', code: 'CONFLICTS_WITH_CLOSING' }] }, 400),
    })
    const user = userEvent.setup()
    render(<NewCardView />)
    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Cadastrar cartão' }))

    await waitFor(() => expect(screen.getByLabelText('Dia de vencimento')).toHaveFocus())
    expect(screen.getByLabelText('Dia de vencimento')).toHaveAccessibleDescription(/o fechamento vai até o dia 27/)
    expect(push).not.toHaveBeenCalled()
  })
})
