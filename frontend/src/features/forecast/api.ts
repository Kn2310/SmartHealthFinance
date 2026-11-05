'use client'

import { useState } from 'react'
import type {
  ForecastInvoicesResponse,
  ForecastMonthResponse,
  ForecastNextIncomeResponse,
  ForecastUpcomingResponse,
} from '@/lib/api/types'
import { bffCall } from '@/lib/bff-client'
import { useKeyed } from '@/lib/use-keyed'

/** Leituras do Forecast servidas pelo BFF (ADR-0033 §7); o corpo é o contrato do backend, sem recálculo. */
export interface ForecastReads {
  month: ForecastMonthResponse
  'next-income': ForecastNextIncomeResponse
  upcoming: ForecastUpcomingResponse
  invoices: ForecastInvoicesResponse
}
export type ForecastRead = keyof ForecastReads

export const fetchForecast = <V extends ForecastRead>(view: V, search = '') =>
  bffCall<ForecastReads[V]>(`/api/bff/forecast/${view}${search ? `?${search}` : ''}`)

/** Uma leitura = uma chamada ao BFF por chave; `enabled = false` desliga a chamada. */
function useForecast<V extends ForecastRead>(view: V, search = '', enabled = true) {
  const [attempt, setAttempt] = useState(0)
  const { value } = useKeyed<ForecastReads[V] | null>(`${enabled ? view : 'off'}?${search}#${attempt}`, async () => {
    if (!enabled) return { status: 'success', data: null }
    const result = await fetchForecast(view, search)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })
  return { ...value, retry: () => setAttempt((n) => n + 1) }
}

export const useForecastMonth = () => useForecast('month')
export const useForecastNextIncome = () => useForecast('next-income')
export const useForecastUpcoming = () => useForecast('upcoming')

/** `cardId` nulo desliga a chamada. */
export const useForecastInvoices = (cardId: string | null) =>
  useForecast('invoices', cardId ? `cardId=${encodeURIComponent(cardId)}` : '', cardId !== null)
