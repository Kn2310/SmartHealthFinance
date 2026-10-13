import type {
  AnalysisCategories,
  AnalysisCategoryDetail,
  AnalysisEvolution,
  AnalysisIncome,
  AnalysisMerchants,
  AnalysisMeta,
  AnalysisOverview,
  AnalysisSpending,
} from '@/lib/api/types'

/** Respostas de exemplo da API de Análise (valores de design/specs/09-dados-de-exemplo.md, fictícios). */

const brl = (amount: string) => ({ amount, currency: 'BRL' })

export const meta = (overrides: Partial<AnalysisMeta> = {}): AnalysisMeta => ({
  workspaceId: 'ws',
  state: 'READY',
  period: { month: '2026-09', from: '2026-09-01', to: '2026-09-22', partial: true, daysElapsed: 22, daysInMonth: 30 },
  comparison: {
    selected: 'PREVIOUS',
    previous: { from: '2026-08-01', to: '2026-08-22' },
    average: { requestedMonths: 5, monthsUsed: 5, months: ['2026-04', '2026-05', '2026-06', '2026-07', '2026-08'], state: 'AVAILABLE' },
  },
  freshness: { state: 'UP_TO_DATE', refreshedAt: '2026-09-22T12:00:00Z', pendingEvents: 0 },
  ...overrides,
})

const months = [
  ['2026-04', '6500.00', '4020.00'],
  ['2026-05', '6500.00', '4310.00'],
  ['2026-06', '6500.00', '3980.00'],
  ['2026-07', '6800.00', '4460.00'],
  ['2026-08', '6500.00', '4190.00'],
  ['2026-09', '6800.00', '3846.20'],
].map(([month, income, expense], i) => ({
  month: month!,
  partial: i === 5,
  income: brl(income!),
  expense: brl(expense!),
  refunds: brl('0.00'),
  net: brl((Number(income) - Number(expense)).toFixed(2)),
}))

const variation = (base: string, difference: string, percent: string | null, trend: 'UP' | 'DOWN' | 'STABLE') =>
  ({ base: brl(base), difference: brl(difference), percent, trend })

const categories = [
  { categoryCode: 'HOUSING', amount: brl('1986.32'), share: '51.6', transactionCount: 2, refunds: brl('0.00'),
    vsPrevious: variation('1980.00', '6.32', '0.3', 'STABLE'), vsAverage: variation('1980.00', '6.32', '0.3', 'STABLE') },
  { categoryCode: 'DINING_OUT', amount: brl('1284.00'), share: '33.4', transactionCount: 25, refunds: brl('0.00'),
    vsPrevious: variation('662.10', '621.90', '93.9', 'UP'), vsAverage: variation('973.00', '311.00', '32.0', 'UP') },
  { categoryCode: 'GROCERIES', amount: brl('296.08'), share: '7.7', transactionCount: 1, refunds: brl('0.00'),
    vsPrevious: null, vsAverage: variation('321.83', '-25.75', '-8.0', 'DOWN') },
  { categoryCode: null, amount: brl('19.90'), share: '0.5', transactionCount: 1, refunds: brl('0.00'),
    vsPrevious: null, vsAverage: null },
]

export const overview = (overrides: Partial<AnalysisOverview> = {}): AnalysisOverview => ({
  ...meta(),
  income: { amount: brl('6800.00'), vsPrevious: variation('6500.00', '300.00', '4.6', 'STABLE'), vsAverage: null },
  expense: { amount: brl('3846.20'), vsPrevious: variation('3372.10', '474.10', '14.1', 'UP'), vsAverage: null },
  refunds: { amount: brl('0.00'), vsPrevious: null, vsAverage: null },
  net: { amount: brl('2953.80'), vsPrevious: null, vsAverage: null },
  savedShare: '43.4',
  months,
  averageExpense: brl('4192.00'),
  composition: {
    income: brl('6800.00'),
    refunds: brl('0.00'),
    inflows: brl('6800.00'),
    outflows: [
      { group: 'CATEGORY', categoryCode: 'HOUSING', amount: brl('1986.32'), categories: 1 },
      { group: 'CATEGORY', categoryCode: 'DINING_OUT', amount: brl('1284.00'), categories: 1 },
      { group: 'CATEGORY', categoryCode: 'GROCERIES', amount: brl('296.08'), categories: 1 },
      { group: 'CATEGORY', categoryCode: 'TRANSPORT', amount: brl('220.00'), categories: 1 },
      { group: 'OTHERS', categoryCode: null, amount: brl('59.80'), categories: 2 },
    ],
    net: brl('2953.80'),
  },
  changes: {
    currentNet: brl('2953.80'),
    baseNet: brl('3127.90'),
    netDelta: brl('-174.10'),
    items: [
      { kind: 'EXPENSE_CATEGORY', categoryCode: 'DINING_OUT', current: brl('1284.00'), base: brl('662.10'),
        delta: brl('621.90'), impact: brl('-621.90'), merchants: [] },
      { kind: 'INCOME', categoryCode: null, current: brl('6800.00'), base: brl('6500.00'), delta: brl('300.00'),
        impact: brl('300.00'), merchants: [] },
    ],
  },
  topCategories: categories.slice(0, 3),
  ...overrides,
})

export const spending = (): AnalysisSpending => ({
  ...meta(),
  total: { amount: brl('3846.20'), vsPrevious: variation('3372.10', '474.10', '14.1', 'UP'), vsAverage: null },
  refunds: brl('0.00'),
  dailyAverage: brl('174.83'),
  previousDailyAverage: brl('153.28'),
  cumulative: [{ day: 1, amount: brl('0.00') }, { day: 2, amount: brl('1800.00') }],
  previousCumulative: [{ day: 1, amount: brl('0.00') }, { day: 2, amount: brl('1800.00') }, { day: 3, amount: brl('1900.00') }],
  largestExpenses: [{ transactionId: '01922f5e-0000-7000-8000-0000000000c1', occurredOn: '2026-09-05',
    description: 'Aluguel - Imobiliária Exemplo', merchantName: 'Imobiliária Exemplo', categoryCode: 'HOUSING',
    amount: brl('1800.00') }],
  categories,
})

export const income = (): AnalysisIncome => ({
  ...meta(),
  total: { amount: brl('6800.00'), vsPrevious: variation('6500.00', '300.00', '4.6', 'STABLE'), vsAverage: null },
  monthlyAverage: brl('6560.00'),
  sources: [
    { merchantKey: 'empresa ficticia', merchantName: 'Empresa Fictícia', categoryCode: 'SALARY', amount: brl('6500.00'),
      share: '95.6', transactionCount: 1, recurrence: 'RECURRING', typicalDay: 5, lastReceivedOn: '2026-09-05' },
    { merchantKey: 'estudio ponto', merchantName: 'Estúdio Ponto', categoryCode: null, amount: brl('300.00'),
      share: '4.4', transactionCount: 1, recurrence: 'OCCASIONAL', typicalDay: null, lastReceivedOn: '2026-09-18' },
  ],
  months,
})

export const categoriesView = (): AnalysisCategories => ({ ...meta(), totalExpense: brl('3846.20'), categories })

export const categoryDetail = (): AnalysisCategoryDetail => ({
  ...meta(),
  categoryCode: 'DINING_OUT',
  nature: 'EXPENSE',
  amount: { amount: brl('1284.00'), vsPrevious: null, vsAverage: variation('973.00', '311.00', '32.0', 'UP') },
  transactionCount: 25,
  averageTicket: brl('51.36'),
  history: months.map((m) => ({ month: m.month, partial: m.partial, amount: brl('900.00'), transactionCount: 20 })),
  merchants: [{ merchantKey: 'entrega ja', merchantName: 'Entrega Já', amount: brl('447.00'), transactionCount: 9 }],
})

export const merchants = (sort: 'AMOUNT' | 'COUNT' = 'AMOUNT'): AnalysisMerchants => ({
  ...meta(),
  sort,
  merchants: [
    { merchantKey: 'imobiliaria', merchantName: 'Imobiliária Exemplo', categoryCode: 'HOUSING', transactionCount: 1,
      amount: brl('1800.00'), vsPrevious: null, vsAverage: variation('1800.00', '0.00', '0.0', 'STABLE'), lastPurchaseOn: '2026-09-05' },
    { merchantKey: 'entrega ja', merchantName: 'Entrega Já', categoryCode: 'DINING_OUT', transactionCount: 9,
      amount: brl('447.00'), vsPrevious: null, vsAverage: variation('283.00', '164.00', '58.0', 'UP'), lastPurchaseOn: '2026-09-20' },
  ],
  unidentified: brl('19.90'),
})

export const evolution = (): AnalysisEvolution => ({ ...meta(), months })
