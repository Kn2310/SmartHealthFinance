import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import { AiResponse, plainText } from './AiResponse'
import { actionHref } from './links'
import { answer, CARD, GOAL, INSIGHT, plain, REQUEST } from './fixtures'
import type { AnswerAction } from './api'

afterEach(() => vi.unstubAllGlobals())

const action = (kind: AnswerAction['kind'], targetId: string | null = null, params: Record<string, string> = {}): AnswerAction => ({
  kind,
  label: 'x',
  targetId,
  targetName: null,
  params,
})

describe('AI Response — renderização por segmentos tipados (ADR-0041 §2)', () => {
  it('texto, valores em negrito com Estimado, quadro com total, explicação, avisos, confiança e dados usados', async () => {
    const { container } = render(<AiResponse response={answer()} historic={false} />)

    const article = screen.getByRole('article', { name: 'Resposta do Copilot' })
    expect(article).toHaveTextContent('Sua fatura de outubro está em R$ 3.412,80, R$ 520,60Estimado acima da média.')
    expect(screen.getByText('R$ 3.412,80').tagName).toBe('STRONG')
    expect(screen.getByText('Estimado')).toBeInTheDocument()

    const table = screen.getByRole('table', { name: 'O que explica a diferença' })
    const rows = within(table).getAllByRole('row')
    expect(rows.map((row) => row.textContent)).toEqual([
      'FatorValor',
      'Alimentação fora de casaR$ 1.284 vs R$ 973+ R$ 311,00',
      'Parcela do notebook+ R$ 160,00Simulado',
      'Total+ R$ 471,00',
    ])

    const how = screen.getByText('Como cheguei a isso').closest('details')!
    expect(how).not.toHaveAttribute('open')
    expect(how).toHaveTextContent('Comparei cada categoria com a média de R$ 2.892,20.')

    const notices = within(screen.getByRole('list', { name: 'Avisos' })).getAllByRole('listitem')
    expect(notices.map((n) => n.textContent)).toEqual([
      'Período parcial: o valor ainda pode mudar.',
      'Inclui valores estimados (≈).',
    ])
    expect(screen.getByText('Confiança alta')).toBeInTheDocument()
    const used = within(screen.getByRole('list', { name: 'Dados usados' })).getAllByRole('listitem')
    expect(used.map((n) => n.textContent)).toEqual(['Fatura out/2026 · parcial', 'Faturas jul–set'])
    expect(await axeViolations(container)).toEqual([])
  })

  it('HTML e markdown do servidor aparecem como texto: nada é interpretado', async () => {
    const hostile = answer({
      answer: {
        ...answer().answer,
        segments: [
          { kind: 'TEXT', text: '<script>window.__pwned = 1</script><img src=x onerror="window.__pwned = 2"> **negrito** [link](http://evil.test)' },
          { kind: 'VALUE', ref: 'v1' },
        ],
        values: [{ ref: 'v1', label: 'x', formatted: '<b>R$ 1,00</b>', kind: 'FACT', period: null, source: 's' }],
        explanation: [[{ kind: 'TEXT', text: '<iframe src="http://evil.test"></iframe>' }]],
        breakdown: null,
        actions: [],
      },
    })
    const { container } = render(<AiResponse response={hostile} historic={false} />)

    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('iframe')).toBeNull()
    expect(container.querySelector('b')).toBeNull()
    expect(container.querySelector('a[href="http://evil.test"]')).toBeNull()
    expect(screen.getByRole('article')).toHaveTextContent('<script>window.__pwned = 1</script><img src=x onerror="window.__pwned = 2"> **negrito** [link](http://evil.test)<b>R$ 1,00</b>')
    expect((window as unknown as { __pwned?: number }).__pwned).toBeUndefined()
  })

  it('valor com ref inexistente é omitido; sem quadro, explicação, confiança ou dados usados, nada disso aparece', () => {
    render(
      <AiResponse
        response={plain('INSUFFICIENT_DATA', 'Seus dados começam em abril.')}
        historic={false}
      />,
    )
    expect(screen.getByText('Seus dados começam em abril.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByText('Como cheguei a isso')).not.toBeInTheDocument()
    expect(screen.queryByText(/Confiança/)).not.toBeInTheDocument()
    expect(screen.queryByText('Dados usados')).not.toBeInTheDocument()
    // Feedback só em OK e modo seguro.
    expect(screen.queryByRole('button', { name: 'Resposta útil' })).not.toBeInTheDocument()
  })

  it('modo seguro mostra o aviso e aceita feedback; resposta aberta do histórico mostra a data', () => {
    render(<AiResponse response={answer({ code: 'SAFE_MODE', notices: [] })} historic />)
    expect(screen.getByText('Resposta simplificada: mostro só os números calculados pelo sistema.')).toBeInTheDocument()
    expect(screen.getByText('Resposta de 22 set. Os números podem ter mudado desde então.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Resposta útil' })).toBeInTheDocument()
  })

  it('Copiar usa o texto plano da resposta e mostra "Copiado"', async () => {
    const user = userEvent.setup()
    render(<AiResponse response={answer()} historic={false} />)
    await user.click(screen.getByRole('button', { name: 'Copiar' }))
    expect(await navigator.clipboard.readText()).toBe('Sua fatura de outubro está em R$ 3.412,80, R$ 520,60 (estimado) acima da média.')
    expect(await screen.findByText('Copiado')).toBeInTheDocument()
    expect(plainText(answer().answer)).not.toMatch(/<|\*\*/)
  })

  it('feedback: útil → obrigado; não útil → motivos opcionais, e o motivo é enviado depois', async () => {
    const user = userEvent.setup()
    const calls = stubBff({ [`PUT /api/bff/copilot/questions/${REQUEST}/feedback`]: () => json({ saved: true }) })
    const { container } = render(<AiResponse response={answer()} historic={false} />)

    const helpful = screen.getByRole('button', { name: 'Resposta útil' })
    const notHelpful = screen.getByRole('button', { name: 'Resposta não útil' })
    expect(helpful).toHaveAttribute('aria-pressed', 'false')
    await user.click(helpful)
    expect(await screen.findByText('Obrigado pelo retorno')).toBeInTheDocument()
    expect(helpful).toHaveAttribute('aria-pressed', 'true')
    expect(JSON.parse(String(calls[0]!.init?.body))).toEqual({ rating: 'HELPFUL' })
    expect(screen.queryByRole('group', { name: 'Motivo' })).not.toBeInTheDocument()

    await user.click(notHelpful)
    const reasons = await screen.findByRole('group', { name: 'Motivo' })
    expect(within(reasons).getAllByRole('button').map((b) => b.textContent)).toEqual([
      'Números errados',
      'Não respondeu',
      'Confuso',
      'Outro',
    ])
    expect(JSON.parse(String(calls[1]!.init?.body))).toEqual({ rating: 'NOT_HELPFUL' })
    await user.click(within(reasons).getByRole('button', { name: 'Confuso' }))
    await waitFor(() => expect(within(reasons).getByRole('button', { name: 'Confuso' })).toHaveAttribute('aria-pressed', 'true'))
    expect(JSON.parse(String(calls[2]!.init?.body))).toEqual({ rating: 'NOT_HELPFUL', reason: 'CONFUSING' })
    expect(await axeViolations(container)).toEqual([])
  })

  it('falha no feedback avisa e não marca o botão', async () => {
    const user = userEvent.setup()
    stubBff({ 'PUT /api/bff/copilot/questions/': () => json({ code: 'UNAVAILABLE' }, 502) })
    render(<AiResponse response={answer()} historic={false} />)
    await user.click(screen.getByRole('button', { name: 'Resposta útil' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Não conseguimos registrar agora. Tente de novo.')
    expect(screen.getByRole('button', { name: 'Resposta útil' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('ações viram links para as telas existentes; a primeira é a primária', () => {
    render(<AiResponse response={answer()} historic={false} />)
    expect(screen.getByRole('link', { name: 'Ver fatura' })).toHaveAttribute('href', `/cards/${CARD}/invoices/2026-10`)
    expect(screen.getByRole('link', { name: 'Ver parcelamentos' })).toHaveAttribute('href', `/cards/${CARD}?tab=installments`)
  })
})

describe('rota de cada ação (links.ts)', () => {
  it.each([
    [action('OPEN_INVOICE', CARD, { month: '2026-10' }), `/cards/${CARD}/invoices/2026-10`],
    [action('OPEN_INVOICE', CARD), `/cards/${CARD}/invoice`],
    [action('OPEN_INVOICE', CARD, { month: '../x' }), `/cards/${CARD}/invoice`],
    [action('OPEN_INSTALLMENTS', CARD), `/cards/${CARD}?tab=installments`],
    [action('OPEN_HEALTH'), '/home/health'],
    [action('OPEN_INSIGHT', INSIGHT), `/insights/${INSIGHT}`],
    [action('OPEN_GOAL', GOAL), `/goals/${GOAL}`],
    [action('OPEN_ANALYSIS_CATEGORY', 'DINING_OUT', { month: '2026-09' }), '/analysis/categories?month=2026-09&category=DINING_OUT'],
    [action('OPEN_ANALYSIS_CATEGORY', null), '/analysis/categories'],
    [action('OPEN_ANALYSIS_CATEGORY', 'dining out'), '/analysis/categories'],
    [action('OPEN_FORECAST'), '/home#events'],
  ])('%j → %s', (input, href) => {
    expect(actionHref(input)).toBe(href)
  })

  it('ids que não são UUID e tipos desconhecidos não têm rota', () => {
    expect(actionHref(action('OPEN_INVOICE', 'abc'))).toBeNull()
    expect(actionHref(action('OPEN_INVOICE'))).toBeNull()
    expect(actionHref(action('OPEN_GOAL', '../../etc'))).toBeNull()
    expect(actionHref(action('OPEN_INSIGHT'))).toBeNull()
    expect(actionHref({ ...action('OPEN_HEALTH'), kind: 'OPEN_UNKNOWN' as never })).toBeNull()
  })
})
