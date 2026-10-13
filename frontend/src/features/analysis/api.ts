'use client'

import { useState } from 'react'
import type {
  AnalysisCategories,
  AnalysisCategoryDetail,
  AnalysisEvolution,
  AnalysisIncome,
  AnalysisMerchants,
  AnalysisOverview,
  AnalysisSpending,
} from '@/lib/api/types'
import { bffCall } from '@/lib/bff-client'
import { useKeyed } from '@/lib/use-keyed'

export interface AnalysisResponses {
  overview: AnalysisOverview
  spending: AnalysisSpending
  income: AnalysisIncome
  categories: AnalysisCategories
  merchants: AnalysisMerchants
  evolution: AnalysisEvolution
}
export type AnalysisView = keyof AnalysisResponses

export const fetchAnalysis = <V extends AnalysisView>(view: V, search: string) =>
  bffCall<AnalysisResponses[V]>(`/api/bff/analytics/${view}${search ? `?${search}` : ''}`)

export const fetchCategoryDetail = (code: string, search: string) =>
  bffCall<AnalysisCategoryDetail>(`/api/bff/analytics/categories/${encodeURIComponent(code)}${search ? `?${search}` : ''}`)

/** Uma aba = uma chamada ao BFF; trocar mês, comparação ou parâmetro da aba muda a chave e recarrega. */
export function useAnalysis<V extends AnalysisView>(view: V, search: string) {
  const [attempt, setAttempt] = useState(0)
  const { value } = useKeyed<AnalysisResponses[V]>(`${view}?${search}#${attempt}`, async () => {
    const result = await fetchAnalysis(view, search)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })
  return { ...value, retry: () => setAttempt((n) => n + 1) }
}

export function useCategoryDetail(code: string | null, search: string) {
  const [attempt, setAttempt] = useState(0)
  const { value } = useKeyed<AnalysisCategoryDetail | null>(`category:${code}?${search}#${attempt}`, async () => {
    if (!code) return { status: 'success', data: null }
    const result = await fetchCategoryDetail(code, search)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })
  return { ...value, retry: () => setAttempt((n) => n + 1) }
}
