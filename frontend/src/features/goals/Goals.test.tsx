import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Account } from '@/features/accounts/api'
import { SPIKE_ID, spikeDetail } from '@/features/insights/fixtures'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import { EditGoalView } from './EditGoalView'
import {
  AURORA,
  brl,
  goal,
  goalList,
  NORTE,
  projection,
  RESERVE_ID,
  reserveGoal,
  scenario,
  simulation,
  TRIP_ACCOUNT,
  TRIP_ID,
} from './fixtures'
import { monthYear, statusBadge } from './format'
import { GoalDetailView } from './GoalDetailView'
import { amountToInput, parsePlanned } from './GoalForm'
import { GoalsView } from './GoalsView'
import { NewGoalView } from './NewGoalView'

const push = vi.fn()
const replace = vi.fn()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace }),
  usePathname: () => '/goals',
  useSearchParams: () => new URLSearchParams(),
}))

beforeEach(() => {
  push.mockReset()
  replace.mockReset()
})
afterEach(() => vi.unstubAllGlobals())

const account = (o: Partial<Account> = {}): Account => ({
  id: AURORA,
  workspaceId: 'ws',
  name: 'Conta Aurora',
  type: 'CHECKING',
  institutionName: 'Banco Aurora',
  currency: 'BRL',
  includedInTotal: true,
  status: 'ACTIVE',
  createdAt: '2026-04-01T00:00:00Z',
  updatedAt: '2026-04-01T00:00:00Z',
  ...o,
})
const accounts = [
  account(),
  account({ id: TRIP_ACCOUNT, name: 'Caixinha Lisboa', type: 'OTHER' }),
  account({ id: NORTE, name: 'Banco Norte · Reserva', type: 'SAVINGS' }),
]
const accountRoute = { 'GET /api/bff/accounts': () => json({ items: accounts }) }
const body = (init: RequestInit | undefined) => JSON.parse(String(init?.body)) as Record<string, unknown>

describe('/goals (D-Goals / M-Goals)', () => {
  const routes = () => ({
    ...accountRoute,
    'GET /api/bff/goals': () => json(goalList()),
    'GET /api/bff/goals/recent-contributions': () =>
      json({
        contributions: [
          { goalId: TRIP_ID, transactionId: 't1', occurredOn: '2026-09-21', amount: brl('700.00'), sourceAccountId: AURORA },
        ],
      }),
    [`POST /api/bff/goals/${TRIP_ID}/simulations`]: () => json(simulation()),
  })

  it('métricas, sugestão sem ✦ com o mínimo do backend, Goal Cards e aportes recentes', async () => {
    const calls = stubBff(routes())
    const { container } = render(<GoalsView />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Objetivos' })).toBeInTheDocument()
    const metrics = await screen.findByLabelText('Resumo dos objetivos')
    expect(within(metrics).getByText('R$ 14.300,00')).toBeInTheDocument()
    expect(within(metrics).getByText('R$ 1.200,00')).toBeInTheDocument()
    expect(within(metrics).getByText('≈ 18% da sua renda média')).toBeInTheDocument()
    expect(within(metrics).getByText('1 de 2')).toBeInTheDocument()

    const trip = screen.getByRole('article', { name: 'Viagem para Lisboa' })
    expect(within(trip).getByRole('link', { name: 'Viagem para Lisboa' })).toHaveAttribute('href', `/goals/${TRIP_ID}`)
    expect(within(trip).getByText('Prazo: jul/2027')).toBeInTheDocument()
    expect(within(trip).getAllByText('1 mês após o prazo').length).toBeGreaterThan(0)
    expect(within(trip).getByText('36% concluído')).toBeInTheDocument()
    expect(within(trip).getByText('Previsão estimada: agosto de 2027')).toBeInTheDocument()
    const reserve = screen.getByRole('article', { name: 'Reserva de emergência' })
    expect(within(reserve).getByText('Sem prazo')).toBeInTheDocument()
    expect(within(reserve).getByText('Prazo: sem prazo definido')).toBeInTheDocument()

    // Sugestão: o objetivo atrasado, com o aporte mínimo para o prazo (cenário só com prazo, ADR-0036 §2).
    const suggestion = await screen.findByRole('complementary', { name: 'Simulação' })
    expect(suggestion).toHaveTextContent('Com R$ 770,00/mês, Viagem para Lisboa fica pronto em jul/2027 — dentro do prazo.')
    expect(within(suggestion).getByRole('link', { name: /Simular/ })).toHaveAttribute('href', `/goals/${TRIP_ID}#simular`)
    const simulate = calls.find((c) => c.method === 'POST' && c.url.endsWith('/simulations'))!
    expect(body(simulate.init)).toEqual({ scenarios: [{ deadline: '2027-07' }] })
    expect(screen.queryByText(/✦/)).not.toBeInTheDocument()

    const recent = screen.getByRole('heading', { name: 'Aportes recentes' }).closest('section')!
    expect(await within(recent).findByText('21 set. · de Conta Aurora')).toBeInTheDocument()
    expect(within(recent).getByText('R$ 700,00')).toBeInTheDocument()
    expect(within(recent).getByText('Confirmado')).toBeInTheDocument()

    expect(await axeViolations(container)).toEqual([])
  })

  it('sem objetivo atrasado, sem sugestão; nenhuma simulação é pedida', async () => {
    const calls = stubBff({ ...routes(), 'GET /api/bff/goals': () => json(goalList([reserveGoal()])) })
    render(<GoalsView />)
    expect(await screen.findByRole('article', { name: 'Reserva de emergência' })).toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: 'Simulação' })).not.toBeInTheDocument()
    expect(calls.some((c) => c.url.includes('/simulations'))).toBe(false)
  })

  it('vazio: convida a criar o primeiro objetivo', async () => {
    stubBff({ ...routes(), 'GET /api/bff/goals': () => json(goalList([], { activeCount: 0, forecastCount: 0, onTrackCount: 0 })) })
    const { container } = render(<GoalsView />)
    expect(await screen.findByRole('heading', { name: 'Crie seu primeiro objetivo' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: /Criar objetivo/ })[0]).toHaveAttribute('href', '/goals/new')
    expect(screen.queryByRole('heading', { name: 'Aportes recentes' })).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('erro: mensagem neutra e nova tentativa', async () => {
    let fail = true
    stubBff({ ...routes(), 'GET /api/bff/goals': () => (fail ? json({ code: 'UNAVAILABLE' }, 502) : json(goalList())) })
    render(<GoalsView />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Seus dados estão seguros')
    fail = false
    await userEvent.click(screen.getByRole('button', { name: 'Tentar novamente' }))
    expect(await screen.findByRole('article', { name: 'Viagem para Lisboa' })).toBeInTheDocument()
  })

  it('sem previsão pronta: só fatos e o motivo', async () => {
    const noForecast = goal({ forecast: { ...goal().forecast, status: 'NO_FORECAST', reason: 'FORECAST_NOT_READY', completion: null, monthsLate: null, range: null, confidence: null } })
    stubBff({ ...routes(), 'GET /api/bff/goals': () => json(goalList([noForecast], { forecastCount: 0, onTrackCount: 0, plannedIncomeShare: null })) })
    render(<GoalsView />)
    const card = await screen.findByRole('article', { name: 'Viagem para Lisboa' })
    expect(within(card).getByText('A previsão aparece quando houver 3 meses de histórico.')).toBeInTheDocument()
    expect(within(card).queryByText('Estimado')).not.toBeInTheDocument()
  })

  it('D-GoalCreated: o novo objetivo primeiro, "Novo" e o toast com "Ver objetivo"', async () => {
    stubBff(routes())
    render(<GoalsView created={TRIP_ID} firstMonth="2026-10" />)
    const cards = await screen.findAllByRole('article')
    expect(cards[0]).toHaveAccessibleName('Viagem para Lisboa')
    expect(within(cards[0]!).getByText('Novo')).toBeInTheDocument()
    const toast = screen.getByText('Objetivo criado. O primeiro aporte está previsto para outubro.').closest('[role="status"]')!
    expect(within(toast as HTMLElement).getByRole('link', { name: 'Ver objetivo' })).toHaveAttribute('href', `/goals/${TRIP_ID}`)
    await userEvent.click(screen.getByRole('button', { name: 'Fechar aviso' }))
    expect(replace).toHaveBeenCalledWith('/goals')
  })
})

describe('/goals/:id (D-GoalDetail)', () => {
  /** Plano atual no "backend" falso: o cenário sem valor simula o plano vigente. */
  let plannedNow = '700.00'
  beforeEach(() => {
    plannedNow = '700.00'
  })
  const routes = (o: Record<string, (init: RequestInit | undefined, url: string) => Response> = {}) => ({
    ...accountRoute,
    [`GET /api/bff/goals/${TRIP_ID}`]: () => json(goal()),
    [`GET /api/bff/goals/${TRIP_ID}/projection`]: () => json(projection()),
    [`GET /api/bff/goals/${TRIP_ID}/contributions`]: () =>
      json({
        openingBalance: { date: '2026-04-09', amount: brl('100.00') },
        movements: [
          { transactionId: 'm1', kind: 'CONTRIBUTION', direction: 'IN', occurredOn: '2026-09-21', amount: brl('700.00'), counterpartAccountId: AURORA },
        ],
        page: 0,
        pageSize: 50,
        totalElements: 1,
      }),
    [`POST /api/bff/goals/${TRIP_ID}/simulations`]: (init: RequestInit | undefined) => {
      const { scenarios } = body(init) as { scenarios: { deadline?: string; monthlyAmount?: { amount: string } }[] }
      if (scenarios[0]?.deadline) return json(simulation([scenario()]))
      return json(simulation(scenarios.map((s) =>
        s.monthlyAmount
          ? scenario({ monthlyAmount: brl(s.monthlyAmount.amount), completion: '2027-05', contributions: 8, deadline: { month: '2027-07', within: true, monthsFromDeadline: -2 } })
          : scenario({ monthlyAmount: brl(plannedNow), completion: '2027-08', contributions: 11, deadline: { month: '2027-07', within: false, monthsFromDeadline: 1 } }))))
    },
    ...o,
  })

  it('progresso (fato), previsão (Estimado), gráfico com tabela, aportes e Simulation Card', async () => {
    stubBff(routes())
    render(<GoalDetailView id={TRIP_ID} />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Viagem para Lisboa' })).toBeInTheDocument()
    expect(screen.getByText('Criado em abr/2026 · aportes de Conta Aurora')).toBeInTheDocument()
    const facts = screen.getByRole('heading', { name: 'Progresso' }).closest('section')!
    expect(within(facts).getByText('36%')).toBeInTheDocument()
    expect(within(facts).getByText('R$ 7.700,00')).toBeInTheDocument()
    expect(within(facts).getByText('jul/2027')).toBeInTheDocument()
    expect(within(facts).getByText('agosto de 2027, estimado')).toBeInTheDocument()
    expect(within(facts).getByText('Faixa: ago/2027 a out/2027 · confiança média')).toBeInTheDocument()

    expect(await screen.findByRole('table', { name: /Guardado e projeção/ })).toBeInTheDocument()
    const history = screen.getByRole('heading', { name: 'Aportes' }).closest('section')!
    expect(await within(history).findByText('De Conta Aurora')).toBeInTheDocument()
    expect(history).toHaveTextContent('Saldo inicial de R$ 100,00 em 9 abr., antes de acompanhar o objetivo.')

    // Simulation Card: plano atual e o mínimo para o prazo (backend); o mínimo vem selecionado quando atrasado.
    const card = screen.getByRole('heading', { name: 'Simular aporte' }).closest('section')!
    expect(within(card).getByText('Simulado')).toBeInTheDocument()
    const minimum = await within(card).findByRole('button', { name: /^R\$\s770,00\/mês/ })
    expect(minimum).toHaveAttribute('aria-pressed', 'true')
    expect(within(card).getByRole('button', { name: /^R\$\s700,00\/mês/ })).toHaveAttribute('aria-pressed', 'false')
    expect(within(card).getByText('Dentro do prazo de jul/2027')).toBeInTheDocument()
    expect(within(card).getByText('10 aportes a partir de out/2026')).toBeInTheDocument()
    expect(within(card).getByText('Simulação não altera seu objetivo. Considera aportes mensais iguais, sem rendimento.')).toBeInTheDocument()

    await userEvent.click(within(card).getByRole('button', { name: /^R\$\s700,00\/mês/ }))
    expect(within(card).getByText('1 mês após o prazo de jul/2027')).toBeInTheDocument()
    // O plano atual não tem o que aplicar.
    expect(within(card).queryByRole('button', { name: /Aplicar/ })).not.toBeInTheDocument()
    expect(await axeViolations(document.body)).toEqual([])
  })

  it('"Aplicar" só com confirmação e só troca o plano (PUT /plan)', async () => {
    const calls = stubBff(routes({
      [`PUT /api/bff/goals/${TRIP_ID}/plan`]: () => {
        plannedNow = '770.00'
        return json(goal({ plannedMonthly: brl('770.00'), forecast: { ...goal().forecast, status: 'ON_TRACK', completion: '2027-07', monthsLate: null } }))
      },
    }))
    render(<GoalDetailView id={TRIP_ID} />)
    const apply = await screen.findByRole('button', { name: /^Aplicar R\$\s770,00\/mês ao objetivo$/ })
    await userEvent.click(apply)
    expect(calls.some((c) => c.method === 'PUT')).toBe(false)
    const confirm = screen.getByRole('group', { name: /Trocar o aporte planejado/ })
    expect(confirm).toHaveTextContent('Trocar o aporte planejado de R$ 700,00 para R$ 770,00 por mês? O prazo continua jul/2027.')
    await userEvent.click(within(confirm).getByRole('button', { name: 'Confirmar' }))
    const put = calls.find((c) => c.method === 'PUT')!
    expect(put.url).toBe(`/api/bff/goals/${TRIP_ID}/plan`)
    expect(body(put.init)).toEqual({ plannedMonthly: brl('770.00'), deadline: '2027-07' })
    expect(await screen.findByText('Plano atualizado: R$ 770,00 por mês. A previsão já considera o novo valor.')).toBeInTheDocument()
    expect(screen.getAllByText('No prazo').length).toBeGreaterThan(0)
    // O card remonta com o novo plano: o chip do plano é R$ 770 e nada mais a aplicar (nunca "Aplicar R$ 700").
    expect(await screen.findByRole('button', { name: /^R\$\s770,00\/mês plano atual/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByRole('button', { name: /^Aplicar/ })).not.toBeInTheDocument()
  })

  it('"Outro valor": valida o texto e simula com o valor digitado', async () => {
    const calls = stubBff(routes())
    render(<GoalDetailView id={TRIP_ID} />)
    const input = await screen.findByLabelText('Outro valor por mês')
    await userEvent.type(input, 'abc')
    await userEvent.click(screen.getByRole('button', { name: 'Simular' }))
    expect(screen.getByText('Digite o valor em reais, por exemplo 1.800,00.')).toBeInTheDocument()
    await userEvent.clear(input)
    await userEvent.type(input, '1.000,00')
    await userEvent.click(screen.getByRole('button', { name: 'Simular' }))
    expect(await screen.findByRole('button', { name: /^R\$\s1\.000,00\/mês/ })).toHaveAttribute('aria-pressed', 'true')
    const last = calls.filter((c) => c.url.endsWith('/simulations')).at(-1)!
    expect(body(last.init)).toEqual({ scenarios: [{}, { monthlyAmount: brl('1000.00') }] })
    expect(await screen.findByText('Dentro do prazo de jul/2027')).toBeInTheDocument()
  })

  it('"Registrar aporte": drawer, Idempotency-Key, corpo do contrato e erro por campo', async () => {
    let attempt = 0
    const calls = stubBff(routes({
      [`POST /api/bff/goals/${TRIP_ID}/contributions`]: () => {
        attempt += 1
        return attempt === 1
          ? json({ code: 'VALIDATION_FAILED', details: [{ field: 'occurredOn', code: 'BEFORE_TRACKING_START' }] }, 400)
          : json({ id: 'tx1' }, 201)
      },
    }))
    render(<GoalDetailView id={TRIP_ID} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Registrar aporte' }))
    const dialog = await screen.findByRole('dialog', { name: 'Registrar aporte' })
    // Origem padrão = a do objetivo; o destino (conta do objetivo) não é opção.
    expect(within(dialog).getByLabelText('Conta de origem')).toHaveValue(AURORA)
    expect(within(dialog).queryByRole('option', { name: 'Caixinha Lisboa' })).not.toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Registrar aporte' }))
    expect(within(dialog).getByText('Informe o valor.')).toBeInTheDocument()
    await userEvent.type(within(dialog).getByLabelText('Valor'), '700')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Registrar aporte' }))
    expect(await within(dialog).findByText('Use uma data a partir da criação do objetivo.')).toBeInTheDocument()

    const first = calls.find((c) => c.method === 'POST' && c.url.endsWith('/contributions'))!
    const sent = body(first.init)
    expect(Object.keys(sent).sort()).toEqual(['amount', 'destinationAccountId', 'occurredOn', 'sourceAccountId'])
    expect(sent.amount).toEqual(brl('700.00'))
    expect(sent.destinationAccountId).toBeNull()
    const key1 = (first.init?.headers as Record<string, string>)['Idempotency-Key']
    expect(key1).toMatch(/^web:/)

    await userEvent.click(within(dialog).getByRole('button', { name: 'Registrar aporte' }))
    expect(await screen.findByText('Aporte de R$ 700,00 registrado. O progresso já considera este valor.')).toBeInTheDocument()
    const second = calls.filter((c) => c.method === 'POST' && c.url.endsWith('/contributions'))[1]!
    // Recusa do pedido → nova intenção, nova chave.
    expect((second.init?.headers as Record<string, string>)['Idempotency-Key']).not.toBe(key1)
  })

  it('reserva: aporte pede a conta de reserva; origem não pode ser poupança', async () => {
    stubBff({
      ...accountRoute,
      [`GET /api/bff/goals/${RESERVE_ID}`]: () => json(reserveGoal()),
      [`GET /api/bff/goals/${RESERVE_ID}/projection`]: () => json(projection({ deadline: null })),
      [`GET /api/bff/goals/${RESERVE_ID}/contributions`]: () =>
        json({ openingBalance: { date: '2026-04-09', amount: brl('9500.00') }, movements: [], page: 0, pageSize: 50, totalElements: 0 }),
      [`POST /api/bff/goals/${RESERVE_ID}/simulations`]: () => json(simulation([scenario({ deadline: null })])),
    })
    render(<GoalDetailView id={RESERVE_ID} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Registrar aporte' }))
    const dialog = await screen.findByRole('dialog', { name: 'Registrar aporte' })
    expect(within(dialog).getByLabelText('Conta da reserva')).toHaveValue(NORTE)
    const sources = within(within(dialog).getByLabelText('Conta de origem')).getAllByRole('option').map((o) => o.textContent)
    expect(sources).not.toContain('Banco Norte · Reserva')
  })

  it('id inválido ou de outro Workspace: "não encontrado"', async () => {
    render(<GoalDetailView id={null} />)
    expect(screen.getByRole('alert')).toHaveTextContent('Este objetivo não existe ou não está mais disponível.')
    vi.unstubAllGlobals()
    stubBff({ ...accountRoute, [`GET /api/bff/goals/${TRIP_ID}`]: () => json({ code: 'GOAL_NOT_FOUND' }, 404) })
    render(<GoalDetailView id={TRIP_ID} />)
    await waitFor(() => expect(screen.getAllByText('Objetivo não encontrado')).toHaveLength(2))
  })
})

describe('/goals/new (D-CreateGoal)', () => {
  const routes = () => ({
    ...accountRoute,
    'GET /api/bff/goals': () => json(goalList([])),
    [`GET /api/bff/insights/${SPIKE_ID}`]: () => json(spikeDetail()),
    'POST /api/bff/goals/preview': (init: RequestInit | undefined) => {
      const sent = body(init) as { type: string }
      return json({
        alreadySaved: brl(sent.type === 'EMERGENCY_RESERVE' ? '10000.00' : '0.00'),
        suggestedTarget: sent.type === 'EMERGENCY_RESERVE' ? brl('25152.00') : null,
        target: brl('1800.00'),
        simulation: simulation([scenario({ monthlyAmount: brl('300.00'), completion: '2027-03', contributions: 6, incomeShare: 5,
          deadline: { month: '2027-03', within: true, monthsFromDeadline: 0 } })]),
      })
    },
    'POST /api/bff/goals': () => json(goal({ id: 'new-goal' }), 201),
  })

  it('a partir do insight: origem, sugestão lida do backend, prévia Simulada e criação com origem', async () => {
    const calls = stubBff(routes())
    const { container } = render(<NewGoalView origin={{ kind: 'INSIGHT', insightId: SPIKE_ID }} />)
    expect(await screen.findByRole('link', { name: 'Alimentação fora' })).toHaveAttribute('href', `/insights/${SPIKE_ID}`)
    expect(screen.getByLabelText('Nome do objetivo')).toHaveValue('Guardar a diferença em alimentação fora')
    expect(screen.getByLabelText('Valor alvo')).toHaveValue('1.800,00')
    expect(screen.getByLabelText('Aporte mensal planejado')).toHaveValue('300,00')
    expect(screen.getByLabelText('Prazo (opcional)')).toHaveValue('2027-03')
    expect(screen.getByRole('radio', { name: /Outro objetivo/ })).toBeChecked()
    expect(screen.getByRole('heading', { level: 1, name: 'Criar objetivo' })).toBeInTheDocument()

    const preview = screen.getByRole('complementary', { name: 'Previsão' })
    expect(await within(preview).findByText('mar/2027')).toBeInTheDocument()
    expect(within(preview).getByText('Simulado')).toBeInTheDocument()
    expect(within(preview).getByText('Dentro do prazo de mar/2027')).toBeInTheDocument()
    expect(within(preview).getByText('out/2026')).toBeInTheDocument()
    expect(within(preview).getByText('≈ 5%')).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('Conta do objetivo'), TRIP_ACCOUNT)
    expect(await axeViolations(container)).toEqual([])
    await userEvent.click(screen.getByRole('button', { name: 'Criar objetivo' }))
    const create = calls.find((c) => c.method === 'POST' && c.url === '/api/bff/goals')!
    expect(body(create.init)).toEqual({
      name: 'Guardar a diferença em alimentação fora',
      type: 'CUSTOM',
      targetAmount: brl('1800.00'),
      plannedMonthly: brl('300.00'),
      deadline: '2027-03',
      sourceAccountId: null,
      destinationAccountId: TRIP_ACCOUNT,
      origin: { kind: 'INSIGHT', insightId: SPIKE_ID },
    })
    await waitFor(() => expect(push).toHaveBeenCalledWith('/goals?created=new-goal&first=2026-10'))
  })

  it('reserva pelo Health: tipo reserva, alvo sugerido preenchido, "Já guardado" só leitura e sem conta do objetivo', async () => {
    const calls = stubBff(routes())
    render(<NewGoalView type="EMERGENCY_RESERVE" origin={{ kind: 'HEALTH' }} />)
    expect(await screen.findByText(/Criado a partir da sua/)).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Reserva de emergência/ })).toBeChecked()
    expect(await screen.findByDisplayValue('25.152,00')).toBeInTheDocument()
    expect(screen.getByText(/6 meses da sua despesa média/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Conta do objetivo')).not.toBeInTheDocument()
    expect(screen.getByText('R$ 10.000,00')).toBeInTheDocument()
    // Origem dos aportes da reserva não pode ser poupança.
    const sources = within(screen.getByLabelText(/Conta de origem dos aportes/)).getAllByRole('option').map((o) => o.textContent)
    expect(sources).not.toContain('Banco Norte · Reserva')
    await userEvent.type(screen.getByLabelText('Aporte mensal planejado'), '500')
    await userEvent.click(screen.getByRole('button', { name: 'Criar objetivo' }))
    const create = calls.find((c) => c.method === 'POST' && c.url === '/api/bff/goals')!
    expect(body(create.init)).toMatchObject({ type: 'EMERGENCY_RESERVE', destinationAccountId: null, origin: { kind: 'HEALTH' } })
  })

  it('reserva já existe: o tipo fica indisponível; erro por campo volta com foco', async () => {
    stubBff({
      ...routes(),
      'GET /api/bff/goals': () => json(goalList([reserveGoal()])),
      'POST /api/bff/goals': () => json({ code: 'GOAL_DESTINATION_IN_USE' }, 409),
    })
    render(<NewGoalView />)
    expect(await screen.findByRole('radio', { name: /Reserva de emergência/ })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Criar objetivo' }))
    expect(screen.getByText('Escolha o tipo do objetivo.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('radio', { name: /Compra/ }))
    await userEvent.type(screen.getByLabelText('Nome do objetivo'), 'Notebook')
    await userEvent.type(screen.getByLabelText('Valor alvo'), '6000')
    await userEvent.type(screen.getByLabelText('Aporte mensal planejado'), '0')
    await userEvent.selectOptions(screen.getByLabelText('Conta do objetivo'), TRIP_ACCOUNT)
    await userEvent.click(screen.getByRole('button', { name: 'Criar objetivo' }))
    expect(await screen.findByText('Essa conta já guarda outro objetivo. Escolha outra.')).toBeInTheDocument()
    expect(screen.getByLabelText('Conta do objetivo')).toHaveFocus()
    expect(push).not.toHaveBeenCalled()
  })
})

describe('/goals/:id/edit', () => {
  it('salva com o corpo do contrato e pausa só depois de confirmar', async () => {
    const calls = stubBff({
      ...accountRoute,
      'GET /api/bff/goals': () => json(goalList([goal()])),
      [`GET /api/bff/goals/${TRIP_ID}`]: () => json(goal()),
      [`PUT /api/bff/goals/${TRIP_ID}`]: () => json(goal({ name: 'Lisboa e Porto' })),
      [`POST /api/bff/goals/${TRIP_ID}/pause`]: () => json(goal({ status: 'PAUSED' })),
    })
    const { container } = render(<EditGoalView id={TRIP_ID} />)
    const name = await screen.findByLabelText('Nome do objetivo')
    expect(screen.getByText('Compra')).toBeInTheDocument()
    expect(screen.getByLabelText('Valor alvo')).toHaveValue('12.000,00')
    expect(screen.getByLabelText('Conta do objetivo')).toHaveValue(TRIP_ACCOUNT)
    expect(await axeViolations(container)).toEqual([])
    await userEvent.clear(name)
    await userEvent.type(name, 'Lisboa e Porto')
    await userEvent.click(screen.getByRole('button', { name: 'Salvar alterações' }))
    const put = calls.find((c) => c.method === 'PUT')!
    expect(body(put.init)).toEqual({
      name: 'Lisboa e Porto',
      targetAmount: brl('12000.00'),
      plannedMonthly: brl('700.00'),
      deadline: '2027-07',
      sourceAccountId: AURORA,
      destinationAccountId: TRIP_ACCOUNT,
    })
    await waitFor(() => expect(push).toHaveBeenCalledWith(`/goals/${TRIP_ID}`))

    push.mockReset()
    await userEvent.click(screen.getByRole('button', { name: 'Pausar objetivo' }))
    expect(calls.some((c) => c.url.endsWith('/pause'))).toBe(false)
    expect(screen.getByText(/A previsão fica suspensa até você retomar/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }))
    await waitFor(() => expect(push).toHaveBeenCalledWith(`/goals/${TRIP_ID}`))
  })
})

describe('formatação dos Objetivos', () => {
  it('meses, badges e valores de campo', () => {
    expect(monthYear('2027-07')).toBe('jul/2027')
    expect(monthYear(null)).toBe('—')
    expect(statusBadge(goal())?.label).toBe('1 mês após o prazo')
    expect(statusBadge(goal())?.short).toBe('+1 mês')
    expect(statusBadge(goal({ forecast: { ...goal().forecast, status: 'LATE', monthsLate: 3 } }))?.label).toBe('3 meses após o prazo')
    expect(statusBadge(reserveGoal())?.label).toBe('Sem prazo')
    expect(statusBadge(goal({ status: 'PAUSED' }))?.label).toBe('Pausado')
    expect(statusBadge(goal({ forecast: { ...goal().forecast, status: 'NO_FORECAST', reason: 'NO_PLAN', completion: null } }))).toBeNull()
    expect(amountToInput('25152.00')).toBe('25.152,00')
    expect(amountToInput('300.5')).toBe('300,50')
    expect(parsePlanned('0')).toEqual({ ok: true, value: '0.00' })
    expect(parsePlanned('-1').ok).toBe(false)
  })
})
