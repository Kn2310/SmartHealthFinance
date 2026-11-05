import type {
  ForecastFreshness,
  ForecastInvoice,
  ForecastInvoicesResponse,
  ForecastLimitation,
  ForecastMonthResponse,
  ForecastNextIncomeResponse,
  ForecastUpcomingResponse,
  ForecastValue,
  MoneyDto,
} from '@/lib/api/types'

/**
 * Fixtures sintéticas do Forecast com os números do 09-dados: projeção do mês R$ 4.664,10 (realizado 3.846,20 +
 * 817,90 estimados) e a fatura Aurora de novembro (480,00 comprometido + 2.532,10 de novas compras).
 */
export const brl = (amount: string): MoneyDto => ({ amount, currency: 'BRL' })

export const AURORA_CARD = '01922f5e-0000-7000-8000-0000000000c1'
export const SERIES_SALARY = '01922f5e-0000-7000-8000-0000000000d1'
export const SERIES_RENT = '01922f5e-0000-7000-8000-0000000000d2'

const fresh = (state: ForecastFreshness['state'] = 'UP_TO_DATE'): ForecastFreshness => ({
  state,
  computedAt: state === 'NOT_BUILT' ? null : '2026-09-27T12:00:00Z',
  asOf: state === 'NOT_BUILT' ? null : '2026-09-27',
})

export function fact(amount: string, partial = false): ForecastValue {
  return {
    basis: 'FACT',
    value: brl(amount),
    factPart: brl(amount),
    estimatedPart: brl('0.00'),
    range: { low: brl(amount), high: brl(amount) },
    partial,
    confidence: 'HIGH',
    limitations: [],
    evidence: [],
  }
}

export function estimate(
  value: string,
  low: string,
  high: string,
  options: { factPart?: string; confidence?: ForecastValue['confidence']; limitations?: ForecastLimitation[]; months?: number } = {},
): ForecastValue {
  const factPart = options.factPart ?? '0.00'
  const months = options.months ?? 3
  return {
    basis: 'ESTIMATED',
    value: brl(value),
    factPart: brl(factPart),
    estimatedPart: brl((Number(value) - Number(factPart)).toFixed(2)),
    range: { low: brl(low), high: brl(high) },
    partial: false,
    confidence: options.confidence ?? 'MEDIUM',
    limitations: options.limitations ?? [],
    evidence: Array.from({ length: months }, (_, i) => ({
      kind: 'SAMPLE_MONTH' as const,
      month: `2026-0${6 + i}`,
      refId: null,
      value: brl('1000.00'),
    })),
  }
}

export function readyMonth(overrides: Partial<ForecastMonthResponse> = {}): ForecastMonthResponse {
  return {
    state: 'READY',
    freshness: fresh(),
    algorithmVersion: 'forecast-v1',
    asOf: '2026-09-27',
    month: {
      month: '2026-09',
      asOf: '2026-09-27',
      baseMonths: 3,
      expense: estimate('4664.10', '4400.00', '5100.00', { factPart: '3846.20' }),
      income: estimate('10690.00', '10690.00', '10690.00', { factPart: '6800.00' }),
      net: estimate('6025.90', '5590.00', '6290.00'),
      pending: [],
    },
    ...overrides,
  }
}

export const notBuiltMonth = (): ForecastMonthResponse => ({
  state: 'NOT_BUILT',
  freshness: fresh('NOT_BUILT'),
  algorithmVersion: 'forecast-v1',
  asOf: null,
  month: null,
})

export function readyNextIncome(overrides: Partial<ForecastNextIncomeResponse> = {}): ForecastNextIncomeResponse {
  return {
    state: 'READY',
    freshness: fresh(),
    algorithmVersion: 'forecast-v1',
    asOf: '2026-09-27',
    nextIncome: {
      seriesId: SERIES_SALARY,
      label: 'Salário',
      date: '2026-10-05',
      channel: { kind: 'ACCOUNT', id: 'a1' },
      amount: estimate('6500.00', '6500.00', '6500.00', { confidence: 'HIGH' }),
    },
    ...overrides,
  }
}

export function readyUpcoming(overrides: Partial<ForecastUpcomingResponse> = {}): ForecastUpcomingResponse {
  return {
    state: 'READY',
    freshness: fresh(),
    algorithmVersion: 'forecast-v1',
    asOf: '2026-09-27',
    events: [
      {
        kind: 'INVOICE_PARTIAL',
        date: '2026-09-28',
        seriesId: null,
        label: 'Aurora Platinum',
        channel: { kind: 'CARD', id: 'c1' },
        value: fact('3412.80', true),
        closingEstimate: estimate('3800.00', '3500.00', '4200.00', { factPart: '3412.80' }),
      },
      {
        kind: 'RECURRING_INCOME',
        date: '2026-10-05',
        seriesId: SERIES_SALARY,
        label: 'Salário',
        channel: { kind: 'ACCOUNT', id: 'a1' },
        value: estimate('6500.00', '6500.00', '6500.00', { confidence: 'HIGH' }),
        closingEstimate: null,
      },
      {
        kind: 'RECURRING_EXPENSE',
        date: '2026-10-07',
        seriesId: SERIES_RENT,
        label: 'Aluguel',
        channel: { kind: 'ACCOUNT', id: 'a1' },
        value: estimate('1800.00', '1800.00', '1800.00', { confidence: 'HIGH' }),
        closingEstimate: null,
      },
    ],
    ...overrides,
  }
}

export const notBuiltUpcoming = (): ForecastUpcomingResponse => ({
  state: 'NOT_BUILT',
  freshness: fresh('NOT_BUILT'),
  algorithmVersion: 'forecast-v1',
  asOf: null,
  events: [],
})

/** Fatura aberta (parcial 1.480,00 + fechamento estimado) e as futuras: comprometido (fato) + novas compras. */
export function readyInvoices(overrides: Partial<ForecastInvoicesResponse> = {}): ForecastInvoicesResponse {
  const base = {
    cardId: AURORA_CARD,
    nickname: 'Aurora Platinum',
  }
  const invoices: ForecastInvoice[] = [
    {
      ...base,
      kind: 'OPEN',
      closingMonth: '2026-10',
      dueMonth: '2026-10',
      closingDate: '2026-10-28',
      dueDate: '2026-11-05',
      total: estimate('3800.00', '3500.00', '4200.00', { factPart: '1480.00' }),
    },
    {
      ...base,
      kind: 'NEXT',
      closingMonth: '2026-11',
      dueMonth: '2026-11',
      closingDate: '2026-11-28',
      dueDate: '2026-12-05',
      total: estimate('3012.10', '2800.00', '3300.00', { factPart: '480.00', months: 3 }),
    },
    {
      ...base,
      kind: 'FUTURE',
      closingMonth: '2026-12',
      dueMonth: '2026-12',
      closingDate: '2026-12-28',
      dueDate: '2027-01-05',
      total: estimate('2900.00', '2700.00', '3200.00', { factPart: '480.00', limitations: ['SHORT_HISTORY'] }),
    },
  ]
  return {
    state: 'READY',
    freshness: fresh(),
    algorithmVersion: 'forecast-v1',
    asOf: '2026-10-22',
    invoices,
    ...overrides,
  }
}

/** Cartão com poucas faturas acompanhadas: só o comprometido, sem estimativa de novas compras. */
export function partialCardInvoices(): ForecastInvoicesResponse {
  const ready = readyInvoices()
  return {
    ...ready,
    invoices: ready.invoices.map((invoice) => ({
      ...invoice,
      total: { ...fact(invoice.total.factPart.amount), limitations: ['PARTIAL_CARD_HISTORY' as const] },
    })),
  }
}

export const insufficientInvoices = (): ForecastInvoicesResponse => ({
  ...readyInvoices(),
  state: 'INSUFFICIENT_HISTORY',
  invoices: readyInvoices().invoices.map((invoice) => ({ ...invoice, total: fact(invoice.total.factPart.amount) })),
})

export const notBuiltInvoices = (): ForecastInvoicesResponse => ({
  state: 'NOT_BUILT',
  freshness: fresh('NOT_BUILT'),
  algorithmVersion: 'forecast-v1',
  asOf: null,
  invoices: [],
})

export const recalculating = <T extends { freshness: ForecastFreshness }>(response: T): T => ({
  ...response,
  freshness: fresh('RECALCULATING'),
})
