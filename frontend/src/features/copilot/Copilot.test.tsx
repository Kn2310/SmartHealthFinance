import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import { ConversationView } from './ConversationView'
import { CopilotView } from './CopilotView'
import { parseEntry } from './entry'
import {
  answer,
  CARD,
  CONVERSATION,
  conversation,
  GOAL,
  INSIGHT,
  page,
  PLAN,
  plain,
  summary,
  usage,
} from './fixtures'
import { FailurePanel, LoadingPanel } from './parts'

const replace = vi.fn()
const push = vi.fn()
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace }),
  usePathname: () => '/copilot',
  useSearchParams: () => new URLSearchParams(),
}))

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const body = (init: RequestInit | undefined) => JSON.parse(String(init?.body)) as Record<string, unknown>
const headers = (init: RequestInit | undefined) => init?.headers as Record<string, string>
const asks = (calls: { method: string; url: string; init?: RequestInit }[]) =>
  calls.filter((c) => c.method === 'POST' && c.url === '/api/bff/copilot/questions')

const base = () => ({
  'GET /api/bff/copilot/usage': () => json(usage()),
  'GET /api/bff/copilot/conversations': () => json(page()),
})

beforeEach(() => {
  replace.mockClear()
  push.mockClear()
})
afterEach(() => vi.unstubAllGlobals())

async function ask(user: ReturnType<typeof userEvent.setup>, question: string) {
  await user.type(screen.getByRole('textbox', { name: 'Pergunta para o Copilot' }), question)
  await user.click(screen.getByRole('button', { name: 'Enviar pergunta' }))
}

describe('/copilot — D-Copilot, M-Copilot (início)', () => {
  it('mostra a pergunta, o escopo de dados, as sugestões, o que faz e não faz, o histórico e o uso no mês', async () => {
    stubBff(base())
    const { container } = render(<CopilotView entry={null} />)

    expect(await screen.findByRole('heading', { level: 1, name: 'O que você quer entender sobre seu dinheiro?' })).toBeInTheDocument()
    expect(await screen.findByText(/Respostas com base nos seus dados importados \(abr a set\)\. O Copilot explica e sugere — as decisões são sempre suas\./)).toBeInTheDocument()
    const suggestions = screen.getByRole('region', { name: 'Sugestões para você' })
    expect(within(suggestions).getAllByRole('button').map((b) => b.textContent)).toEqual([
      '✦Por que minha fatura está mais alta este mês?',
      '✦Quanto já comprometi nas próximas faturas?',
      '✦Estou no caminho da minha reserva?',
      '✦Onde mais gastei este mês?',
    ])
    expect(screen.getByRole('region', { name: 'O que o Copilot faz' })).toHaveTextContent('Mostra os dados usados em cada resposta')
    expect(screen.getByRole('region', { name: 'O que ele não faz' })).toHaveTextContent('Decidir por você')
    expect(screen.getByText('O Copilot pode errar. Confira os dados usados em cada resposta.')).toBeInTheDocument()

    const history = await screen.findByRole('link', { name: /Quanto gastei com transporte em agosto\?/ })
    expect(history).toHaveAttribute('href', `/copilot/${CONVERSATION}`)
    expect(await screen.findByText('6 de 100 perguntas')).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(await axeViolations(container)).toEqual([])
  })

  it('primeiro uso: sem perguntas no mês e sem histórico', async () => {
    stubBff({
      'GET /api/bff/copilot/usage': () => json(usage({ month: { used: 0, limit: 100, resetsAt: '2026-11-01T03:00:00Z' } })),
      'GET /api/bff/copilot/conversations': () => json(page({ items: [] })),
    })
    const { container } = render(<CopilotView entry={null} />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Pergunte sobre seu dinheiro em linguagem comum' })).toBeInTheDocument()
    expect(screen.getByText('Eu uso só os dados que você importou e mostro de onde vem cada número.')).toBeInTheDocument()
    const suggestions = screen.getByRole('region', { name: 'Sugestões para você' })
    expect(within(suggestions).getAllByRole('button').map((b) => b.textContent)).toEqual(['✦Como estou este mês?', '✦Onde mais gastei?'])
    expect(await axeViolations(container)).toEqual([])
  })

  it('histórico desligado: texto e link para Consentimentos', async () => {
    stubBff({ ...base(), 'GET /api/bff/copilot/conversations': () => json(page({ historyEnabled: false, items: [] })) })
    render(<CopilotView entry={null} />)
    expect(await screen.findByText('O histórico está desligado e as conversas não são guardadas.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ativar em Consentimentos' })).toHaveAttribute('href', '/profile/consents')
  })

  it('histórico com cursor: "Ver mais" acrescenta a próxima página', async () => {
    const user = userEvent.setup()
    const second = '01922f5e-0000-7000-8000-000000000a02'
    const calls = stubBff({
      ...base(),
      'GET /api/bff/copilot/conversations': (_i, url) =>
        url.includes('before=cur%20sor')
          ? json(page({ items: [summary(second, 'O que mudou no meu score?', '2026-08-28T15:00:00Z')] }))
          : json(page({ nextCursor: 'cur sor' })),
    })
    render(<CopilotView entry={null} />)
    await user.click(await screen.findByRole('button', { name: 'Ver mais' }))
    expect(await screen.findByRole('link', { name: /O que mudou no meu score\?/ })).toHaveAttribute('href', `/copilot/${second}`)
    expect(screen.queryByRole('button', { name: 'Ver mais' })).not.toBeInTheDocument()
    expect(calls.some((c) => c.url === '/api/bff/copilot/conversations?before=cur%20sor')).toBe(true)
  })

  it('"Apagar todas" pede confirmação e esvazia a lista', async () => {
    const user = userEvent.setup()
    const calls = stubBff({ ...base(), 'DELETE /api/bff/copilot/conversations': () => json({ deleted: true }) })
    render(<CopilotView entry={null} />)
    await user.click(await screen.findByRole('button', { name: 'Apagar todas' }))
    expect(screen.getByText('Apagar todas as conversas? Elas são removidas de vez.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)
    await user.click(screen.getByRole('button', { name: 'Apagar' }))
    await waitFor(() => expect(screen.queryByRole('link', { name: /transporte em agosto/ })).not.toBeInTheDocument())
    expect(calls.filter((c) => c.method === 'DELETE')).toHaveLength(1)
  })
})

describe('perguntar — conversa efêmera × guardada (ADR-0041 §1)', () => {
  it('efêmera: a URL não muda e as perguntas seguintes levam as anteriores (até 3), nunca o conversationId', async () => {
    const user = userEvent.setup()
    const calls = stubBff({
      ...base(),
      'POST /api/bff/copilot/questions': () => json(answer({ conversationId: null })),
    })
    const { container } = render(<CopilotView entry={null} />)
    await screen.findByText('6 de 100 perguntas')

    await ask(user, 'Por que minha fatura subiu?')
    const first = await screen.findByRole('article', { name: 'Resposta do Copilot' })
    expect(first).toHaveFocus()
    expect(screen.getByRole('heading', { level: 1, name: 'Por que minha fatura subiu?' })).toBeInTheDocument()
    expect(replace).not.toHaveBeenCalled()
    expect(body(asks(calls)[0]!.init)).toEqual({ question: 'Por que minha fatura subiu?' })
    expect(headers(asks(calls)[0]!.init)['Idempotency-Key']).toMatch(UUID)
    // O uso no mês é relido depois da resposta.
    await waitFor(() => expect(calls.filter((c) => c.url === '/api/bff/copilot/usage').length).toBeGreaterThan(1))

    // Sugestão de continuação da intenção CARDS.
    const next = screen.getByRole('region', { name: 'Continuar' })
    expect(within(next).getAllByRole('button').map((b) => b.textContent)).toEqual([
      '✦Quanto do limite fica livre no próximo mês?',
      '✦Isso afeta minha saúde financeira?',
    ])
    await user.click(within(next).getByRole('button', { name: /Isso afeta minha saúde financeira\?/ }))
    await waitFor(() => expect(asks(calls)).toHaveLength(2))
    expect(body(asks(calls)[1]!.init)).toEqual({
      question: 'Isso afeta minha saúde financeira?',
      previousQuestions: ['Por que minha fatura subiu?'],
    })
    expect(headers(asks(calls)[1]!.init)['Idempotency-Key']).not.toBe(headers(asks(calls)[0]!.init)['Idempotency-Key'])
    await waitFor(() => expect(screen.getAllByRole('article')).toHaveLength(2))

    await ask(user, 'Terceira?')
    await waitFor(() => expect(asks(calls)).toHaveLength(3))
    await ask(user, 'Quarta?')
    await waitFor(() => expect(asks(calls)).toHaveLength(4))
    await ask(user, 'Quinta?')
    await waitFor(() => expect(asks(calls)).toHaveLength(5))
    expect(body(asks(calls)[4]!.init).previousQuestions).toEqual([
      'Isso afeta minha saúde financeira?',
      'Terceira?',
      'Quarta?',
    ])
    expect(body(asks(calls)[4]!.init)).not.toHaveProperty('conversationId')
    expect(await axeViolations(container)).toEqual([])
  }, 20_000)

  it('guardada: troca a URL por /copilot/{id} (replace) e abre a conversa sem recarregar', async () => {
    const user = userEvent.setup()
    const calls = stubBff({
      ...base(),
      'POST /api/bff/copilot/questions': () =>
        json(answer({ conversationId: CONVERSATION, messageId: '01922f5e-0000-7000-8000-000000000c01' })),
      [`GET /api/bff/copilot/conversations/${CONVERSATION}`]: () => json(conversation()),
    })
    const { unmount } = render(<CopilotView entry={null} />)
    await screen.findByText('6 de 100 perguntas')
    await ask(user, 'Por que minha fatura está mais alta?')
    await waitFor(() => expect(replace).toHaveBeenCalledTimes(1))
    expect(replace).toHaveBeenCalledWith(`/copilot/${CONVERSATION}`)
    unmount()

    // A tela de destino usa a conversa entregue, sem nova leitura nem aviso de "resposta de {data}".
    render(<ConversationView conversationId={CONVERSATION} />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Por que minha fatura está mais alta?' })).toBeInTheDocument()
    expect(screen.queryByText(/Os números podem ter mudado desde então/)).not.toBeInTheDocument()
    expect(calls.some((c) => c.url.includes(`/conversations/${CONVERSATION}`))).toBe(false)
  }, 15_000)

  it('a conversa entregue é consumida uma só vez: a segunda abertura de /copilot/{id} relê a conversa', async () => {
    const user = userEvent.setup()
    const calls = stubBff({
      ...base(),
      'POST /api/bff/copilot/questions': () =>
        json(answer({ conversationId: CONVERSATION, messageId: '01922f5e-0000-7000-8000-000000000c01' })),
      [`GET /api/bff/copilot/conversations/${CONVERSATION}`]: () => json(conversation()),
    })
    const first = render(<CopilotView entry={null} />)
    await screen.findByText('6 de 100 perguntas')
    await ask(user, 'Por que minha fatura está mais alta?')
    await waitFor(() => expect(replace).toHaveBeenCalledTimes(1))
    first.unmount()

    const second = render(<ConversationView conversationId={CONVERSATION} />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Por que minha fatura está mais alta?' })).toBeInTheDocument()
    expect(calls.some((c) => c.url.includes(`/conversations/${CONVERSATION}`))).toBe(false)
    second.unmount()

    render(<ConversationView conversationId={CONVERSATION} />)
    expect(await screen.findByText(/Os números podem ter mudado desde então/)).toBeInTheDocument()
    expect(calls.filter((c) => c.url === `/api/bff/copilot/conversations/${CONVERSATION}`)).toHaveLength(1)
  })

  it('se o replace falhou, abrir /copilot de novo descarta a conversa entregue', async () => {
    const user = userEvent.setup()
    const calls = stubBff({
      ...base(),
      'POST /api/bff/copilot/questions': () => json(answer({ conversationId: CONVERSATION })),
      [`GET /api/bff/copilot/conversations/${CONVERSATION}`]: () => json(conversation()),
    })
    const first = render(<CopilotView entry={null} />)
    await screen.findByText('6 de 100 perguntas')
    await ask(user, 'Por que minha fatura está mais alta?')
    await waitFor(() => expect(replace).toHaveBeenCalledTimes(1))
    first.unmount()

    // O replace "não aconteceu": a pessoa volta a /copilot, que limpa o que ficou pendurado.
    render(<CopilotView entry={null} />).unmount()
    render(<ConversationView conversationId={CONVERSATION} />)
    expect(await screen.findByText(/Os números podem ter mudado desde então/)).toBeInTheDocument()
    expect(calls.some((c) => c.url === `/api/bff/copilot/conversations/${CONVERSATION}`)).toBe(true)
  })

  it('pergunta vazia ou em branco não envia; o campo respeita o limite de 500 caracteres', async () => {
    const user = userEvent.setup()
    const calls = stubBff(base())
    render(<CopilotView entry={null} />)
    const input = screen.getByRole('textbox', { name: 'Pergunta para o Copilot' })
    const send = screen.getByRole('button', { name: 'Enviar pergunta' })
    expect(send).toBeDisabled()
    await user.type(input, '   ')
    expect(send).toBeDisabled()
    expect(input).toHaveAttribute('maxlength', '500')
    expect(asks(calls)).toHaveLength(0)
  })
})

describe('contexto de entrada (ADR-0041 §1, §6)', () => {
  it('mostra as sugestões do contexto, não envia nada sozinho e manda o contexto só na primeira pergunta', async () => {
    const user = userEvent.setup()
    const calls = stubBff({ ...base(), 'POST /api/bff/copilot/questions': () => json(answer()) })
    const entry = parseEntry({ from: 'invoice', cardId: CARD, month: '2026-10' })!
    render(<CopilotView entry={entry} />)

    const suggestions = screen.getByRole('region', { name: 'Sugestões para você' })
    expect(within(suggestions).getAllByRole('button').map((b) => b.textContent)).toEqual([
      '✦Por que esta fatura está mais alta?',
      '✦Quanto já está comprometido na próxima fatura?',
      '✦Quanto do meu limite fica livre?',
    ])
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(asks(calls)).toHaveLength(0)

    await user.click(within(suggestions).getByRole('button', { name: /Por que esta fatura está mais alta\?/ }))
    await screen.findByRole('article')
    expect(body(asks(calls)[0]!.init)).toEqual({
      question: 'Por que esta fatura está mais alta?',
      context: { kind: 'INVOICE', cardId: CARD, month: '2026-10' },
    })

    await ask(user, 'E o limite?')
    await waitFor(() => expect(asks(calls)).toHaveLength(2))
    expect(body(asks(calls)[1]!.init)).toEqual({ question: 'E o limite?', previousQuestions: ['Por que esta fatura está mais alta?'] })
  })

  it.each([
    [{ from: 'insight', insightId: INSIGHT }, 'Por que isso aconteceu?', { kind: 'INSIGHT', insightId: INSIGHT }],
    [{ from: 'installment', cardId: CARD, planId: PLAN }, 'Quanto falta pagar deste parcelamento?', { kind: 'INSTALLMENT_PLAN', cardId: CARD, planId: PLAN }],
    [{ from: 'goal', goalId: GOAL }, 'Estou no ritmo para este objetivo?', { kind: 'GOAL', goalId: GOAL }],
  ])('entrada %j', async (search, suggestion, context) => {
    const user = userEvent.setup()
    const calls = stubBff({ ...base(), 'POST /api/bff/copilot/questions': () => json(answer()) })
    render(<CopilotView entry={parseEntry(search)} />)
    await user.click(screen.getByRole('button', { name: new RegExp(suggestion.replace('?', '\\?')) }))
    await screen.findByRole('article')
    expect(body(asks(calls)[0]!.init).context).toEqual(context)
  })

  it('parâmetros inválidos ou incompletos são ignorados (a tela abre como o /copilot simples)', () => {
    expect(parseEntry({})).toBeNull()
    expect(parseEntry({ from: 'insight', insightId: 'abc' })).toBeNull()
    expect(parseEntry({ from: 'invoice', cardId: CARD })).toBeNull()
    expect(parseEntry({ from: 'invoice', cardId: CARD, month: '2026-13' })).toBeNull()
    expect(parseEntry({ from: 'installment', cardId: CARD })).toBeNull()
    expect(parseEntry({ from: 'goal', goalId: ['a', 'b'] })).toBeNull()
    expect(parseEntry({ from: '<script>', insightId: INSIGHT })).toBeNull()
  })

  it('"Nova pergunta" descarta a conversa e o contexto', async () => {
    const user = userEvent.setup()
    stubBff({ ...base(), 'POST /api/bff/copilot/questions': () => json(answer()) })
    render(<CopilotView entry={parseEntry({ from: 'goal', goalId: GOAL })} />)
    await user.click(screen.getByRole('button', { name: /Estou no ritmo para este objetivo/ }))
    await screen.findByRole('article')
    await user.click(screen.getByRole('button', { name: /Nova pergunta/ }))
    expect(await screen.findByRole('heading', { level: 1, name: 'O que você quer entender sobre seu dinheiro?' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Pergunta para o Copilot' })).toHaveFocus()
    const suggestions = screen.getByRole('region', { name: 'Sugestões para você' })
    expect(within(suggestions).getAllByRole('button')).toHaveLength(4)
  })
})

describe('estados da pergunta (05-states, ADR-0041 §5)', () => {
  it('carregando: pergunta visível, skeleton e "Analisando seus dados…" numa região viva', async () => {
    const user = userEvent.setup()
    let release: (r: Response) => void = () => {}
    stubBff({ ...base(), 'POST /api/bff/copilot/questions': () => new Promise<Response>((resolve) => (release = resolve)) })
    const { container } = render(<CopilotView entry={null} />)
    await ask(user, 'Onde mais gastei?')

    // Visual (aria-hidden) + anúncio na região viva dedicada: um único aria-live na tela.
    await waitFor(() => expect(screen.getAllByText('Analisando seus dados…')).toHaveLength(2))
    expect(screen.getByText('Onde mais gastei?', { selector: 'p' })).toBeInTheDocument()
    expect(screen.queryByText('Ainda calculando. Pode levar alguns segundos.')).not.toBeInTheDocument()
    const live = container.querySelectorAll('[aria-live="polite"]')
    expect(live).toHaveLength(1)
    expect(live[0]).toHaveTextContent('Analisando seus dados…')
    expect(container.querySelector('[class*="thread"]')).not.toHaveAttribute('aria-live')
    expect(screen.getByRole('button', { name: 'Enviar pergunta' })).toBeDisabled()
    expect(await axeViolations(container)).toEqual([])

    await act(async () => release(json(answer())))
    const article = await screen.findByRole('article', { name: 'Resposta do Copilot' })
    expect(article).toHaveFocus()
    expect(screen.queryByText('Analisando seus dados…')).not.toBeInTheDocument()
    expect(container.querySelector('[aria-live="polite"]')).toHaveTextContent('Resposta pronta')
  })

  it('"Nova pergunta" durante uma pergunta pendente libera o campo; a resposta tardia é descartada', async () => {
    const user = userEvent.setup()
    let release: (r: Response) => void = () => {}
    const calls = stubBff({
      ...base(),
      'POST /api/bff/copilot/questions': () => new Promise<Response>((resolve) => (release = resolve)),
    })
    render(<CopilotView entry={null} />)
    await ask(user, 'Onde mais gastei?')
    await waitFor(() => expect(screen.getAllByText('Analisando seus dados…').length).toBeGreaterThan(0))

    await user.click(screen.getByRole('button', { name: /Nova pergunta/ }))
    expect(await screen.findByRole('heading', { level: 1, name: 'O que você quer entender sobre seu dinheiro?' })).toBeInTheDocument()
    const input = screen.getByRole('textbox', { name: 'Pergunta para o Copilot' })
    await user.type(input, 'Outra pergunta')
    expect(screen.getByRole('button', { name: 'Enviar pergunta' })).toBeEnabled()
    expect(screen.getByRole('button', { name: /Onde mais gastei este mês\?/ })).toBeEnabled()

    await act(async () => release(json(answer({ conversationId: CONVERSATION }))))
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'O que você quer entender sobre seu dinheiro?' })).toBeInTheDocument()
    expect(input).toHaveValue('Outra pergunta')
    expect(replace).not.toHaveBeenCalled()
    expect(asks(calls)).toHaveLength(1)
  })

  it('carregando há mais de 10 s mostra a mensagem extra', () => {
    vi.useFakeTimers()
    try {
      render(<LoadingPanel />)
      expect(screen.queryByText('Ainda calculando. Pode levar alguns segundos.')).not.toBeInTheDocument()
      act(() => void vi.advanceTimersByTime(10_000))
      expect(screen.getByText('Ainda calculando. Pode levar alguns segundos.')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('sem consentimento (403): texto e link para Consentimentos', async () => {
    const user = userEvent.setup()
    stubBff({ ...base(), 'POST /api/bff/copilot/questions': () => json({ code: 'COPILOT_CONSENT_REQUIRED' }, 403) })
    const { container } = render(<CopilotView entry={null} />)
    await ask(user, 'Como estou?')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Para responder, o Copilot precisa usar seus dados financeiros. Você decide em Consentimentos.')
    expect(within(alert).getByRole('link', { name: 'Rever consentimentos' })).toHaveAttribute('href', '/profile/consents')
    expect(await axeViolations(container)).toEqual([])
  })

  it('limite (429 QUOTA): título, data de liberação e "Ver insights"', async () => {
    const user = userEvent.setup()
    stubBff({
      'GET /api/bff/copilot/usage': () => json(usage({ month: { used: 100, limit: 100, resetsAt: '2026-11-01T03:00:00Z' } })),
      'GET /api/bff/copilot/conversations': () => json(page()),
      'POST /api/bff/copilot/questions': () => json({ code: 'COPILOT_QUOTA_EXCEEDED', resetsAt: '2026-11-01T03:00:00.000Z' }, 429),
    })
    const { container } = render(<CopilotView entry={null} />)
    await screen.findByText('100 de 100 perguntas')
    await ask(user, 'Como estou?')
    const alert = await screen.findByRole('alert')
    expect(within(alert).getByRole('heading', { name: 'Você usou as perguntas deste mês' })).toBeInTheDocument()
    expect(alert).toHaveTextContent('Novas perguntas liberam em 1 nov. Insights e análises continuam disponíveis.')
    expect(within(alert).getByRole('link', { name: 'Ver insights' })).toHaveAttribute('href', '/insights')
    expect(await axeViolations(container)).toEqual([])
  })

  it('limite diário: "perguntas de hoje"', async () => {
    const user = userEvent.setup()
    stubBff({
      'GET /api/bff/copilot/usage': () => json(usage({ day: { used: 20, limit: 20, resetsAt: '2026-09-23T03:00:00Z' } })),
      'GET /api/bff/copilot/conversations': () => json(page()),
      'POST /api/bff/copilot/questions': () => json({ code: 'COPILOT_QUOTA_EXCEEDED', resetsAt: '2026-09-23T03:00:00.000Z' }, 429),
    })
    render(<CopilotView entry={null} />)
    await screen.findByText('6 de 100 perguntas')
    await ask(user, 'Como estou?')
    expect(await screen.findByRole('heading', { name: 'Você usou as perguntas de hoje' })).toBeInTheDocument()
  })

  it('muitas perguntas (429 RATE): os segundos do Retry-After', async () => {
    const user = userEvent.setup()
    stubBff({ ...base(), 'POST /api/bff/copilot/questions': () => json({ code: 'COPILOT_RATE_LIMITED', retryAfter: 17 }, 429) })
    render(<CopilotView entry={null} />)
    await ask(user, 'Como estou?')
    expect(await screen.findByRole('alert')).toHaveTextContent('Muitas perguntas seguidas. Tente de novo em 17 segundos.')
  })

  it('429 RATE: "Tentar novamente" fica desabilitado até passar o Retry-After', () => {
    vi.useFakeTimers()
    try {
      const onRetry = vi.fn()
      render(<FailurePanel failure={{ ok: false, code: 'COPILOT_RATE_LIMITED', retryAfter: 5 }} entry={null} usage={undefined} onRetry={onRetry} />)
      const button = screen.getByRole('button', { name: 'Tentar novamente' })
      expect(button).toBeDisabled()
      act(() => void vi.advanceTimersByTime(4_900))
      expect(button).toBeDisabled()
      act(() => void vi.advanceTimersByTime(200))
      expect(button).toBeEnabled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('429 RATE: desmontar antes do prazo não deixa timer pendente', () => {
    vi.useFakeTimers()
    try {
      const { unmount } = render(
        <FailurePanel failure={{ ok: false, code: 'COPILOT_RATE_LIMITED', retryAfter: 5 }} entry={null} usage={undefined} onRetry={() => {}} />,
      )
      unmount()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('já respondida (409): pede para perguntar de novo', async () => {
    const user = userEvent.setup()
    stubBff({ ...base(), 'POST /api/bff/copilot/questions': () => json({ code: 'COPILOT_ALREADY_ANSWERED' }, 409) })
    render(<CopilotView entry={null} />)
    await ask(user, 'Como estou?')
    expect(await screen.findByRole('alert')).toHaveTextContent('Esta pergunta já foi respondida, mas a resposta não foi guardada. Pergunte de novo.')
  })

  it('erro: "Tentar novamente" reenvia o MESMO envio (mesma Idempotency-Key e corpo); um novo envio usa outra chave', async () => {
    const user = userEvent.setup()
    let attempts = 0
    const calls = stubBff({
      ...base(),
      'POST /api/bff/copilot/questions': () => (++attempts === 1 ? json({ code: 'COPILOT_UNAVAILABLE' }, 503) : json(answer())),
    })
    const { container } = render(<CopilotView entry={null} />)
    await ask(user, 'Por que minha fatura subiu?')

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByRole('heading', { name: 'Não consegui concluir esta análise' })).toBeInTheDocument()
    expect(alert).toHaveTextContent('Seus dados estão seguros. Você pode tentar de novo ou ver seus insights.')
    expect(within(alert).getByRole('link', { name: 'Ver insights' })).toHaveAttribute('href', '/insights')
    expect(alert).not.toHaveTextContent('COPILOT_UNAVAILABLE')
    expect(await axeViolations(container)).toEqual([])

    await user.click(within(alert).getByRole('button', { name: 'Tentar novamente' }))
    expect(await screen.findByRole('article')).toBeInTheDocument()
    const [first, second] = asks(calls)
    expect(headers(second!.init)['Idempotency-Key']).toBe(headers(first!.init)['Idempotency-Key'])
    expect(body(second!.init)).toEqual(body(first!.init))

    await ask(user, 'Outra?')
    await waitFor(() => expect(asks(calls)).toHaveLength(3))
    expect(headers(asks(calls)[2]!.init)['Idempotency-Key']).not.toBe(headers(first!.init)['Idempotency-Key'])
  })

  it('erro de rede ou 502 também vira o estado de erro, e o texto se adapta ao ponto de entrada', async () => {
    const user = userEvent.setup()
    stubBff({ ...base(), 'POST /api/bff/copilot/questions': () => json({ code: 'UNAVAILABLE' }, 502) })
    render(<CopilotView entry={parseEntry({ from: 'invoice', cardId: CARD, month: '2026-10' })} />)
    await user.click(screen.getByRole('button', { name: /Por que esta fatura está mais alta\?/ }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Seus dados estão seguros. Você pode tentar de novo ou ver a fatura diretamente.')
    expect(within(alert).getByRole('link', { name: 'Ver fatura' })).toHaveAttribute('href', `/cards/${CARD}/invoices/2026-10`)
  })

  it('chave reutilizada (422) troca a chave para o próximo envio', async () => {
    const user = userEvent.setup()
    let attempts = 0
    const calls = stubBff({
      ...base(),
      'POST /api/bff/copilot/questions': () =>
        ++attempts === 1 ? json({ code: 'IDEMPOTENCY_KEY_REUSED' }, 422) : json(answer()),
    })
    render(<CopilotView entry={null} />)
    await ask(user, 'Como estou?')
    await user.click(await screen.findByRole('button', { name: 'Tentar novamente' }))
    await screen.findByRole('article')
    const [first, second] = asks(calls)
    expect(headers(second!.init)['Idempotency-Key']).not.toBe(headers(first!.init)['Idempotency-Key'])
  })

  it('dados insuficientes e fora do escopo mostram o texto do backend, sem feedback', async () => {
    const user = userEvent.setup()
    stubBff({
      ...base(),
      'POST /api/bff/copilot/questions': () => json(plain('OUT_OF_SCOPE', 'Não faço transferências nem pagamentos.')),
    })
    const { container } = render(<CopilotView entry={null} />)
    await ask(user, 'Pague minha fatura')
    expect(await screen.findByText('Não faço transferências nem pagamentos.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resposta útil' })).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })
})

describe('/copilot/{id} — D-CopilotChat (conversa guardada)', () => {
  it('abre a conversa do histórico com o aviso da data, continua com o conversationId e destaca no histórico', async () => {
    const user = userEvent.setup()
    const calls = stubBff({
      ...base(),
      [`GET /api/bff/copilot/conversations/${CONVERSATION}`]: () => json(conversation()),
      'POST /api/bff/copilot/questions': () => json(answer({ conversationId: CONVERSATION })),
    })
    const { container } = render(<ConversationView conversationId={CONVERSATION} />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Por que minha fatura está mais alta?' })).toBeInTheDocument()
    expect(screen.getByText('Resposta de 22 set. Os números podem ter mudado desde então.')).toBeInTheDocument()
    expect(screen.getByRole('article')).not.toHaveFocus()
    expect(await screen.findByText('Dados de abr a set')).toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])

    await user.click(screen.getByRole('button', { name: /Quanto do limite fica livre no próximo mês\?/ }))
    await waitFor(() => expect(asks(calls)).toHaveLength(1))
    expect(body(asks(calls)[0]!.init)).toEqual({
      question: 'Quanto do limite fica livre no próximo mês?',
      conversationId: CONVERSATION,
    })
    await waitFor(() => expect(screen.getAllByRole('article')).toHaveLength(2))
    // Conversa já guardada: sem nova troca de URL.
    expect(replace).not.toHaveBeenCalled()
  })

  it('apagar a conversa pede confirmação, apaga e volta para /copilot', async () => {
    const user = userEvent.setup()
    const calls = stubBff({
      ...base(),
      [`GET /api/bff/copilot/conversations/${CONVERSATION}`]: () => json(conversation()),
      [`DELETE /api/bff/copilot/conversations/${CONVERSATION}`]: () => json({ deleted: true }),
    })
    render(<ConversationView conversationId={CONVERSATION} />)
    await user.click(await screen.findByRole('button', { name: 'Apagar conversa' }))
    expect(screen.getByText('Apagar esta conversa? Ela é removida de vez.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Apagar conversa' }))
    await user.click(screen.getByRole('button', { name: 'Apagar' }))
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/copilot'))
  })

  it('falha ao apagar avisa e mantém a conversa', async () => {
    const user = userEvent.setup()
    stubBff({
      ...base(),
      [`GET /api/bff/copilot/conversations/${CONVERSATION}`]: () => json(conversation()),
      [`DELETE /api/bff/copilot/conversations/${CONVERSATION}`]: () => json({ code: 'UNAVAILABLE' }, 502),
    })
    render(<ConversationView conversationId={CONVERSATION} />)
    await user.click(await screen.findByRole('button', { name: 'Apagar conversa' }))
    await user.click(screen.getByRole('button', { name: 'Apagar' }))
    expect(await screen.findByText('Não conseguimos apagar agora. Tente de novo.')).toBeInTheDocument()
    expect(replace).not.toHaveBeenCalled()
  })

  it('conversa inexistente (404) e id inválido: "Não encontramos esta conversa"', async () => {
    stubBff({ 'GET /api/bff/copilot/conversations/': () => json({ code: 'COPILOT_CONVERSATION_NOT_FOUND' }, 404) })
    const { container, unmount } = render(<ConversationView conversationId={CONVERSATION} />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Não encontramos esta conversa' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Nova pergunta' })).toHaveAttribute('href', '/copilot')
    expect(await axeViolations(container)).toEqual([])
    unmount()

    const calls = stubBff({})
    render(<ConversationView conversationId={null} />)
    expect(screen.getByRole('heading', { level: 1, name: 'Não encontramos esta conversa' })).toBeInTheDocument()
    expect(calls).toHaveLength(0)
  })

  it('erro ao carregar: "Tentar novamente" relê a conversa', async () => {
    const user = userEvent.setup()
    let attempts = 0
    stubBff({
      ...base(),
      [`GET /api/bff/copilot/conversations/${CONVERSATION}`]: () =>
        ++attempts === 1 ? json({ code: 'UNAVAILABLE' }, 502) : json(conversation()),
    })
    render(<ConversationView conversationId={CONVERSATION} />)
    const alert = await screen.findByRole('alert')
    await user.click(within(alert).getByRole('button', { name: 'Tentar novamente' }))
    expect(await screen.findByRole('article')).toBeInTheDocument()
  })
})
