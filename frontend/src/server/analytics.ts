import 'server-only'
import type { NextResponse } from 'next/server'
import type { components } from '@/lib/api/schema'
import { stableCode } from './bff'
import { noStoreJson } from './http'

type ApiError = components['schemas']['ApiError']

/** Abas da Análise servidas por `/api/bff/analytics/{view}` (ADR-0015 §7) mais a freshness (ADR-0014 §6). */
export const ANALYSIS_VIEWS = ['overview', 'spending', 'income', 'categories', 'merchants', 'evolution', 'status'] as const
export type AnalysisView = (typeof ANALYSIS_VIEWS)[number]

export function isAnalysisView(value: string): value is AnalysisView {
  return (ANALYSIS_VIEWS as readonly string[]).includes(value)
}

/** Parâmetros aceitos; o Workspace vem SEMPRE da sessão. */
export interface AnalysisQueryParams {
  month?: string
  compareWith?: 'PREVIOUS' | 'AVERAGE'
  averageMonths?: number
  sort?: 'AMOUNT' | 'COUNT'
  months?: number
}

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/
const CATEGORY_CODE = /^[A-Z][A-Z0-9_]{0,39}$/

function intIn(value: string | null, min: number, max: number): number | undefined {
  if (value === null || !/^\d{1,2}$/.test(value)) return undefined
  const n = Number(value)
  return n >= min && n <= max ? n : undefined
}

/**
 * Só valores bem formados passam; o resto é descartado (cai no padrão do backend). Regras de negócio (mês futuro,
 * por exemplo) continuam no backend, que responde 400 com o campo.
 */
export function parseAnalysisQuery(search: URLSearchParams): AnalysisQueryParams {
  const month = search.get('month')
  const compareWith = search.get('compareWith')
  const sort = search.get('sort')
  return {
    month: month && MONTH.test(month) ? month : undefined,
    compareWith: compareWith === 'PREVIOUS' || compareWith === 'AVERAGE' ? compareWith : undefined,
    averageMonths: intIn(search.get('averageMonths'), 1, 12),
    sort: sort === 'AMOUNT' || sort === 'COUNT' ? sort : undefined,
    months: intIn(search.get('months'), 1, 12),
  }
}

export function isCategoryCode(value: string): boolean {
  return CATEGORY_CODE.test(value)
}

const ANALYSIS_FIELDS = new Set(['month', 'compareWith', 'averageMonths', 'sort', 'months', 'categoryCode'])

/** Falhas do backend → códigos estáveis; só os campos conhecidos da Análise chegam ao browser. */
export function analysisFailure(label: string, status: number, error: ApiError | undefined): NextResponse {
  if (status === 401) return noStoreJson({ code: 'UNAUTHENTICATED' }, 401)
  if (status === 400 && stableCode(error?.code) === 'VALIDATION_FAILED') {
    const details = (error?.details ?? []).flatMap((detail) =>
      detail.field && ANALYSIS_FIELDS.has(detail.field)
        ? [{ field: detail.field, code: stableCode(detail.code) ?? 'INVALID' }]
        : [],
    )
    return noStoreJson({ code: 'VALIDATION_FAILED', details }, 400)
  }
  console.error(`${label} upstream failure`, { status })
  return noStoreJson({ code: 'UNAVAILABLE' }, 502)
}
