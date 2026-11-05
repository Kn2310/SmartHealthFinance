import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fact,
  notBuiltMonth,
  readyMonth,
  readyNextIncome,
  recalculating,
} from '@/features/forecast/fixtures'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import { AnalysisView } from './AnalysisView'
import { income, spending } from './fixtures'

let search = new URLSearchParams()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/analysis/spending',
  useSearchParams: () => search,
}))

const catalog = { items: [{ code: 'HOUSING', name: 'Moradia', nature: 'EXPENSE', active: true }] }
const text = (el: Element | null) => el?.textContent?.replace(/ /g, ' ') ?? ''

beforeEach(() => {
  search = new URLSearchParams()
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-09-22T15:00:00Z'))
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

function spendingWith(month: Response | (() => Response)) {
  return stubBff({
    'GET /api/bff/analytics/spending': () => json(spending()),
    'GET /api/bff/categories': () => json(catalog),
    'GET /api/bff/forecast/month': () => (typeof month === 'function' ? month() : month),
  })
}

describe('Gastos — "Projeção para o mês" (ADR-0033 §3)', () => {
  it('READY: ≈ sem centavos, selo, faixa, base, realizado (fato) e link para as recorrências', async () => {
    spendingWith(() => json(readyMonth()))
    const { container } = render(<AnalysisView tab="spending" />)
    const card = await screen.findByRole('region', { name: 'Projeção para o mês' })
    await within(card).findByText('Estimado')

    expect(text(card)).toContain('≈ R$ 4.664')
    expect(text(card)).not.toContain('4.664,10')
    expect(within(card).getByText('entre R$ 4.400 e R$ 5.100')).toBeInTheDocument()
    expect(within(card).getByText('confiança média · pelo seu padrão dos últimos 3 meses')).toBeInTheDocument()
    expect(text(card)).toContain('Realizado até agora: R$ 3.846,20')
    expect(within(card).getByRole('link', { name: 'Ver recorrências' })).toHaveAttribute('href', '/recurring?tab=expenses')
    expect(within(card).queryByText('Atualizando estimativas…')).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('RECALCULATING: avisa que atualiza e mantém o valor anterior', async () => {
    spendingWith(() => json(recalculating(readyMonth())))
    const { container } = render(<AnalysisView tab="spending" />)
    const card = await screen.findByRole('region', { name: 'Projeção para o mês' })
    expect(await within(card).findByText('Atualizando estimativas…')).toBeInTheDocument()
    expect(text(card)).toContain('≈ R$ 4.664')
    expect(await axeViolations(container)).toEqual([])
  })

  it('NOT_BUILT: só a nota "Estimativas em preparação."', async () => {
    spendingWith(() => json(notBuiltMonth()))
    const { container } = render(<AnalysisView tab="spending" />)
    const card = await screen.findByRole('region', { name: 'Projeção para o mês' })
    expect(await within(card).findByText('Estimativas em preparação.')).toBeInTheDocument()
    expect(text(card)).not.toContain('≈')
    expect(await axeViolations(container)).toEqual([])
  })

  it('INSUFFICIENT_HISTORY: só o realizado e a nota do histórico', async () => {
    const response = readyMonth({ state: 'INSUFFICIENT_HISTORY' })
    response.month!.baseMonths = 0
    response.month!.expense = fact('3846.20')
    spendingWith(() => json(response))
    const { container } = render(<AnalysisView tab="spending" />)
    const card = await screen.findByRole('region', { name: 'Projeção para o mês' })
    expect(await within(card).findByText('As estimativas aparecem depois de 3 meses de histórico.')).toBeInTheDocument()
    expect(text(card)).toContain('Gasto até agora')
    expect(text(card)).toContain('R$ 3.846,20')
    expect(text(card)).not.toContain('≈')
    expect(await axeViolations(container)).toEqual([])
  })

  it('erro: avisa sem esconder a aba e permite tentar de novo', async () => {
    let fail = true
    const calls = spendingWith(() => (fail ? json({ code: 'UNAVAILABLE' }, 502) : json(readyMonth())))
    const user = userEvent.setup()
    render(<AnalysisView tab="spending" />)
    const card = await screen.findByRole('region', { name: 'Projeção para o mês' })
    expect(await within(card).findByText('Não conseguimos carregar a estimativa agora.')).toBeInTheDocument()
    expect(screen.getByText('Maiores gastos')).toBeInTheDocument()
    fail = false
    await user.click(within(card).getByRole('button', { name: 'Tentar novamente' }))
    expect(await within(card).findByText('Estimado')).toBeInTheDocument()
    expect(calls.filter((c) => c.url === '/api/bff/forecast/month')).toHaveLength(2)
  })

  it('outro mês: a projeção só vale para o mês corrente', async () => {
    const response = readyMonth()
    response.month!.month = '2026-10'
    spendingWith(() => json(response))
    render(<AnalysisView tab="spending" />)
    expect(await screen.findByText('A projeção vale só para o mês corrente.')).toBeInTheDocument()
  })
})

describe('Receitas — "Próxima receita prevista" (ADR-0033 §3)', () => {
  function incomeWith(next: Response | (() => Response)) {
    return stubBff({
      'GET /api/bff/analytics/income': () => json(income()),
      'GET /api/bff/categories': () => json(catalog),
      'GET /api/bff/forecast/next-income': () => (typeof next === 'function' ? next() : next),
    })
  }

  it('READY: fonte, data, "pelo seu histórico", faixa e link "Ver recorrências"', async () => {
    incomeWith(() => json(readyNextIncome()))
    const { container } = render(<AnalysisView tab="income" />)
    const card = await screen.findByRole('region', { name: 'Próxima receita prevista' })
    await within(card).findByText('Estimado')
    expect(text(card)).toContain('Salário · 5 out.')
    expect(text(card)).toContain('≈ R$ 6.500')
    expect(within(card).getByText('entre R$ 6.500 e R$ 6.500')).toBeInTheDocument()
    expect(within(card).getByText('confiança alta · pelo seu histórico')).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Ver recorrências' })).toHaveAttribute('href', '/recurring?tab=incomes')
    expect(await axeViolations(container)).toEqual([])
  })

  it('sem receita recorrente: "Sem receita recorrente identificada"', async () => {
    incomeWith(() => json(readyNextIncome({ nextIncome: null })))
    const { container } = render(<AnalysisView tab="income" />)
    expect(await screen.findByText('Sem receita recorrente identificada')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver recorrências' })).toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('RECALCULATING, NOT_BUILT, INSUFFICIENT_HISTORY e erro', async () => {
    incomeWith(() => json(recalculating(readyNextIncome())))
    const first = render(<AnalysisView tab="income" />)
    expect(await screen.findByText('Atualizando estimativas…')).toBeInTheDocument()
    expect(text(first.container)).toContain('≈ R$ 6.500')
    first.unmount()
    vi.unstubAllGlobals()

    incomeWith(() => json(readyNextIncome({ state: 'NOT_BUILT', nextIncome: null })))
    const second = render(<AnalysisView tab="income" />)
    expect(await screen.findByText('Estimativas em preparação.')).toBeInTheDocument()
    second.unmount()
    vi.unstubAllGlobals()

    incomeWith(() => json(readyNextIncome({ state: 'INSUFFICIENT_HISTORY', nextIncome: null })))
    const third = render(<AnalysisView tab="income" />)
    expect(await screen.findByText('As estimativas aparecem depois de 3 meses de histórico.')).toBeInTheDocument()
    third.unmount()
    vi.unstubAllGlobals()

    incomeWith(() => json({ code: 'UNAVAILABLE' }, 502))
    render(<AnalysisView tab="income" />)
    expect(await screen.findByText('Não conseguimos carregar a estimativa agora.')).toBeInTheDocument()
    expect(screen.getByText('Fontes de receita')).toBeInTheDocument()
  })
})
