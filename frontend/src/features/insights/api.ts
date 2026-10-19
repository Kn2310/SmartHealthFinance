'use client'

import { useState } from 'react'
import type { InsightDetail, InsightFeedback, InsightHighlights, InsightItem, InsightList, InsightRelated } from '@/lib/api/types'
import { bffCall, type Result } from '@/lib/bff-client'
import { useKeyed } from '@/lib/use-keyed'

/** Filtros da lista na URL (chips): Todos, Importantes, Gastos e Saúde; Cartões fica desabilitado até o M8. */
export type InsightFilter = 'ALL' | 'IMPORTANT' | 'SPENDING' | 'HEALTH'

const FILTER_QUERY: Record<InsightFilter, string> = {
  ALL: '',
  IMPORTANT: 'importance=IMPORTANT',
  SPENDING: 'type=SPENDING',
  HEALTH: 'type=HEALTH',
}

/** Uma chamada ao BFF por chave; trocar os parâmetros muda a chave e recarrega. */
function useInsightView<T>(path: string | null) {
  const [attempt, setAttempt] = useState(0)
  const { value, replace } = useKeyed<T | null>(`${path}#${attempt}`, async () => {
    if (!path) return { status: 'success', data: null }
    const result = await bffCall<T>(path)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })
  return { ...value, replace, retry: () => setAttempt((n) => n + 1) }
}

/** Uma página grande o bastante para a lista inteira do mês (o backend aceita até 50). */
export function useInsightList(filter: InsightFilter) {
  const query = FILTER_QUERY[filter]
  return useInsightView<InsightList>(`/api/bff/insights?pageSize=50${query ? `&${query}` : ''}`)
}

export function useInsightDetail(id: string) {
  return useInsightView<InsightDetail>(`/api/bff/insights/${encodeURIComponent(id)}`)
}

export function useInsightHighlights() {
  return useInsightView<InsightHighlights>('/api/bff/insights/highlights')
}

/** `null` desliga a chamada (ex.: transação ainda carregando). */
export function useRelatedInsights(params: { transactionId: string } | { dimension: string } | null) {
  const search = params ? new URLSearchParams(params as Record<string, string>).toString() : null
  return useInsightView<InsightRelated>(search ? `/api/bff/insights/related?${search}` : null)
}

export function markInsightSeen(id: string): Promise<Result<InsightItem>> {
  return bffCall<InsightItem>(`/api/bff/insights/${encodeURIComponent(id)}/seen`, { method: 'POST' })
}

export function dismissInsight(id: string): Promise<Result<InsightItem>> {
  return bffCall<InsightItem>(`/api/bff/insights/${encodeURIComponent(id)}/dismiss`, { method: 'POST' })
}

export function sendInsightFeedback(id: string, value: InsightFeedback | null): Promise<Result<InsightItem>> {
  return bffCall<InsightItem>(`/api/bff/insights/${encodeURIComponent(id)}/feedback`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ value }),
  })
}
