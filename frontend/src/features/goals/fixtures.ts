import type { Goal, GoalForecast, GoalList, GoalProjection, Simulation, SimulationScenario } from './api'

/** Fixtures dos Objetivos (09-dados-de-exemplo: Viagem para Lisboa e Reserva de emergência), tipos do schema.d.ts. */
export const brl = (amount: string) => ({ amount, currency: 'BRL' })

export const TRIP_ID = '01922f5e-0000-7000-8000-00000000a001'
export const RESERVE_ID = '01922f5e-0000-7000-8000-00000000a002'
export const AURORA = '01922f5e-0000-7000-8000-0000000000a1'
export const TRIP_ACCOUNT = '01922f5e-0000-7000-8000-0000000000a2'
export const NORTE = '01922f5e-0000-7000-8000-0000000000a3'

export const forecast = (o: Partial<GoalForecast> = {}): GoalForecast => ({
  basis: 'ESTIMATED',
  status: 'LATE',
  reason: null,
  completion: '2027-08',
  contributionsLeft: 11,
  monthsLate: 1,
  monthsEarly: null,
  range: { optimistic: '2027-08', pessimistic: '2027-10', openEnded: false },
  confidence: 'MEDIUM',
  realPace: brl('700.00'),
  freshness: 'UP_TO_DATE',
  ...o,
})

export const goal = (o: Partial<Goal> = {}): Goal => ({
  id: TRIP_ID,
  name: 'Viagem para Lisboa',
  type: 'PURCHASE',
  status: 'ACTIVE',
  origin: { kind: 'MANUAL', insightId: null },
  targetAmount: brl('12000.00'),
  plannedMonthly: brl('700.00'),
  deadline: '2027-07',
  sourceAccountId: AURORA,
  destination: { kind: 'ACCOUNT', accountId: TRIP_ACCOUNT },
  trackingStart: '2026-04-10',
  createdAt: '2026-04-10T12:00:00Z',
  completedAt: null,
  completedAmount: null,
  progress: {
    basis: 'FACT',
    saved: brl('4300.00'),
    percent: 36,
    remaining: brl('7700.00'),
    reached: false,
    destinationBalance: brl('4300.00'),
    destinationState: 'OK',
  },
  forecast: forecast(),
  ...o,
})

export const reserveGoal = (o: Partial<Goal> = {}): Goal =>
  goal({
    id: RESERVE_ID,
    name: 'Reserva de emergência',
    type: 'EMERGENCY_RESERVE',
    targetAmount: brl('25152.00'),
    plannedMonthly: brl('500.00'),
    deadline: null,
    sourceAccountId: AURORA,
    destination: { kind: 'RESERVE', accountId: null },
    progress: {
      basis: 'FACT',
      saved: brl('10000.00'),
      percent: 40,
      remaining: brl('15152.00'),
      reached: false,
      destinationBalance: brl('10000.00'),
      destinationState: 'OK',
    },
    forecast: forecast({ status: 'NO_DEADLINE', completion: '2028-04', monthsLate: null, contributionsLeft: 31 }),
    ...o,
  })

export const goalList = (goals: Goal[] = [reserveGoal(), goal()], o: Partial<GoalList['summary']> = {}): GoalList => ({
  summary: {
    savedTotal: brl('14300.00'),
    plannedMonthlyTotal: brl('1200.00'),
    activeCount: goals.filter((g) => g.status === 'ACTIVE').length,
    plannedIncomeShare: 18,
    forecastCount: 2,
    onTrackCount: 1,
    forecastFreshness: 'UP_TO_DATE',
    ...o,
  },
  goals,
})

export const scenario = (o: Partial<SimulationScenario> = {}): SimulationScenario => ({
  monthlyAmount: brl('770.00'),
  completion: '2027-07',
  contributions: 10,
  firstContribution: '2026-10',
  lastContribution: { month: '2027-07', amount: brl('770.00') },
  deadline: { month: '2027-07', within: true, monthsFromDeadline: 0 },
  requiredMonthlyForDeadline: brl('770.00'),
  incomeShare: 12,
  tooFar: false,
  reduction: null,
  ...o,
})

export const simulation = (scenarios: SimulationScenario[] = [scenario()]): Simulation => ({
  basis: 'SIMULATED',
  remaining: brl('7700.00'),
  start: '2026-10',
  averageIncome: brl('6560.00'),
  assumptions: ['EQUAL_MONTHLY_CONTRIBUTIONS', 'NO_YIELD'],
  scenarios,
})

export const projection = (o: Partial<GoalProjection> = {}): GoalProjection => ({
  target: brl('12000.00'),
  deadline: '2027-07',
  actual: {
    basis: 'FACT',
    points: [
      { month: '2026-04', amount: brl('100.00') },
      { month: '2026-09', amount: brl('4300.00') },
    ],
  },
  projected: {
    basis: 'ESTIMATED',
    points: [
      { month: '2026-09', amount: brl('4300.00') },
      { month: '2027-08', amount: brl('12000.00') },
    ],
  },
  forecast: forecast(),
  ...o,
})
