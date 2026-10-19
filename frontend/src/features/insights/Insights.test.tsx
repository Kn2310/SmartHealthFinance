import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextStepLink } from '@/features/health/format'
import { readyHealth } from '@/features/health/fixtures'
import type { InsightAction } from '@/lib/api/types'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import { readyHighlights, readyList, related, reserveItem, SPIKE_ID, spikeDetail, spikeItem } from './fixtures'
import { actionHref, dataSourcesText, dayLabel, figureValue } from './format'
import { AttentionBlock, ChangesBlock, useHomeInsights } from './HomeInsights'
import { InsightDetailView } from './InsightDetailView'
import { InsightsBadge } from './InsightsBadge'
import { InsightsView } from './InsightsView'
import { RelatedInsight } from './RelatedInsight'

const replace = vi.fn()
let search = new URLSearchParams()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace }),
  usePathname: () => '/insights',
  useSearchParams: () => search,
}))

beforeEach(() => {
  replace.mockReset()
  search = new URLSearchParams()
})
afterEach(() => vi.unstubAllGlobals())

describe('Insights (D-Insights / M-Insights)', () => {
  it('chips com contagem (grupo com estado selecionado), Cartões "Em breve" e seções Importantes / Outros', async () => {
    const calls = stubBff({ 'GET /api/bff/insights': () => json(readyList()) })
    const { container } = render(<InsightsView />)

    const important = await screen.findByRole('heading', { name: 'Importantes' })
    expect(screen.getByRole('heading', { name: 'Outros insights' })).toBeInTheDocument()
    const chips = screen.getByRole('group', { name: 'Filtrar insights' })
    expect(within(chips).getByRole('button', { name: 'Todos 3' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(chips).getByRole('button', { name: 'Importantes 1' })).toHaveAttribute('aria-pressed', 'false')
    expect(within(chips).getByRole('button', { name: 'Cartões Em breve' })).toBeDisabled()
    expect(within(chips).getByRole('button', { name: 'Saúde 2' })).toBeInTheDocument()

    const section = important.closest('section')!
    const card = within(section).getByRole('link', {
      name: 'Gastos, importante: Seus gastos com alimentação fora aumentaram 32% neste mês, cerca de R$ 310 a mais. (novo)',
    })
    expect(card).toHaveAttribute('href', `/insights/${SPIKE_ID}`)
    expect(card).toHaveTextContent('✦ Insight · Gastos')
    expect(card).toHaveTextContent('Alimentação fora')
    expect(screen.getByText(/Setembro de 2026 \(em andamento\)/)).toBeInTheDocument()
    expect(calls.map((c) => c.url)).toEqual(['/api/bff/insights?pageSize=50'])
    expect(await axeViolations(container)).toEqual([])
  })

  it('filtro vai para a URL e muda a chamada', async () => {
    stubBff({ 'GET /api/bff/insights': () => json(readyList()) })
    render(<InsightsView />)
    await userEvent.click(await screen.findByRole('button', { name: /Importantes/ }))
    expect(replace).toHaveBeenCalledWith('/insights?filter=IMPORTANT', { scroll: false })

    search = new URLSearchParams('filter=HEALTH')
    const calls = stubBff({ 'GET /api/bff/insights': () => json(readyList({ items: [reserveItem()] })) })
    render(<InsightsView />)
    expect(await screen.findByRole('heading', { name: 'Insights' , level: 2 })).toBeInTheDocument()
    expect(calls[0]?.url).toBe('/api/bff/insights?pageSize=50&type=HEALTH')
  })

  it('estados: sem dados, histórico curto, vazio e erro com nova tentativa', async () => {
    stubBff({ 'GET /api/bff/insights': () => json(readyList({ state: 'NO_DATA', items: [], totalItems: 0 })) })
    const { unmount } = render(<InsightsView />)
    expect(await screen.findByText('Seus insights aparecem aqui')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Importar extrato' })).toHaveAttribute('href', '/import')
    unmount()

    stubBff({ 'GET /api/bff/insights': () => json(readyList({ state: 'INSUFFICIENT_HISTORY', items: [] })) })
    const second = render(<InsightsView />)
    expect(await screen.findByText('Ainda é cedo para comparar')).toBeInTheDocument()
    second.unmount()

    stubBff({ 'GET /api/bff/insights': () => json(readyList({ items: [], totalItems: 0 })) })
    const third = render(<InsightsView />)
    expect(await screen.findByText('Nenhum insight por aqui')).toBeInTheDocument()
    third.unmount()

    let attempts = 0
    stubBff({
      'GET /api/bff/insights': () => (++attempts === 1 ? json({ code: 'UNAVAILABLE' }, 502) : json(readyList())),
    })
    render(<InsightsView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Tentar novamente' }))
    expect(await screen.findByRole('heading', { name: 'Importantes' })).toBeInTheDocument()
  })
})

describe('Detalhe do insight (D-InsightDetail / M-InsightDetail)', () => {
  it('título = a frase; resumo, evidências com tabela, explicação, mês em andamento, fontes e ações', async () => {
    const calls = stubBff({
      [`GET /api/bff/insights/${SPIKE_ID}`]: () => json(spikeDetail()),
      [`POST /api/bff/insights/${SPIKE_ID}/seen`]: () => json(spikeItem({ seen: true })),
    })
    const { container } = render(<InsightDetailView id={SPIKE_ID} />)

    expect(await screen.findByRole('heading', { level: 1, name: /aumentaram 32% neste mês/ })).toBeInTheDocument()
    expect(screen.getByText('✦ Insight')).toBeInTheDocument()
    expect(screen.getByText('Setembro (1–22)').nextElementSibling).toHaveTextContent('R$ 1.284,00')
    expect(screen.getByText('Variação').nextElementSibling).toHaveTextContent('+32%')
    expect(screen.getByText('Ver dados de “Evolução mensal: Alimentação fora”')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Onde foi o gasto' })).toHaveTextContent('ENTREGA JA')
    expect(screen.getByText('Setembro ainda não terminou; o valor pode mudar até o dia 30.')).toBeInTheDocument()
    expect(screen.getByText('Análise › categorias · 01/04/2026 a 22/09/2026')).toBeInTheDocument()
    expect(screen.getByText('Lançamentos de origem CSV e OFX')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver as 4 transações' })).toHaveAttribute('href',
      '/transactions?period=CUSTOM&from=2026-09-01&to=2026-09-22&type=EXPENSE&category=DINING_OUT')
    expect(screen.getByRole('link', { name: 'Ver categoria' })).toHaveAttribute('href',
      '/analysis/categories?month=2026-09&category=DINING_OUT')
    expect(screen.getByRole('button', { name: 'Criar objetivo de economia · Em breve' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Perguntar ao Copilot · Em breve' })).toBeDisabled()
    expect(screen.getByText('Confiança alta')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Gastos · 71 na saúde financeira/ })).toHaveAttribute('href', '/home/health')
    // Abrir marca como visto (POST explícito; o GET não escreve).
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/seen'))).toBe(true))
    expect(await axeViolations(container)).toEqual([])
  })

  it('feedback: "Útil" fica pressionado; clicar de novo remove', async () => {
    const bodies: unknown[] = []
    stubBff({
      [`GET /api/bff/insights/${SPIKE_ID}`]: () => json(spikeDetail({ insight: spikeItem({ seen: true }) })),
      [`PUT /api/bff/insights/${SPIKE_ID}/feedback`]: (init) => {
        const body = JSON.parse(String(init?.body)) as { value: string | null }
        bodies.push(body)
        return json(spikeItem({ seen: true, feedback: body.value as 'USEFUL' | null }))
      },
    })
    render(<InsightDetailView id={SPIKE_ID} />)
    const useful = await screen.findByRole('button', { name: 'Útil' })
    await userEvent.click(useful)
    await waitFor(() => expect(useful).toHaveAttribute('aria-pressed', 'true'))
    await userEvent.click(useful)
    await waitFor(() => expect(useful).toHaveAttribute('aria-pressed', 'false'))
    expect(bodies).toEqual([{ value: 'USEFUL' }, { value: null }])
  })

  it('dispensar pede confirmação e o insight deixa de estar ativo', async () => {
    const calls = stubBff({
      [`GET /api/bff/insights/${SPIKE_ID}`]: () => json(spikeDetail({ insight: spikeItem({ seen: true }) })),
      [`POST /api/bff/insights/${SPIKE_ID}/dismiss`]: () => json(spikeItem({ seen: true, status: 'DISMISSED' })),
    })
    render(<InsightDetailView id={SPIKE_ID} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Dispensar insight' }))
    expect(screen.getByText('Dispensar este insight? Ele não volta a aparecer.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)

    await userEvent.click(screen.getByRole('button', { name: 'Dispensar insight' }))
    await userEvent.click(screen.getByRole('button', { name: 'Dispensar' }))
    expect(await screen.findByText('Dispensado')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Dispensar insight' })).not.toBeInTheDocument()
  })

  it('insight inexistente ou de outro Workspace', async () => {
    stubBff({ [`GET /api/bff/insights/${SPIKE_ID}`]: () => json({ code: 'INSIGHT_NOT_FOUND' }, 404) })
    render(<InsightDetailView id={SPIKE_ID} />)
    expect(await screen.findByText('Insight não encontrado')).toBeInTheDocument()
    render(<InsightDetailView id={null} />)
    expect(screen.getAllByText('Insight não encontrado')).toHaveLength(2)
  })
})

function Home() {
  const highlights = useHomeInsights()
  return (
    <>
      <AttentionBlock highlights={highlights} />
      <ChangesBlock highlights={highlights} />
    </>
  )
}

describe('Home: "Merece atenção" e "O que mudou?"', () => {
  it('uma chamada para os dois blocos; linhas e cards levam ao detalhe', async () => {
    const calls = stubBff({ 'GET /api/bff/insights/highlights': () => json(readyHighlights()) })
    const { container } = render(<Home />)
    const change = await screen.findByRole('link', { name: /Alimentação fora R\$ 1.284,00 até 22 set/ })
    expect(change).toHaveAttribute('href', `/insights/${SPIKE_ID}`)
    expect(change).toHaveTextContent('+32% vs média de abr–ago')
    expect(screen.getByText('1 insight importante')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver todos' })).toHaveAttribute('href', '/insights')
    expect(screen.getByRole('link', { name: /Gastos, importante/ })).toBeInTheDocument()
    expect(calls).toHaveLength(1)
    expect(await axeViolations(container)).toEqual([])
  })

  it('sem mudanças relevantes, histórico curto e erro', async () => {
    stubBff({ 'GET /api/bff/insights/highlights': () => json(readyHighlights({ attention: [], changes: [], importantCount: 0 })) })
    const { unmount } = render(<Home />)
    expect(await screen.findByText('Nada mudou de forma relevante no período.')).toBeInTheDocument()
    expect(screen.getByText('Nada pede sua atenção agora. Avisamos quando algo mudar.')).toBeInTheDocument()
    unmount()

    stubBff({ 'GET /api/bff/insights/highlights': () => json(readyHighlights({ state: 'INSUFFICIENT_HISTORY', changes: [], attention: [] })) })
    const second = render(<Home />)
    expect(await screen.findAllByText('Os insights aparecem quando houver pelo menos 2 meses de histórico.')).toHaveLength(2)
    second.unmount()

    stubBff({ 'GET /api/bff/insights/highlights': () => json({ code: 'UNAVAILABLE' }, 502) })
    render(<Home />)
    expect(await screen.findAllByRole('button', { name: 'Tentar novamente' })).toHaveLength(2)
  })
})

describe('Integrações', () => {
  it('"Ver insight relacionado" só aparece quando há insight para a transação', async () => {
    stubBff({ 'GET /api/bff/insights/related': () => json(related()) })
    const { unmount } = render(<RelatedInsight transactionId="0192b1f0-0000-7000-8000-0000000000aa" />)
    expect(await screen.findByRole('link', { name: 'Ver insight relacionado' })).toHaveAttribute('href', `/insights/${SPIKE_ID}`)
    unmount()

    stubBff({ 'GET /api/bff/insights/related': () => json(related([])) })
    const { container } = render(<RelatedInsight transactionId="0192b1f0-0000-7000-8000-0000000000aa" />)
    await waitFor(() => expect(container).toBeEmptyDOMElement())
  })

  it('próximo passo da categoria leva ao insight da alta no mesmo mês; sem ele, à Análise', () => {
    const step = readyHealth().nextSteps.find((s) => s.kind === 'REVIEW_CATEGORY')!
    expect(nextStepLink(step, [spikeItem()])).toEqual({ href: `/insights/${SPIKE_ID}`, label: 'Ver insight' })
    expect(nextStepLink(step, [spikeItem({ period: { month: '2026-08', partial: false } })]).label).toBe('Ver categoria')
    expect(nextStepLink(step).href).toBe('/analysis/categories?month=2026-09&category=DINING_OUT')
  })

  it('contagem de Importantes na navegação some sem Importantes', async () => {
    stubBff({ 'GET /api/bff/insights/highlights': () => json(readyHighlights({ importantCount: 2 })) })
    const { unmount } = render(<InsightsBadge variant="text" />)
    expect(await screen.findByText('2 importantes')).toBeInTheDocument()
    unmount()

    stubBff({ 'GET /api/bff/insights/highlights': () => json(readyHighlights({ importantCount: 0 })) })
    const { container } = render(<InsightsBadge />)
    await waitFor(() => expect(container).toBeEmptyDOMElement())
  })
})

describe('format', () => {
  const action = (kind: InsightAction['kind'], params: Record<string, string> = {}, available = true): InsightAction =>
    ({ kind, label: '', primary: false, available, params })

  it('rotas das ações só para telas que existem', () => {
    expect(actionHref(action('VIEW_ANALYSIS', { month: '2026-09' }))).toBe('/analysis?month=2026-09')
    expect(actionHref(action('VIEW_MERCHANTS', { month: '2026-09' }))).toBe('/analysis/merchants?month=2026-09')
    expect(actionHref(action('VIEW_INCOME', { month: '2026-09' }))).toBe('/analysis/income?month=2026-09')
    expect(actionHref(action('VIEW_TRANSACTION', { transactionId: 'abc' }))).toBe('/transactions/abc')
    expect(actionHref(action('IMPORT_STATEMENT'))).toBe('/import')
    expect(actionHref(action('VIEW_HEALTH'))).toBe('/home/health')
    expect(actionHref(action('VIEW_ACCOUNTS'))).toBe('/accounts')
    expect(actionHref(action('CATEGORIZE', { category: 'none' }))).toBe('/transactions?category=none')
    expect(actionHref(action('CREATE_SAVINGS_GOAL', {}, false))).toBeNull()
    expect(actionHref(action('ASK_COPILOT', {}, false))).toBeNull()
  })

  it('datas, valores e origens', () => {
    const now = new Date(2026, 8, 22, 15)
    expect(dayLabel(new Date(2026, 8, 22, 9).toISOString(), now)).toBe('Hoje')
    expect(dayLabel(new Date(2026, 8, 21, 9).toISOString(), now)).toBe('Ontem')
    expect(dayLabel(new Date(2026, 8, 20, 9).toISOString(), now)).toBe('20 de set')
    const fig = (kind: 'MONEY' | 'PERCENT' | 'MONTHS' | 'RATIO' | 'POINTS', value: string) =>
      ({ code: 'x', label: 'x', kind, value, from: '2026-09-01', to: '2026-09-22' })
    expect(figureValue(fig('MONEY', '15152.00'))).toBe('R$ 15.152,00')
    expect(figureValue(fig('PERCENT', '-4.5'))).toBe('-4,5%')
    expect(figureValue(fig('MONTHS', '2.39'))).toBe('2,4 meses')
    expect(figureValue(fig('RATIO', '4.0'))).toBe('4,0×')
    expect(figureValue(fig('POINTS', '84'))).toBe('84')
    expect(dataSourcesText(['CSV', 'MANUAL', 'OFX'])).toBe('CSV, manual e OFX')
    expect(dataSourcesText([])).toBeNull()
  })
})
