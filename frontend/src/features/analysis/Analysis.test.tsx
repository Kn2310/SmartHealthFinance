import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import { evolution as healthEvolution } from '@/features/health/fixtures'
import { AnalysisView } from './AnalysisView'
import { categoriesView, categoryDetail, evolution, income, merchants, meta, overview, spending } from './fixtures'
import { baseLabel, formatPercent, periodHeading, variationText } from './format'
import { monthOptions, parseAnalysisQuery, toApiSearch, toPageSearch } from './query'

const replace = vi.fn()
let search = new URLSearchParams()
let pathname = '/analysis'

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace }),
  usePathname: () => pathname,
  useSearchParams: () => search,
}))

const catalog = {
  items: [
    { code: 'HOUSING', name: 'Moradia', nature: 'EXPENSE', active: true },
    { code: 'DINING_OUT', name: 'Alimentação fora', nature: 'EXPENSE', active: true },
    { code: 'GROCERIES', name: 'Mercado', nature: 'EXPENSE', active: true },
    { code: 'TRANSPORT', name: 'Transporte', nature: 'EXPENSE', active: true },
    { code: 'SALARY', name: 'Salário', nature: 'INCOME', active: true },
  ],
}

beforeEach(() => {
  replace.mockReset()
  search = new URLSearchParams()
  pathname = '/analysis'
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-09-22T15:00:00Z'))
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('formatação (só texto; os números vêm do backend)', () => {
  it('percentuais e variações em pt-BR', () => {
    expect(formatPercent('14.1')).toBe('+14,1%')
    expect(formatPercent('-8.0')).toBe('−8%')
    expect(formatPercent('32.0')).toBe('+32%')
    expect(formatPercent('0.0')).toBe('0%')
    expect(formatPercent(null)).toBeNull()
    const m = meta()
    expect(baseLabel(m, 'previous')).toBe('1 a 22 de agosto')
    expect(baseLabel(m, 'average')).toBe('média de abr–ago')
    expect(variationText({ base: { amount: '1', currency: 'BRL' }, difference: { amount: '0', currency: 'BRL' }, percent: '0.3', trend: 'STABLE' }, m, 'average'))
      .toBe('Estável vs média de abr–ago')
    expect(periodHeading(m)).toBe('Setembro de 2026 (1 a 22) comparado a 1 a 22 de agosto')
  })

  it('parâmetros da URL e do BFF', () => {
    const query = parseAnalysisQuery(new URLSearchParams('month=2026-08&compare=AVERAGE_3'))
    expect(query).toEqual({ month: '2026-08', compare: 'AVERAGE_3' })
    expect(toApiSearch(query)).toBe('month=2026-08&compareWith=AVERAGE&averageMonths=3')
    expect(toPageSearch(query, { sort: 'COUNT' })).toBe('month=2026-08&compare=AVERAGE_3&sort=COUNT')
    expect(parseAnalysisQuery(new URLSearchParams('month=2026-13&compare=x'))).toEqual({ month: undefined, compare: 'PREVIOUS' })
    expect(monthOptions('2026-02-10', 3)).toEqual(['2026-02', '2026-01', '2025-12'])
  })
})

describe('/analysis — abas', () => {
  it('visão geral: métricas, 6 meses com tabela, "Por que meu dinheiro mudou?" e mudanças; acessível', async () => {
    const calls = stubBff({ 'GET /api/bff/analytics/overview': () => json(overview()), 'GET /api/bff/categories': () => json(catalog) })
    const { container } = render(<AnalysisView tab="overview" />)

    expect(await screen.findByText('Setembro de 2026 (1 a 22) comparado a 1 a 22 de agosto')).toBeInTheDocument()
    expect(calls[0]!.url).toBe('/api/bff/analytics/overview')
    expect(screen.getByText('+14,1% vs 1 a 22 de agosto')).toBeInTheDocument()
    expect(screen.getByText('Estável vs 1 a 22 de agosto')).toBeInTheDocument()
    expect(screen.getByText('43,4% da renda não foi gasta')).toBeInTheDocument()

    const why = screen.getByRole('list', { name: 'Composição do resultado do período' })
    expect(within(why).getByText('Demais categorias (2)')).toBeInTheDocument()
    expect(await within(why).findByText('Alimentação fora')).toBeInTheDocument()
    expect(within(why).getByText('Resultado do período')).toBeInTheDocument()

    // Alternativa textual do gráfico: tabela com os 6 meses (o mês parcial marcado).
    const table = screen.getByRole('table', { name: 'Receitas e despesas nos últimos 6 meses' })
    expect(within(table).getAllByRole('row')).toHaveLength(7)
    expect(within(table).getByText('set/26 (parcial)')).toBeInTheDocument()

    expect(screen.getByRole('link', { name: 'Visão geral' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Gastos' })).toHaveAttribute('href', '/analysis/spending')
    expect(await axeViolations(container)).toEqual([])
  })

  it('mês e "Comparar com" vão para a URL; abas preservam a seleção', async () => {
    search = new URLSearchParams('month=2026-08&compare=AVERAGE_6')
    const calls = stubBff({ 'GET /api/bff/analytics/overview': () => json(overview()), 'GET /api/bff/categories': () => json(catalog) })
    const user = userEvent.setup()
    render(<AnalysisView tab="overview" />)
    await screen.findByText(/Setembro de 2026/)

    expect(calls[0]!.url).toBe('/api/bff/analytics/overview?month=2026-08&compareWith=AVERAGE&averageMonths=6')
    expect(screen.getByRole('link', { name: 'Comerciantes' })).toHaveAttribute('href', '/analysis/merchants?month=2026-08&compare=AVERAGE_6')

    await user.selectOptions(screen.getByLabelText('Comparar com'), 'PREVIOUS')
    expect(replace).toHaveBeenLastCalledWith('/analysis?month=2026-08', { scroll: false })
    await user.selectOptions(screen.getByLabelText('Mês'), '2026-09')
    expect(replace).toHaveBeenLastCalledWith('/analysis?compare=AVERAGE_6', { scroll: false })
  })

  it('gastos: média por dia, acumulado com tabela e maiores gastos', async () => {
    pathname = '/analysis/spending'
    stubBff({ 'GET /api/bff/analytics/spending': () => json(spending()), 'GET /api/bff/categories': () => json(catalog) })
    const { container } = render(<AnalysisView tab="spending" />)

    expect(await screen.findByText('R$ 174,83')).toBeInTheDocument()
    expect(screen.getByText('agosto:')).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Gasto acumulado em setembro' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Imobiliária Exemplo' })).toHaveAttribute('href', '/transactions/01922f5e-0000-7000-8000-0000000000c1')
    expect(await axeViolations(container)).toEqual([])
  })

  it('receitas: fontes recorrentes e pontuais', async () => {
    stubBff({ 'GET /api/bff/analytics/income': () => json(income()), 'GET /api/bff/categories': () => json(catalog) })
    render(<AnalysisView tab="income" />)

    expect(await screen.findByText('Recorrente · costuma cair no dia 05 · 95,6%')).toBeInTheDocument()
    expect(screen.getByText('Pontual · 18 set. · 4,4%')).toBeInTheDocument()
  })

  it('categorias: lista, seleção na URL e detalhe com ticket médio', async () => {
    search = new URLSearchParams('category=DINING_OUT')
    const calls = stubBff({
      'GET /api/bff/analytics/categories/': () => json(categoryDetail()),
      'GET /api/bff/analytics/categories': () => json(categoriesView()),
      'GET /api/bff/categories': () => json(catalog),
    })
    const user = userEvent.setup()
    const { container } = render(<AnalysisView tab="categories" />)

    const detail = await screen.findByRole('region', { name: 'Alimentação fora' })
    expect(await within(detail).findByText('R$ 51,36')).toBeInTheDocument()
    expect(calls.some((c) => c.url === '/api/bff/analytics/categories/DINING_OUT')).toBe(true)
    expect(within(detail).getByRole('link', { name: 'Ver transações' }))
      .toHaveAttribute('href', '/transactions?category=DINING_OUT&period=CUSTOM&from=2026-09-01&to=2026-09-22')
    expect(screen.getByRole('link', { name: 'Sem categoria' })).toHaveAttribute('href', '/transactions?category=none')

    await user.click(screen.getByRole('button', { name: 'Moradia' }))
    expect(replace).toHaveBeenLastCalledWith('/analysis?category=HOUSING', { scroll: false })
    expect(await axeViolations(container)).toEqual([])
  })

  it('comerciantes: ranking, busca local e ordenação na URL', async () => {
    pathname = '/analysis/merchants'
    stubBff({ 'GET /api/bff/analytics/merchants': () => json(merchants()), 'GET /api/bff/categories': () => json(catalog) })
    const user = userEvent.setup()
    render(<AnalysisView tab="merchants" />)

    const table = await screen.findByRole('table', { name: /Comerciantes do mês/ })
    expect(within(table).getByText('+58% vs média de abr–ago')).toBeInTheDocument()
    expect(screen.getByText(/fora do ranking/)).toBeInTheDocument()

    await user.type(screen.getByLabelText('Buscar comerciante'), 'entrega')
    expect(within(table).queryByText('Imobiliária Exemplo')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Por frequência' }))
    expect(replace).toHaveBeenLastCalledWith('/analysis/merchants?sort=COUNT', { scroll: false })
  })

  it('evolução: tabela mês a mês e alternância 6/3 meses', async () => {
    pathname = '/analysis/evolution'
    stubBff({ 'GET /api/bff/analytics/evolution': () => json(evolution()), 'GET /api/bff/categories': () => json(catalog) })
    const user = userEvent.setup()
    render(<AnalysisView tab="evolution" />)

    const table = await screen.findByRole('table', { name: 'Mês a mês' })
    expect(within(table).getAllByRole('row')).toHaveLength(7)
    await user.click(screen.getByRole('button', { name: '3 meses' }))
    expect(replace).toHaveBeenLastCalledWith('/analysis/evolution?months=3', { scroll: false })
  })

  it('evolução: coluna e linha do score vêm do Health no mesmo período (ADR-0019 §6)', async () => {
    pathname = '/analysis/evolution'
    const calls = stubBff({
      'GET /api/bff/analytics/evolution': () => json(evolution()),
      'GET /api/bff/health/evolution': () => json(healthEvolution()),
      'GET /api/bff/categories': () => json(catalog),
    })
    render(<AnalysisView tab="evolution" />)

    const table = await screen.findByRole('table', { name: 'Mês a mês' })
    await waitFor(() => expect(within(table).getAllByRole('row')[1]).toHaveTextContent('≈ 91'))
    expect(within(table).getAllByRole('row').at(-1)).toHaveTextContent('84')
    expect(calls.some((c) => c.url === '/api/bff/health/evolution?month=2026-09&months=6')).toBe(true)
    expect(screen.getByRole('link', { name: 'Ver saúde' })).toHaveAttribute('href', '/home/health')
    expect(screen.getByRole('table', { name: 'Score mês a mês' })).toBeInTheDocument()
  })

  it('evolução: falha do Health não esconde os números da Análise', async () => {
    pathname = '/analysis/evolution'
    stubBff({ 'GET /api/bff/analytics/evolution': () => json(evolution()), 'GET /api/bff/categories': () => json(catalog) })
    render(<AnalysisView tab="evolution" />)

    const table = await screen.findByRole('table', { name: 'Mês a mês' })
    expect(await screen.findByText(/Não conseguimos carregar o score agora/)).toBeInTheDocument()
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('—')
  })
})

describe('/analysis — estados', () => {
  it('sem dados: convida a lançar ou importar', async () => {
    stubBff({ 'GET /api/bff/analytics/overview': () => json({ ...overview(), state: 'NO_DATA' }) })
    render(<AnalysisView tab="overview" />)

    expect(await screen.findByRole('heading', { name: 'Ainda não há o que analisar' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Importar extrato' })).toHaveAttribute('href', '/import')
  })

  it('carregando, erro com nova tentativa e mês inválido', async () => {
    let fail = true
    const calls = stubBff({
      'GET /api/bff/analytics/overview': () => (fail ? json({ code: 'UNAVAILABLE' }, 502) : json(overview())),
    })
    const user = userEvent.setup()
    render(<AnalysisView tab="overview" />)
    expect(screen.getByText('Carregando a análise…')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Não conseguimos carregar a análise' })).toBeInTheDocument()
    fail = false
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }))
    expect(await screen.findByText(/43,4% da renda/)).toBeInTheDocument()
    expect(calls.filter((c) => c.url.startsWith('/api/bff/analytics/overview'))).toHaveLength(2)
  })

  it('mês futuro: o backend recusa e a tela explica', async () => {
    stubBff({
      'GET /api/bff/analytics/overview': () => json({ code: 'VALIDATION_FAILED', details: [{ field: 'month', code: 'FUTURE' }] }, 400),
    })
    render(<AnalysisView tab="overview" />)
    expect(await screen.findByRole('heading', { name: 'Esse mês não pode ser analisado' })).toBeInTheDocument()
  })

  it('avisos de freshness e de média curta; mês sem movimento', async () => {
    stubBff({
      'GET /api/bff/analytics/overview': () => json(overview({
        state: 'NO_ACTIVITY_IN_PERIOD',
        freshness: { state: 'UPDATING', refreshedAt: null, pendingEvents: 2 },
        comparison: { ...meta().comparison, average: { requestedMonths: 5, monthsUsed: 1, months: ['2026-08'], state: 'LIMITED' } },
      })),
    })
    render(<AnalysisView tab="overview" />)

    expect(await screen.findByText(/Atualizando a análise/)).toBeInTheDocument()
    expect(screen.getByText('A média usa só 1 mês de histórico; compare com cuidado.')).toBeInTheDocument()
    expect(screen.getByText(/Nenhuma movimentação neste mês/)).toBeInTheDocument()
  })

  it('catálogo indisponível: mostra o código da categoria em vez de quebrar', async () => {
    stubBff({ 'GET /api/bff/analytics/overview': () => json(overview()) })
    render(<AnalysisView tab="overview" />)
    await waitFor(() => expect(screen.getAllByText('DINING_OUT').length).toBeGreaterThan(0))
  })
})
