import { render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  insufficientInvoices,
  notBuiltInvoices,
  partialCardInvoices,
  readyInvoices,
  recalculating,
} from '@/features/forecast/fixtures'
import { readyList } from '@/features/insights/fixtures'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import { FutureInvoicesView } from './FutureInvoicesView'
import { InvoiceView } from './InvoiceView'
import { AURORA, auroraCard, future, invoice, items } from './fixtures'

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/cards',
  useSearchParams: () => new URLSearchParams(),
}))

const text = (el: Element | null) => el?.textContent?.replace(/ /g, ' ') ?? ''

afterEach(() => vi.unstubAllGlobals())

function routes(forecast: () => Response) {
  return stubBff({
    'GET /api/bff/insights': () => json(readyList({ items: [] })),
    'GET /api/bff/categories': () => json({ items: [] }),
    'GET /api/bff/forecast/invoices': forecast,
    'GET /api/bff/cards/': (_init: RequestInit | undefined, url: string) => {
      if (url.includes('/items')) return json(items())
      if (url.includes('/invoices/future')) return json(future())
      if (url.includes('/invoices/')) return json(invoice())
      return json(auroraCard())
    },
  })
}

describe('D-Invoice — Forecast (ADR-0033 §4)', () => {
  it('fatura aberta: "No fechamento ≈" ao lado do parcial e a próxima fatura com as duas partes e o total só com "≈"', async () => {
    const calls = routes(() => json(readyInvoices()))
    const { container } = render(<InvoiceView cardId={AURORA} dueMonth={null} />)

    // O parcial continua um fato; o fechamento é estimativa, com faixa e base.
    const amount = (await screen.findByText('Valor até agora')).closest('section')!
    await within(amount).findByText('No fechamento')
    expect(text(amount)).toContain('R$ 3.412,80')
    expect(text(amount)).toContain('≈ R$ 3.800')
    expect(within(amount).getByText('entre R$ 3.500 e R$ 4.200')).toBeInTheDocument()
    expect(within(amount).getByText('confiança média · pelo seu padrão dos últimos 3 meses')).toBeInTheDocument()

    // Próxima fatura: comprometido (fato) e novas compras (estimativa) separados; total só "≈".
    const next = (await screen.findByRole('heading', { name: 'Próxima fatura · novembro' })).closest('section')!
    const rows = within(next).getAllByText(/Já comprometido|Novas compras|Total estimado/).map((el) => el.closest('div')!)
    expect(rows).toHaveLength(3)
    expect(text(rows[0]!)).toContain('R$ 480,00')
    expect(within(rows[0]!).getByText('Confirmado')).toBeInTheDocument()
    expect(text(rows[1]!)).toContain('Novas compras pelo seu padrão')
    expect(text(rows[1]!)).toContain('≈ R$ 2.532')
    expect(within(rows[1]!).getByText('Estimado')).toBeInTheDocument()
    expect(text(rows[2]!)).toContain('≈ R$ 3.012')
    expect(within(next).getByText('entre R$ 2.800 e R$ 3.300')).toBeInTheDocument()
    expect(within(next).queryByText('Novas compras ainda não aparecem aqui.')).not.toBeInTheDocument()
    expect(calls.some((c) => c.url === `/api/bff/forecast/invoices?cardId=${AURORA}`)).toBe(true)
    expect(await axeViolations(container)).toEqual([])
  })

  it('RECALCULATING: avisa que atualiza e mantém os valores', async () => {
    routes(() => json(recalculating(readyInvoices())))
    render(<InvoiceView cardId={AURORA} dueMonth={null} />)
    expect((await screen.findAllByText('Atualizando estimativas…')).length).toBeGreaterThan(0)
    expect(screen.getAllByText('No fechamento').length).toBe(1)
  })

  it.each([
    ['NOT_BUILT', notBuiltInvoices, 'Estimativas em preparação.'],
    ['INSUFFICIENT_HISTORY', insufficientInvoices, 'As estimativas aparecem depois de 3 meses de histórico.'],
  ])('%s: só o comprometido (fato), o aviso e a nota do estado', async (_name, build, note) => {
    routes(() => json(build()))
    const { container } = render(<InvoiceView cardId={AURORA} dueMonth={null} />)
    const next = (await screen.findByRole('heading', { name: 'Próxima fatura · novembro' })).closest('section')!
    expect(await within(next).findByText(note)).toBeInTheDocument()
    expect(within(next).getByText('Já comprometido (parcelas)')).toBeInTheDocument()
    expect(within(next).getByText('Novas compras ainda não aparecem aqui.')).toBeInTheDocument()
    expect(screen.queryByText('No fechamento')).not.toBeInTheDocument()
    expect(screen.queryByText(/≈/)).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('PARTIAL_CARD_HISTORY e erro no Forecast: sem estimativa, o aviso continua', async () => {
    routes(() => json(partialCardInvoices()))
    const first = render(<InvoiceView cardId={AURORA} dueMonth={null} />)
    expect(await screen.findByText('Novas compras ainda não aparecem aqui.')).toBeInTheDocument()
    expect(screen.queryByText(/≈/)).not.toBeInTheDocument()
    first.unmount()
    vi.unstubAllGlobals()

    routes(() => json({ code: 'UNAVAILABLE' }, 502))
    render(<InvoiceView cardId={AURORA} dueMonth={null} />)
    expect(await screen.findByText('Novas compras ainda não aparecem aqui.')).toBeInTheDocument()
    expect(screen.getByText('Já comprometido (parcelas)')).toBeInTheDocument()
    expect(screen.queryByText(/[Ee]stimativas em preparação/)).not.toBeInTheDocument()
  })

  it('fatura fechada não consulta o Forecast', async () => {
    const calls = stubBff({
      'GET /api/bff/insights': () => json(readyList({ items: [] })),
      'GET /api/bff/categories': () => json({ items: [] }),
      'GET /api/bff/cards/': (_init: RequestInit | undefined, url: string) => {
        if (url.includes('/items')) return json(items())
        if (url.includes('/invoices/')) return json(invoice({ status: 'CLOSED', partial: false }))
        return json(auroraCard())
      },
    })
    render(<InvoiceView cardId={AURORA} dueMonth="2026-10" />)
    await screen.findByRole('heading', { level: 1, name: 'Fatura de outubro' })
    await screen.findByText('Mostrando 2 de 2 lançamentos')
    expect(calls.filter((c) => c.url.startsWith('/api/bff/forecast'))).toHaveLength(0)
    expect(screen.queryByText('No fechamento')).not.toBeInTheDocument()
  })
})

describe('D-FutureInvoices — Forecast (ADR-0033 §4)', () => {
  it('por mês, comprometido e novas compras lado a lado; gráfico com trecho tracejado e tabela acessível', async () => {
    routes(() => json(readyInvoices()))
    const { container } = render(<FutureInvoicesView cardId={AURORA} />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Próximas faturas' })).toBeInTheDocument()

    const detail = (await screen.findByRole('heading', { name: 'Detalhe por mês' })).closest('section')!
    const november = within(detail).getAllByRole('listitem')[0]!
    expect(text(november)).toContain('Notebook · parcela 4/10')
    expect(text(november)).toContain('Já comprometido')
    expect(text(november)).toContain('R$ 480,00')
    expect(text(november)).toContain('Novas compras')
    expect(text(november)).toContain('≈ R$ 3.012')
    expect(within(november).getByText('Confirmado')).toBeInTheDocument()
    expect(within(november).getByText('Estimado')).toBeInTheDocument()
    expect(within(november).getByText('confiança média · pelo seu padrão dos últimos 3 meses')).toBeInTheDocument()
    // Faixa limitada pela API: o histórico curto aparece em frase.
    expect(text(within(detail).getAllByRole('listitem')[1]!)).toContain('Histórico curto: a estimativa pode mudar.')

    // O trecho estimado é tracejado no SVG (classe "partial") e há tabela com os mesmos dados.
    expect(container.querySelectorAll('svg rect[class*="partial"]').length).toBe(2)
    const table = screen.getByRole('table', { name: 'Comprometido e novas compras estimadas por fatura' })
    expect(within(table).getAllByRole('row')).toHaveLength(3)
    expect(text(table)).toContain('Novas compras (estimado)')
    expect(screen.queryByText('Novas compras ainda não aparecem aqui.')).not.toBeInTheDocument()
    expect(screen.getByText(/O trecho tracejado é estimado pelo seu padrão/)).toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('PARTIAL_CARD_HISTORY: só o comprometido e o aviso "Novas compras ainda não aparecem aqui."', async () => {
    routes(() => json(partialCardInvoices()))
    const { container } = render(<FutureInvoicesView cardId={AURORA} />)
    expect(await screen.findByText('Novas compras ainda não aparecem aqui.')).toBeInTheDocument()
    expect(screen.getByText('Notebook · parcela 4/10')).toBeInTheDocument()
    expect(container.querySelectorAll('svg rect[class*="partial"]').length).toBe(0)
    expect(screen.queryByText(/≈/)).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('RECALCULATING mantém os valores; NOT_BUILT e erro caem nos fatos do Cartões', async () => {
    routes(() => json(recalculating(readyInvoices())))
    const first = render(<FutureInvoicesView cardId={AURORA} />)
    expect(await screen.findByText('Atualizando estimativas…')).toBeInTheDocument()
    expect(first.container.textContent).toContain('≈')
    first.unmount()
    vi.unstubAllGlobals()

    routes(() => json(notBuiltInvoices()))
    const second = render(<FutureInvoicesView cardId={AURORA} />)
    expect(await screen.findByText('Estimativas em preparação.')).toBeInTheDocument()
    expect(screen.getByText('Novas compras ainda não aparecem aqui.')).toBeInTheDocument()
    expect(await axeViolations(second.container)).toEqual([])
    second.unmount()
    vi.unstubAllGlobals()

    routes(() => json({ code: 'UNAVAILABLE' }, 502))
    render(<FutureInvoicesView cardId={AURORA} />)
    expect(await screen.findByText('Novas compras ainda não aparecem aqui.')).toBeInTheDocument()
    expect(screen.getByText('Notebook · parcela 4/10')).toBeInTheDocument()
  })
})
