import type {
  Card,
  CardsSummary,
  FutureInvoices,
  InstallmentPlan,
  Invoice,
  InvoiceItemPage,
  InvoiceSummary,
  Utilization,
  UtilizationHistory,
} from './api'

/** Cartões de 09-dados-de-exemplo (Aurora e Horizonte), com hoje = 22 set 2026. Valores como o backend devolve. */
export const AURORA = '01922f5e-0000-7000-8000-0000000000c1'
export const HORIZONTE = '01922f5e-0000-7000-8000-0000000000c2'
export const NOTEBOOK = '01922f5e-0000-7000-8000-0000000000b1'

const brl = (amount: string) => ({ amount, currency: 'BRL' })

export const auroraCard = (overrides: Partial<Card> = {}): Card => ({
  id: AURORA,
  workspaceId: 'ws',
  nickname: 'Aurora Platinum',
  institutionName: 'Banco Aurora',
  brand: 'MASTERCARD',
  last4: '4821',
  limit: brl('12000.00'),
  closingDay: 28,
  dueDay: 5,
  defaultPaymentAccountId: null,
  status: 'ACTIVE',
  trackingStartDueMonth: '2026-10',
  createdAt: '2026-09-22T12:00:00Z',
  updatedAt: '2026-09-22T12:00:00Z',
  ...overrides,
})

export const octoberInvoice = (overrides: Partial<InvoiceSummary> = {}): InvoiceSummary => ({
  dueMonth: '2026-10',
  periodStart: '2026-08-28',
  closingDate: '2026-09-28',
  dueDate: '2026-10-05',
  effectiveDueDate: '2026-10-05',
  datesSource: 'CALCULATED',
  status: 'OPEN',
  charges: brl('3412.80'),
  credits: brl('0.00'),
  amount: brl('3412.80'),
  paid: brl('0.00'),
  remaining: brl('3412.80'),
  minimumPayment: null,
  partial: true,
  ...overrides,
})

export const auroraUtilization = (): Utilization => ({
  limit: brl('12000.00'),
  utilized: brl('6772.80'),
  available: brl('5227.20'),
  credit: brl('0.00'),
  utilizationPercent: 56,
  utilizationWithoutFuturePercent: 28,
  composition: { previousOutstanding: brl('0.00'), currentInvoice: brl('3412.80'), futureInstallments: brl('3360.00') },
  asOf: '2026-09-22',
  calculationVersion: 'invoice-v2',
})

export const summary = (): CardsSummary => ({
  totalLimit: brl('18000.00'),
  utilized: brl('7793.20'),
  openInvoices: brl('4433.20'),
  nextDueDate: '2026-10-05',
  cards: [
    { card: auroraCard(), currentInvoice: octoberInvoice(), utilization: auroraUtilization(), nextInvoiceCommitted: brl('480.00') },
  ],
  asOf: '2026-09-22',
  calculationVersion: 'invoice-v2',
})

export const history = (): UtilizationHistory => ({
  points: [
    { date: '2026-07-31', utilized: brl('5640.00'), limit: brl('12000.00'), utilizationPercent: 47, current: false },
    { date: '2026-08-31', utilized: brl('5880.00'), limit: brl('12000.00'), utilizationPercent: 49, current: false },
    { date: '2026-09-22', utilized: brl('6772.80'), limit: brl('12000.00'), utilizationPercent: 56, current: true },
  ],
  asOf: '2026-09-22',
  calculationVersion: 'invoice-v2',
})

export const invoice = (overrides: Partial<InvoiceSummary> = {}): Invoice => ({
  invoice: octoberInvoice(overrides),
  itemCount: 2,
  asOf: '2026-09-22',
  calculationVersion: 'invoice-v2',
})

export const items = (): InvoiceItemPage => ({
  items: [
    {
      kind: 'CHARGE',
      transactionId: '01922f5e-0000-7000-8000-0000000000b2',
      occurredOn: '2026-09-22',
      description: 'BISTRO LUME',
      categoryCode: 'DINING_OUT',
      merchantName: 'Bistrô Lume',
      source: 'IMPORT',
      installment: { number: 1, count: 1 },
      amount: brl('86.40'),
      cancelledAmount: brl('0.00'),
    },
    {
      kind: 'CHARGE',
      transactionId: NOTEBOOK,
      occurredOn: '2026-07-12',
      description: 'Notebook',
      categoryCode: 'SHOPPING',
      merchantName: 'Loja Tech',
      source: 'MANUAL',
      installment: { number: 3, count: 10 },
      amount: brl('480.00'),
      cancelledAmount: brl('0.00'),
    },
  ],
  page: 0,
  pageSize: 20,
  totalItems: 2,
})

export const future = (): FutureInvoices => ({
  months: [
    {
      dueMonth: '2026-11',
      committed: brl('480.00'),
      installments: [{ transactionId: NOTEBOOK, description: 'Notebook', number: 4, count: 10, amount: brl('480.00') }],
    },
    {
      dueMonth: '2026-12',
      committed: brl('480.00'),
      installments: [{ transactionId: NOTEBOOK, description: 'Notebook', number: 5, count: 10, amount: brl('480.00') }],
    },
  ],
  totalCommitted: brl('960.00'),
  asOf: '2026-09-22',
  calculationVersion: 'invoice-v2',
})

const MONTHS = ['2026-08', '2026-09', '2026-10', '2026-11', '2026-12', '2027-01', '2027-02', '2027-03', '2027-04', '2027-05']

/** Aurora cadastrado em 22 set: parcelas 1–2 antes do acompanhamento (ADR-0025 §15), 3 na fatura aberta. */
export const notebookPlan = (): InstallmentPlan => ({
  transactionId: NOTEBOOK,
  description: 'Notebook',
  purchasedOn: '2026-07-12',
  total: brl('4800.00'),
  count: 10,
  installmentAmount: brl('480.00'),
  paidCount: 0,
  paidAmount: brl('0.00'),
  currentNumber: 3,
  remainingAfterCurrent: brl('3360.00'),
  lastDueMonth: '2027-05',
  status: 'ACTIVE',
  installments: MONTHS.map((dueMonth, i) => ({
    number: i + 1,
    dueMonth,
    amount: brl('480.00'),
    cancelledAmount: brl('0.00'),
    status: i < 2 ? 'BEFORE_TRACKING' : i === 2 ? 'CURRENT' : 'FUTURE',
  })),
  reservedLimit: MONTHS.slice(2).map((dueMonth, i) => ({ dueMonth, amount: brl(`${(8 - i) * 480}.00`) })),
  currentInvoiceShare: { dueMonth: '2026-10', installment: brl('480.00'), invoiceAmount: brl('3412.80'), percent: 14 },
})
