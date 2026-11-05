'use client'

import { useState } from 'react'
import type { ForecastRecurringAction, ForecastRecurringList } from '@/lib/api/types'
import { bffCall } from '@/lib/bff-client'
import { useKeyed } from '@/lib/use-keyed'
import { fetchAllAccounts } from '@/features/accounts/api'

export type RecurringKind = 'expenses' | 'incomes'

const base = (kind: RecurringKind) => `/api/bff/recurring-${kind}`
const seriesPath = (kind: RecurringKind, id: string, ...rest: string[]) =>
  [base(kind), encodeURIComponent(id), ...rest].join('/')

/** Séries de uma direção (ADR-0032 §2); `replace` troca a lista local depois de uma ação. */
export function useRecurringList(kind: RecurringKind) {
  const [attempt, setAttempt] = useState(0)
  const { value, replace } = useKeyed<ForecastRecurringList>(`${kind}#${attempt}`, async () => {
    const result = await bffCall<ForecastRecurringList>(base(kind))
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })
  return { ...value, replace, retry: () => setAttempt((n) => n + 1) }
}

export const confirmSeries = (kind: RecurringKind, id: string) =>
  bffCall<ForecastRecurringAction>(seriesPath(kind, id, 'confirm'), { method: 'POST' })

export const ignoreSeries = (kind: RecurringKind, id: string) =>
  bffCall<ForecastRecurringAction>(seriesPath(kind, id, 'ignore'), { method: 'POST' })

export interface Adjustment {
  expectedAmount?: { amount: string; currency: string }
  expectedDay?: number
}

export const adjustSeries = (kind: RecurringKind, id: string, adjustment: Adjustment) =>
  bffCall<ForecastRecurringAction>(seriesPath(kind, id, 'adjustment'), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(adjustment),
  })

export const resetSeries = (kind: RecurringKind, id: string) =>
  bffCall<ForecastRecurringAction>(seriesPath(kind, id, 'adjustment'), { method: 'DELETE' })

/** `true` quando o Forecast terminou de recalcular (ou o status não pôde ser lido: então para de perguntar). */
export async function forecastSettled(): Promise<boolean> {
  const result = await bffCall<{ state: string }>('/api/bff/forecast/status')
  return !result.ok || result.data.state !== 'RECALCULATING'
}

/** Nomes das contas e dos cartões para o "canal" de cada série; falhas só deixam o nome genérico. */
export function useChannelNames() {
  const { value } = useKeyed<{ accounts: Map<string, string>; cards: Map<string, string> }>('channels', async () => {
    const [accounts, cards] = await Promise.all([
      fetchAllAccounts(),
      bffCall<{ cards: { card: { id: string; nickname: string } }[] }>('/api/bff/cards/summary'),
    ])
    return {
      status: 'success',
      data: {
        accounts: new Map(accounts.ok ? accounts.data.items.map((a) => [a.id, a.name]) : []),
        cards: new Map(cards.ok ? cards.data.cards.map((c) => [c.card.id, c.card.nickname]) : []),
      },
    }
  })
  return value.status === 'success' ? value.data : { accounts: new Map<string, string>(), cards: new Map<string, string>() }
}

export const fetchRecurring = (kind: RecurringKind) => bffCall<ForecastRecurringList>(base(kind))
