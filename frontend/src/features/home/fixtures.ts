import type { MoneyDto, Overview } from '@/lib/api/types'

export const brl = (amount: string): MoneyDto => ({ amount, currency: 'BRL' })

/** Overview READY sintético (nenhum dado real). */
export function readyOverview(overrides: Partial<Overview> = {}): Overview {
  return {
    workspaceId: '00000000-0000-0000-0000-000000000001',
    state: 'READY',
    period: { type: 'CURRENT_MONTH', from: '2026-09-01', to: '2026-09-27' },
    summary: { totalBalance: brl('7627.52'), balanceAsOf: '2026-09-27', accountCount: 2, pendingTransactions: 1 },
    cashFlow: {
      income: brl('6800.00'),
      expense: brl('3846.20'),
      refunds: brl('50.00'),
      net: brl('3003.80'),
      transfers: { count: 1, volume: brl('500.00') },
    },
    accounts: [
      {
        id: 'a1',
        name: 'Conta Aurora',
        type: 'CHECKING',
        institutionName: 'Banco Aurora',
        includedInTotal: true,
        balance: brl('5127.52'),
        movementCount: 4,
      },
      {
        id: 'a2',
        name: 'Reserva',
        type: 'SAVINGS',
        institutionName: null,
        includedInTotal: false,
        balance: brl('2500.00'),
        movementCount: 1,
      },
    ],
    recentTransactions: [
      {
        id: 't1',
        type: 'INCOME',
        flow: 'INFLOW',
        adjustmentDirection: null,
        status: 'POSTED',
        amount: brl('6500.00'),
        occurredOn: '2026-09-05',
        description: 'Salário',
        account: { id: 'a1', name: 'Conta Aurora' },
        destinationAccount: null,
        refundOfTransactionId: null,
        source: 'MANUAL',
      },
      {
        id: 't2',
        type: 'EXPENSE',
        flow: 'OUTFLOW',
        adjustmentDirection: null,
        status: 'POSTED',
        amount: brl('420.00'),
        occurredOn: '2026-09-12',
        description: 'Mercado',
        account: { id: 'a1', name: 'Conta Aurora' },
        destinationAccount: null,
        refundOfTransactionId: null,
        source: 'MANUAL',
      },
      {
        id: 't3',
        type: 'TRANSFER',
        flow: 'TRANSFER',
        adjustmentDirection: null,
        status: 'POSTED',
        amount: brl('500.00'),
        occurredOn: '2026-09-15',
        description: null,
        account: { id: 'a1', name: 'Conta Aurora' },
        destinationAccount: { id: 'a2', name: 'Reserva' },
        refundOfTransactionId: null,
        source: 'MANUAL',
      },
      {
        id: 't4',
        type: 'REFUND',
        flow: 'INFLOW',
        adjustmentDirection: null,
        status: 'PENDING',
        amount: brl('50.00'),
        occurredOn: '2026-09-20',
        description: 'Estorno loja',
        account: { id: 'a1', name: 'Conta Aurora' },
        destinationAccount: null,
        refundOfTransactionId: 't2',
        source: 'MANUAL',
      },
    ],
    ...overrides,
  }
}

export function noAccountsOverview(): Overview {
  return readyOverview({
    state: 'NO_ACCOUNTS',
    summary: { totalBalance: null, balanceAsOf: '2026-09-27', accountCount: 0, pendingTransactions: 0 },
    cashFlow: null,
    accounts: [],
    recentTransactions: [],
  })
}

export function noTransactionsOverview(): Overview {
  const ready = readyOverview()
  return readyOverview({
    state: 'NO_TRANSACTIONS',
    summary: { totalBalance: null, balanceAsOf: '2026-09-27', accountCount: 2, pendingTransactions: 0 },
    cashFlow: null,
    accounts: ready.accounts.map((a) => ({ ...a, balance: null, movementCount: 0 })),
    recentTransactions: [],
  })
}

export function noActivityOverview(): Overview {
  return readyOverview({
    state: 'NO_ACTIVITY_IN_PERIOD',
    period: { type: 'PREVIOUS_MONTH', from: '2026-08-01', to: '2026-08-31' },
    summary: { totalBalance: brl('1200.00'), balanceAsOf: '2026-08-31', accountCount: 2, pendingTransactions: 0 },
    cashFlow: {
      income: brl('0.00'),
      expense: brl('0.00'),
      refunds: brl('0.00'),
      net: brl('0.00'),
      transfers: { count: 0, volume: brl('0.00') },
    },
    recentTransactions: [],
  })
}
