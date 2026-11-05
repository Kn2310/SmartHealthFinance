import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import { brl, recurringAction, recurringSeries, SERIES_RENT } from './fixtures'
import { RecurringView } from './RecurringView'

let search = new URLSearchParams()
vi.mock('next/navigation', () => ({
  useSearchParams: () => search,
}))

const SALARY = '01922f5e-0000-7000-8000-0000000000d3'
const NETFLIX = '01922f5e-0000-7000-8000-0000000000d4'

const accountsResponse = { items: [{ id: 'a1', name: 'Conta Corrente' }] }
const cardsResponse = { cards: [{ card: { id: 'c1', nickname: 'Aurora Platinum' } }] }

const rent = (o = {}) => recurringSeries({ id: SERIES_RENT, ...o })
const netflix = (o = {}) =>
  recurringSeries({ id: NETFLIX, label: 'Streaming', channel: { kind: 'CARD', id: 'c1' }, expectedDay: 12,
    expectedAmount: brl('55.90'), amountRange: { low: brl('55.90'), high: brl('55.90') },
    status: 'CONFIRMED', origin: 'DETECTED', ...o })

function stubAll(items: unknown[], extra: Record<string, () => Response> = {}) {
  return stubBff({
    'GET /api/bff/accounts': () => json(accountsResponse),
    'GET /api/bff/cards/summary': () => json(cardsResponse),
    'GET /api/bff/forecast/status': () => json({ state: 'UP_TO_DATE' }),
    'GET /api/bff/recurring-expenses': () => json({ items }),
    'GET /api/bff/recurring-incomes': () => json({ items: [] }),
    ...extra,
  })
}

beforeEach(() => {
  search = new URLSearchParams()
})
afterEach(() => vi.unstubAllGlobals())

describe('Recorrências (/recurring, ADR-0033 §5)', () => {
  it('seções por status, com canal, dia, valor com faixa, confiança, origem e avisos de elegibilidade', async () => {
    stubAll([
      rent(),
      netflix({ eligibility: 'STALE' }),
      recurringSeries({ id: '01922f5e-0000-7000-8000-0000000000d5', label: 'Seguro', status: 'CONFIRMED',
        eligibility: 'NOT_ELIGIBLE', origin: 'USER', userAdjusted: true }),
      recurringSeries({ id: '01922f5e-0000-7000-8000-0000000000d6', label: 'Clube', status: 'IGNORED' }),
    ])
    const { container } = render(<RecurringView />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Recorrências' })).toBeInTheDocument()
    const review = screen.getByRole('region', { name: 'Para revisar' })
    expect(within(review).getByRole('heading', { name: 'Aluguel' })).toBeInTheDocument()
    expect(review).toHaveTextContent('Detectada')
    expect(review).toHaveTextContent('todo dia 7')
    expect(review).toHaveTextContent('confiança alta')
    expect(review).toHaveTextContent('entre R$ 1.800 e R$ 1.800')
    expect(await within(review).findByText(/Conta Corrente/)).toBeInTheDocument()

    const confirmed = screen.getByRole('region', { name: 'Confirmadas' })
    expect(await within(confirmed).findByText(/Cartão Aurora Platinum · todo dia 12/)).toBeInTheDocument()
    expect(within(confirmed).getByText('Sem ocorrência recente')).toBeInTheDocument()
    expect(within(confirmed).getByText('Não entra nas estimativas')).toBeInTheDocument()
    expect(within(confirmed).getByText('Ajustada por você')).toBeInTheDocument()

    // Ignoradas: recolhida por padrão.
    const ignored = screen.getByText('Ignoradas').closest('details')!
    expect(ignored).not.toHaveAttribute('open')
    expect(await axeViolations(container)).toEqual([])
  })

  it('aba Receitas pela URL', async () => {
    search = new URLSearchParams('tab=incomes')
    const calls = stubAll([], {
      'GET /api/bff/recurring-incomes': () =>
        json({ items: [recurringSeries({ id: SALARY, direction: 'INCOME', label: 'Salário' })] }),
    })
    render(<RecurringView />)
    expect(await screen.findByRole('heading', { name: 'Salário' })).toBeInTheDocument()
    expect(calls.some((c) => c.url.startsWith('/api/bff/recurring-incomes'))).toBe(true)
    expect(screen.getByRole('link', { name: 'Receitas' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Despesas' })).toHaveAttribute('href', '/recurring?tab=expenses')
  })

  it('vazio, carregando e erro com nova tentativa', async () => {
    stubAll([])
    const { container, unmount } = render(<RecurringView />)
    expect(screen.getByRole('status', { busy: true })).toHaveTextContent('Carregando as recorrências…')
    expect(await screen.findByText(
      'Ainda não identificamos recorrências. Elas aparecem depois de 3 meses de histórico.')).toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
    unmount()

    let fail = true
    stubAll([rent()], { 'GET /api/bff/recurring-expenses': () => (fail ? json({ code: 'UNAVAILABLE' }, 502) : json({ items: [rent()] })) })
    const second = render(<RecurringView />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Não conseguimos carregar suas recorrências agora.')
    expect(await axeViolations(second.container)).toEqual([])
    fail = false
    await userEvent.click(screen.getByRole('button', { name: 'Tentar novamente' }))
    expect(await screen.findByRole('heading', { name: 'Aluguel' })).toBeInTheDocument()
  })

  it('confirmar move a série e mostra "Atualizando estimativas…"', async () => {
    const calls = stubAll([rent()], {
      [`POST /api/bff/recurring-expenses/${SERIES_RENT}/confirm`]: () =>
        json(recurringAction(rent({ status: 'CONFIRMED' }))),
    })
    render(<RecurringView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Confirmar Aluguel' }))

    const confirmed = await screen.findByRole('region', { name: 'Confirmadas' })
    expect(within(confirmed).getByRole('heading', { name: 'Aluguel' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Para revisar' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('status').some((s) => s.textContent?.includes('Atualizando estimativas…'))).toBe(true)
    expect(screen.getAllByRole('status').some((s) => s.textContent?.includes('Recorrência confirmada.'))).toBe(true)
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1)
  })

  it('ignorar mostra o aviso com "Desfazer", que confirma de volta', async () => {
    const calls = stubAll([rent()], {
      [`POST /api/bff/recurring-expenses/${SERIES_RENT}/ignore`]: () => json(recurringAction(rent({ status: 'IGNORED' }))),
      [`POST /api/bff/recurring-expenses/${SERIES_RENT}/confirm`]: () => json(recurringAction(rent({ status: 'CONFIRMED' }))),
    })
    const { container } = render(<RecurringView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Ignorar Aluguel' }))

    const toast = await screen.findByText('Recorrência ignorada.')
    expect(toast.closest('[role="status"]')).not.toBeNull()
    const ignored = screen.getByText('Ignoradas').closest('details')!
    expect(within(ignored).getByRole('heading', { name: 'Aluguel', hidden: true })).toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])

    await userEvent.click(screen.getByRole('button', { name: 'Desfazer' }))
    const confirmed = await screen.findByRole('region', { name: 'Confirmadas' })
    expect(within(confirmed).getByRole('heading', { name: 'Aluguel' })).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'POST').map((c) => c.url.split('/').pop())).toEqual(['ignore', 'confirm'])
    expect(screen.queryByRole('button', { name: 'Desfazer' })).not.toBeInTheDocument()
  })

  it('falha numa ação mostra o erro e mantém a lista', async () => {
    stubAll([rent()], { [`POST /api/bff/recurring-expenses/${SERIES_RENT}/confirm`]: () => json({ code: 'UNAVAILABLE' }, 502) })
    render(<RecurringView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Confirmar Aluguel' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Não conseguimos concluir agora.')
    expect(screen.getByRole('region', { name: 'Para revisar' })).toBeInTheDocument()
  })

  it('ajustar: drawer com valor e dia, só os campos alterados vão ao BFF, e o foco volta ao gatilho', async () => {
    const calls = stubAll([rent({ status: 'CONFIRMED' })], {
      [`PUT /api/bff/recurring-expenses/${SERIES_RENT}/adjustment`]: () =>
        json(recurringAction(rent({ status: 'CONFIRMED', userAdjusted: true, origin: 'USER', expectedAmount: brl('1950.00'),
          amountRange: { low: brl('1950.00'), high: brl('1950.00') } }))),
    })
    const { container } = render(<RecurringView />)
    const trigger = await screen.findByRole('button', { name: 'Ajustar Aluguel' })
    await userEvent.click(trigger)

    const dialog = await screen.findByRole('dialog', { name: 'Ajustar recorrência' })
    const amount = within(dialog).getByLabelText('Valor esperado')
    expect(amount).toHaveValue('1800,00')
    expect(within(dialog).getByLabelText('Dia do mês')).toHaveValue('7')
    expect(within(dialog).queryByRole('button', { name: 'Voltar ao detectado' })).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])

    await userEvent.clear(amount)
    await userEvent.type(amount, '1.950,00')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Salvar ajuste' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const put = calls.find((c) => c.method === 'PUT')!
    expect(JSON.parse(String(put.init?.body))).toEqual({ expectedAmount: { amount: '1950.00', currency: 'BRL' } })
    expect(await screen.findByText('Ajustada por você')).toBeInTheDocument()
    expect(screen.getByText('Ajuste salvo.')).toBeInTheDocument()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Ajustar Aluguel' }))
  })

  it('ajustar: validação local por campo e nada a salvar', async () => {
    const calls = stubAll([rent({ status: 'CONFIRMED' })])
    render(<RecurringView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Ajustar Aluguel' }))
    const dialog = await screen.findByRole('dialog')

    await userEvent.click(within(dialog).getByRole('button', { name: 'Salvar ajuste' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Altere o valor ou o dia antes de salvar.')

    const day = within(dialog).getByLabelText('Dia do mês')
    await userEvent.clear(day)
    await userEvent.type(day, '32')
    const amount = within(dialog).getByLabelText('Valor esperado')
    await userEvent.clear(amount)
    await userEvent.type(amount, '0')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Salvar ajuste' }))
    expect(amount).toHaveAccessibleDescription('Use um valor maior que zero.')
    expect(day).toHaveAccessibleDescription('Use um dia do mês de 1 a 31.')
    expect(amount).toHaveAttribute('aria-invalid', 'true')
    expect(calls.some((c) => c.method === 'PUT')).toBe(false)
  })

  it('ajustar: erro 400 do servidor aparece no campo correspondente', async () => {
    stubAll([rent({ status: 'CONFIRMED' })], {
      [`PUT /api/bff/recurring-expenses/${SERIES_RENT}/adjustment`]: () =>
        json({ code: 'VALIDATION_FAILED', details: [{ field: 'expectedDay', code: 'OUT_OF_RANGE' }] }, 400),
    })
    const { container } = render(<RecurringView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Ajustar Aluguel' }))
    const dialog = await screen.findByRole('dialog')
    const day = within(dialog).getByLabelText('Dia do mês')
    await userEvent.clear(day)
    await userEvent.type(day, '10')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Salvar ajuste' }))

    await waitFor(() => expect(day).toHaveAttribute('aria-invalid', 'true'))
    expect(day).toHaveAccessibleDescription('Use um dia do mês de 1 a 31.')
    expect(within(dialog).getByLabelText('Valor esperado')).not.toHaveAttribute('aria-invalid')
    expect(await axeViolations(container)).toEqual([])
  })

  it('ajustar: falha de rede mostra erro geral e mantém o drawer aberto', async () => {
    stubAll([rent({ status: 'CONFIRMED' })], {
      [`PUT /api/bff/recurring-expenses/${SERIES_RENT}/adjustment`]: () => json({ code: 'UNAVAILABLE' }, 502),
    })
    render(<RecurringView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Ajustar Aluguel' }))
    const dialog = await screen.findByRole('dialog')
    const day = within(dialog).getByLabelText('Dia do mês')
    await userEvent.clear(day)
    await userEvent.type(day, '10')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Salvar ajuste' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Não conseguimos salvar agora.')
  })

  it('"Voltar ao detectado" (série ajustada) chama DELETE e fecha o drawer; Esc também fecha', async () => {
    const adjusted = rent({ status: 'CONFIRMED', userAdjusted: true, origin: 'USER', expectedAmount: brl('1950.00') })
    const calls = stubAll([adjusted], {
      [`DELETE /api/bff/recurring-expenses/${SERIES_RENT}/adjustment`]: () => json(recurringAction(rent({ status: 'CONFIRMED' }))),
    })
    render(<RecurringView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Ajustar Aluguel' }))
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: 'Ajustar Aluguel' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Voltar ao detectado' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(calls.some((c) => c.method === 'DELETE')).toBe(true)
    expect(await screen.findByText('Detectada')).toBeInTheDocument()
    expect(screen.getByText('Voltamos aos valores detectados.')).toBeInTheDocument()
  })

  it('depois da ação, relê a lista quando o Forecast termina de recalcular', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      let reads = 0
      const calls = stubAll([], {
        'GET /api/bff/recurring-expenses': () => {
          reads += 1
          return json({ items: [rent({ status: reads === 1 ? 'DETECTED' : 'CONFIRMED', eligibility: reads === 1 ? null : 'ELIGIBLE' })] })
        },
        [`POST /api/bff/recurring-expenses/${SERIES_RENT}/confirm`]: () => json(recurringAction(rent({ status: 'CONFIRMED', eligibility: null }))),
      })
      render(<RecurringView />)
      await userEvent.setup({ advanceTimers: vi.advanceTimersByTime.bind(vi) }).click(await screen.findByRole('button', { name: 'Confirmar Aluguel' }))
      await waitFor(() => expect(screen.getByText('Atualizando estimativas…')).toBeInTheDocument())

      await vi.advanceTimersByTimeAsync(2100)
      await waitFor(() => expect(screen.queryByText('Atualizando estimativas…')).not.toBeInTheDocument())
      expect(calls.some((c) => c.url === '/api/bff/forecast/status')).toBe(true)
      expect(reads).toBe(2)
    } finally {
      vi.useRealTimers()
    }
  })
})
