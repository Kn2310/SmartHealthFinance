import { render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  estimate,
  notBuiltUpcoming,
  readyUpcoming,
  recalculating,
} from '@/features/forecast/fixtures'
import type { Overview } from '@/lib/api/types'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import { brl, readyOverview } from './fixtures'
import { UpcomingEvents } from './UpcomingEvents'

const text = (el: Element | null) => el?.textContent?.replace(/ /g, ' ') ?? ''

const bills = (): NonNullable<Overview['cards']> => ({
  activeCards: 1,
  openInvoices: brl('3412.80'),
  nextDue: { date: '2026-10-05', amount: brl('3412.80') },
  upcomingEvents: [
    {
      date: '2026-09-28', kind: 'INVOICE_CLOSING', status: 'PARTIAL', dueMonth: '2026-10', amount: brl('3412.80'),
      card: { id: 'c1', nickname: 'Aurora Platinum', last4: '4821' },
    },
  ],
  asOf: '2026-09-27',
  calculationVersion: 'invoice-v2',
})

const accounts = () => readyOverview().accounts

afterEach(() => vi.unstubAllGlobals())

function renderEvents(response: Response | (() => Response), overviewBills: Overview['cards'] = bills()) {
  stubBff({ 'GET /api/bff/forecast/upcoming': () => (typeof response === 'function' ? response() : response) })
  return render(<UpcomingEvents bills={overviewBills} accounts={accounts()} />)
}

describe('Próximos eventos — Forecast pronto (ADR-0033 §2)', () => {
  it('fatos e estimados na ordem da API, cada um com o seu selo, e "≈ no fechamento" na fatura aberta', async () => {
    const { container } = renderEvents(() => json(readyUpcoming()))
    const card = await screen.findByRole('region', { name: 'Próximos eventos' })
    const items = await within(card).findAllByRole('listitem')
    expect(items).toHaveLength(3)

    // Fatura aberta: o fato parcial e, ao lado, o fechamento estimado.
    expect(text(items[0]!)).toContain('Fechamento da fatura Aurora Platinum')
    expect(text(items[0]!)).toContain('28 set. · Cartão •••• 4821 · Fatura de outubro')
    expect(within(items[0]!).getByText('Parcial')).toBeInTheDocument()
    expect(text(items[0]!)).toContain('R$ 3.412,80')
    expect(text(items[0]!)).toContain('no fechamento')
    expect(text(items[0]!)).toContain('≈ R$ 3.800')
    expect(text(items[0]!)).toContain('entre R$ 3.500 e R$ 4.200')

    // Recorrências estimadas: selo, "Recorrente · {conta}" e valor sem centavos.
    expect(text(items[1]!)).toContain('Salário')
    expect(text(items[1]!)).toContain('5 out. · Recorrente · Conta Aurora')
    expect(within(items[1]!).getByText('Estimado')).toBeInTheDocument()
    expect(text(items[1]!)).toContain('≈ R$ 6.500')
    expect(items[1]!.textContent).not.toContain('6.500,00')
    expect(within(items[2]!).getByText('Aluguel')).toBeInTheDocument()

    expect(screen.getByRole('link', { name: 'Gerenciar recorrências' })).toHaveAttribute('href', '/recurring')
    expect(card).toHaveAttribute('aria-labelledby', 'events-title')
    expect(screen.queryByText('Estimativas em preparação.')).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('RECALCULATING: "Atualizando estimativas…" e os valores anteriores continuam visíveis', async () => {
    const { container } = renderEvents(() => json(recalculating(readyUpcoming())))
    expect(await screen.findByText('Atualizando estimativas…')).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
    expect(text(screen.getByRole('region', { name: 'Próximos eventos' }))).toContain('≈ R$ 6.500')
    expect(await axeViolations(container)).toEqual([])
  })

  it('INSUFFICIENT_HISTORY: só os fatos da API e a nota do histórico', async () => {
    const factsOnly = readyUpcoming()
    factsOnly.state = 'INSUFFICIENT_HISTORY'
    factsOnly.events = [factsOnly.events[0]!]
    factsOnly.events[0]!.closingEstimate = null
    const { container } = renderEvents(() => json(factsOnly))
    expect(await screen.findByText('As estimativas aparecem depois de 3 meses de histórico.')).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
    expect(screen.queryByText('Estimado')).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('agenda vazia: mensagem de vazio; fatura aberta/vencida mostram a tag de status', async () => {
    const response = readyUpcoming({ events: [] })
    const { unmount } = renderEvents(() => json(response))
    expect(await screen.findByText('Nenhum evento nos próximos 30 dias.')).toBeInTheDocument()
    unmount()
    vi.unstubAllGlobals()

    const overdue = readyUpcoming()
    overdue.events = [{
      kind: 'INVOICE_OVERDUE', date: '2026-09-05', seriesId: null, label: 'Horizonte Gold',
      channel: { kind: 'CARD', id: 'c2' },
      value: { ...estimate('120.00', '120.00', '120.00'), basis: 'FACT', partial: false },
      closingEstimate: null,
    }]
    renderEvents(() => json(overdue))
    expect(await screen.findByText('Vencida')).toBeInTheDocument()
    expect(screen.getByText('Fatura Horizonte Gold')).toBeInTheDocument()
  })
})

describe('Próximos eventos — fallback aprovado (ADR-0033 §2)', () => {
  it('NOT_BUILT: eventos de fatura do Overview (só fatos) e a nota "Estimativas em preparação."', async () => {
    const { container } = renderEvents(() => json(notBuiltUpcoming()))
    expect(await screen.findByText('Estimativas em preparação.')).toBeInTheDocument()
    const items = screen.getAllByRole('listitem')
    expect(items).toHaveLength(1)
    expect(text(items[0]!)).toContain('Fechamento da fatura Aurora Platinum')
    expect(screen.queryByText('Estimado')).not.toBeInTheDocument()
    expect(screen.queryByText(/≈/)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Gerenciar recorrências' })).toHaveAttribute('href', '/recurring')
    expect(await axeViolations(container)).toEqual([])
  })

  it('erro no Forecast: o mesmo fallback, sem quebrar o card', async () => {
    const { container } = renderEvents(() => json({ code: 'UNAVAILABLE' }, 502))
    expect(await screen.findByText('Estimativas em preparação.')).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
    expect(await axeViolations(container)).toEqual([])
  })

  it('carregando: skeleton acessível dentro do card; sem cartão e sem agenda, o bloco não aparece', async () => {
    renderEvents(() => json(readyUpcoming()))
    expect(screen.getByRole('status')).toHaveTextContent('Carregando os próximos eventos')
    await screen.findAllByRole('listitem')
    vi.unstubAllGlobals()

    const { container } = renderEvents(() => json(notBuiltUpcoming()), null)
    expect(container.querySelector('#events-title')).toBeNull()
  })

  it('sem cartão ativo, as recorrências da agenda ainda aparecem', async () => {
    const response = readyUpcoming()
    response.events = response.events.slice(1)
    renderEvents(() => json(response), null)
    expect(await screen.findByText('Salário')).toBeInTheDocument()
  })
})
