import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { arcPath } from '@/components/charts/ScoreRing'
import { scoreDomain } from '@/components/charts/ScoreLine'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import { evolution, method, noDataHealth, readyHealth } from './fixtures'
import { curvePoint, nextStepLink, sourcesText, updatedAgo, variationHeadline, variationShort } from './format'
import { HealthCard } from './HealthCard'
import { HealthView } from './HealthView'

afterEach(() => vi.unstubAllGlobals())

describe('"Como estou?" na Home (D-Home / M-Home)', () => {
  it('score nunca sozinho: ring, variação, frase, 6 dimensões, base e "Como calculamos?"', async () => {
    const calls = stubBff({ 'GET /api/bff/health/summary': () => json(readyHealth()) })
    const { container } = render(<HealthCard />)

    expect(await screen.findByRole('img', { name: 'Score 84 de 100' })).toBeInTheDocument()
    expect(screen.getByText('Seu score subiu 3 pontos desde agosto')).toBeInTheDocument()
    expect(screen.getByText(/Consistência \(100\) e fluxo de caixa \(97\) sustentam o score/)).toBeInTheDocument()
    const grid = screen.getByRole('list', { name: 'Dimensões' })
    expect(within(grid).getAllByRole('listitem')).toHaveLength(6)
    expect(within(grid).getAllByText('Em breve')).toHaveLength(2)
    expect(within(grid).getByText('Reserve').parentElement).toHaveTextContent('69')
    expect(screen.getByRole('link', { name: 'Entender meu resultado' })).toHaveAttribute('href', '/home/health')
    expect(screen.getByRole('button', { name: 'Como calculamos?' })).toBeInTheDocument()
    expect(screen.getByText(/Base: abr–set/)).toBeInTheDocument()
    // Sempre o mês corrente: nenhum parâmetro de período (não segue o seletor da Home).
    expect(calls.map((c) => c.url)).toEqual(['/api/bff/health/summary'])
    expect(await axeViolations(container)).toEqual([])
  })

  it('estimado: "≈" no número e selo de estimativa', async () => {
    stubBff({ 'GET /api/bff/health/summary': () => json(readyHealth({ estimated: true })) })
    render(<HealthCard />)
    expect(await screen.findByRole('img', { name: 'Score estimado 84 de 100' })).toHaveTextContent('≈84')
    expect(screen.getByText('≈ Estimado · menos de 3 meses fechados')).toBeInTheDocument()
  })

  it('recalculando: mostra o último snapshot e avisa que está atualizando', async () => {
    stubBff({
      'GET /api/bff/health/summary': () =>
        json(readyHealth({ freshness: { state: 'RECALCULATING', computedAt: null, analyticsRefreshedAt: null } })),
    })
    render(<HealthCard />)
    expect(await screen.findByText('Atualizando…')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Score 84 de 100' })).toBeInTheDocument()
  })

  it('sem dados: o card continua lá e explica quando o score aparece', async () => {
    stubBff({ 'GET /api/bff/health/summary': () => json(noDataHealth()) })
    render(<HealthCard />)
    expect(await screen.findByText('Seu score aparece em breve')).toBeInTheDocument()
    expect(screen.getByText(/depois do primeiro mês fechado/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Entender meu resultado' })).not.toBeInTheDocument()
  })

  it('dados insuficientes: sem score, com as dimensões disponíveis', async () => {
    const insufficient = readyHealth({ state: 'INSUFFICIENT_DATA', score: null, summary: null, variation: null,
      coverage: { available: 1, total: 6, weightPercent: 25 } })
    stubBff({ 'GET /api/bff/health/summary': () => json(insufficient) })
    render(<HealthCard />)
    expect(await screen.findByText('Ainda não dá para calcular o score')).toBeInTheDocument()
    expect(screen.getByText(/1 de 6 dimensões têm nota/)).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Sem score ainda' })).toBeInTheDocument()
  })

  it('erro: mensagem sem jargão e nova tentativa', async () => {
    let fail = true
    stubBff({
      'GET /api/bff/health/summary': () => (fail ? json({ code: 'UNAVAILABLE' }, 502) : json(readyHealth())),
    })
    render(<HealthCard />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Seus dados estão seguros')
    fail = false
    await userEvent.click(screen.getByRole('button', { name: 'Tentar novamente' }))
    expect(await screen.findByRole('img', { name: 'Score 84 de 100' })).toBeInTheDocument()
  })
})

describe('/home/health (D-Health / M-Health)', () => {
  const routes = () => ({
    'GET /api/bff/health/summary': () => json(readyHealth()),
    'GET /api/bff/health/evolution': () => json(evolution()),
    'GET /api/bff/health/method': () => json(method()),
  })

  it('resumo, fontes, dimensões 3×2, fatores neutros e próximos passos só para telas existentes', async () => {
    const calls = stubBff(routes())
    const { container } = render(<HealthView />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Saúde financeira' })).toBeInTheDocument()
    expect(await screen.findByText('84 de 100')).toBeInTheDocument()
    expect(screen.getByText('+3 pontos vs agosto')).toBeInTheDocument()
    expect(screen.getByText(/Confiança média · 5 meses fechados de 3 contas · 4 de 6 dimensões \(70% do peso\)/))
      .toBeInTheDocument()
    expect(screen.getByText('OFX, CSV e entrada manual')).toBeInTheDocument()
    expect(screen.getByText('Setembro ainda está em andamento.')).toBeInTheDocument()

    const dimensions = screen.getByRole('heading', { name: 'Dimensões' }).closest('section')!
    expect(within(dimensions).getAllByRole('article')).toHaveLength(6)
    expect(within(dimensions).getAllByText('Ponto de atenção')).toHaveLength(2)
    expect(within(dimensions).getAllByText('Em breve')).toHaveLength(2)

    expect(screen.getByText('Sustentando o resultado')).toBeInTheDocument()
    expect(screen.getByText('Pesando no resultado')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver contas' })).toHaveAttribute('href', '/accounts')
    expect(screen.getByRole('link', { name: 'Ver categoria' })).toHaveAttribute('href',
      '/analysis/categories?month=2026-09&category=DINING_OUT')
    expect(screen.queryByText(/✦/)).not.toBeInTheDocument()

    expect(await screen.findByRole('table', { name: /Evolução do score/ })).toBeInTheDocument()
    expect(calls.some((c) => c.url === '/api/bff/health/evolution?months=6')).toBe(true)
    expect(calls.some((c) => c.url.startsWith('/api/bff/health/method'))).toBe(false)
    expect(await axeViolations(container)).toEqual([])
  })

  it('"Detalhes" expande as evidências no próprio card', async () => {
    stubBff(routes())
    render(<HealthView />)
    const reserve = (await screen.findByRole('heading', { name: 'Reserva' })).closest('article')!
    const toggle = within(reserve).getByRole('button', { name: 'Detalhes' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(within(reserve).queryByText('R$ 10.000,00')).not.toBeVisible()

    await userEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(within(reserve).getByText('R$ 10.000,00')).toBeVisible()
    expect(within(reserve).getByText('2,4 meses')).toBeVisible()
    expect(within(reserve).getAllByText(/Contas › saldos/)).toHaveLength(2)
  })

  it('sem dados: estado vazio que explica e oferece importar', async () => {
    stubBff({ ...routes(), 'GET /api/bff/health/summary': () => json(noDataHealth()) })
    render(<HealthView />)
    expect(await screen.findByRole('heading', { name: 'Seu score ainda não tem dados' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Importar extrato' })).toHaveAttribute('href', '/import')
  })

  it('"Como calculamos?": diálogo com foco preso, Esc fecha e o foco volta ao gatilho', async () => {
    stubBff(routes())
    const user = userEvent.setup()
    render(<HealthView />)
    const trigger = await screen.findByRole('button', { name: 'Como calculamos?' })

    await user.click(trigger)
    const dialog = await screen.findByRole('dialog', { name: 'Como calculamos?' })
    expect(await within(dialog).findByText('Média ponderada das notas das dimensões disponíveis.')).toBeInTheDocument()
    expect(within(dialog).getByText(/peso 25/)).toBeInTheDocument()
    expect(within(dialog).getByText(/-10% → 0/)).toBeInTheDocument()
    expect(within(dialog).getByText('Investimentos')).toBeInTheDocument()

    const close = within(dialog).getByRole('button', { name: 'Fechar' })
    expect(close).toHaveFocus()
    await user.tab({ shift: true })
    expect(dialog.contains(document.activeElement)).toBe(true)

    await user.keyboard('{Escape}')
    await waitFor(() => expect(dialog).not.toHaveAttribute('open'))
    expect(trigger).toHaveFocus()

    // Reabre o mesmo diálogo (fica montado; o navegador devolve o foco a quem abriu).
    await user.click(trigger)
    await waitFor(() => expect(dialog).toHaveAttribute('open'))
    await user.click(within(dialog).getByRole('button', { name: 'Fechar' }))
    await waitFor(() => expect(dialog).not.toHaveAttribute('open'))
    expect(trigger).toHaveFocus()
  })
})

describe('formatação do Health', () => {
  it('variação neutra e manchete', () => {
    expect(variationShort(3, '2026-09')).toBe('+3 pontos vs agosto')
    expect(variationShort(-1, '2026-01')).toBe('−1 ponto vs dezembro')
    expect(variationShort(0, '2026-09')).toBe('Igual a agosto')
    expect(variationShort(null, '2026-09')).toBeNull()
    expect(variationHeadline(-2, '2026-09')).toBe('Seu score caiu 2 pontos desde agosto')
  })

  it('links dos próximos passos só para telas existentes', () => {
    const step = readyHealth().nextSteps[0]!
    expect(nextStepLink(step)).toEqual({ href: '/accounts', label: 'Ver contas' })
    expect(nextStepLink({ ...step, kind: 'ADD_RESERVE_ACCOUNT' })).toEqual({ href: '/accounts/new', label: 'Adicionar conta' })
    expect(nextStepLink({ ...step, kind: 'CATEGORIZE_TRANSACTIONS' }).href).toBe('/transactions?category=none')
    expect(nextStepLink({ ...step, kind: 'REVIEW_MONTH', month: '2026-08' }).href).toBe('/analysis?month=2026-08')
  })

  it('fontes, atualização e eixos das curvas', () => {
    expect(sourcesText(['MANUAL', 'CSV', 'OFX'])).toBe('OFX, CSV e entrada manual')
    expect(sourcesText(['MANUAL'])).toBe('entrada manual')
    expect(sourcesText([])).toBeNull()
    const now = new Date('2026-09-22T12:12:00Z')
    expect(updatedAgo('2026-09-22T12:00:00Z', now)).toBe('há 12 min')
    expect(updatedAgo('2026-09-22T12:11:40Z', now)).toBe('agora há pouco')
    expect(updatedAgo(null, now)).toBeNull()
    expect(curvePoint('CASH_FLOW', '-0.1')).toBe('-10%')
    expect(curvePoint('RESERVE', '1')).toBe('1 mês')
  })

  it('geometria do ring e do eixo da linha', () => {
    expect(arcPath(25)).toBe('M50,4.5 A45.5,45.5 0 0 1 95.5,50')
    expect(arcPath(100)).toContain('A45.5,45.5 0 1 1 50,95.5')
    expect(scoreDomain([66, 74])).toEqual([60, 80])
    expect(scoreDomain([97, 100])).toEqual([80, 100])
    expect(scoreDomain([])).toEqual([0, 100])
  })
})
