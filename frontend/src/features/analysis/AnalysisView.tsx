'use client'

import Link from 'next/link'
import { usePathname, useRouter, useSearchParams } from 'next/navigation'
import { useId, type ReactNode } from 'react'
import type { AnalysisMeta } from '@/lib/api/types'
import { businessToday } from '@/lib/format/business-date'
import { longMonthLabel } from '@/components/charts/geometry'
import { categoryNames, useCategories } from '@/features/transactions/hooks'
import { useAnalysis, type AnalysisView as View } from './api'
import { periodHeading } from './format'
import { AnalysisEmpty, AnalysisError, AnalysisNotices, AnalysisSkeleton } from './parts'
import {
  COMPARE_OPTIONS,
  TABS,
  monthOptions,
  parseAnalysisQuery,
  toApiSearch,
  toPageSearch,
  type AnalysisQuery,
  type AnalysisTab,
  type CompareOption,
} from './query'
import { CategoriesTab, EvolutionTab, IncomeTab, MerchantsTab, OverviewTab, SpendingTab } from './tabs'
import styles from './Analysis.module.css'

/** Parâmetros extras de cada aba que vão para o backend (ordenação, meses da evolução). */
function tabExtras(tab: AnalysisTab, params: URLSearchParams): Record<string, string | undefined> {
  if (tab === 'merchants') return { sort: params.get('sort') === 'COUNT' ? 'COUNT' : undefined }
  if (tab === 'evolution') return { months: params.get('months') === '3' ? '3' : undefined }
  return {}
}

/**
 * Análise (design/specs/03-screens, ANALYSIS): mês e "Comparar com" na URL, abas como rotas e uma chamada ao BFF
 * por aba. Todos os números chegam prontos do backend (ADR-0015); aqui só se formata.
 */
export function AnalysisView({ tab }: { tab: AnalysisTab }) {
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const query = parseAnalysisQuery(searchParams)
  const params = new URLSearchParams(searchParams.toString())
  const extras = tabExtras(tab, params)
  const result = useAnalysis(tab as View, toApiSearch(query, extras))
  const categories = useCategories()
  const names = categoryNames(categories)

  function navigate(next: AnalysisQuery, extra: Record<string, string | undefined> = extras) {
    // A categoria selecionada (aba Categorias) sobrevive à troca de mês; uma escolha nova a substitui.
    const search = toPageSearch(next, { category: params.get('category') ?? undefined, ...extra })
    router.replace(search ? `${pathname}?${search}` : pathname, { scroll: false })
  }

  const setExtra = (extra: Record<string, string | undefined>) => navigate(query, { ...extras, ...extra })

  let body: ReactNode
  if (result.status === 'loading') body = <AnalysisSkeleton />
  else if (result.status === 'error') {
    body = <AnalysisError onRetry={result.retry} invalid={result.failure.code === 'VALIDATION_FAILED'} />
  }
  else if (result.data.state === 'NO_DATA') body = <AnalysisEmpty />
  else body = renderTab(tab, result.data, { names, query, params, setExtra })

  const meta: AnalysisMeta | null = result.status === 'success' ? result.data : null

  return (
    <div className={styles.page}>
      <AnalysisHeader query={query} meta={meta} onChange={navigate} />
      <nav aria-label="Abas da análise">
        <div className={styles.tabs}>
          {TABS.map((item) => {
            const search = toPageSearch(query)
            return (
              <Link key={item.tab} href={search ? `${item.href}?${search}` : item.href} className={styles.tab}
                aria-current={item.tab === tab ? 'page' : undefined}>
                {item.label}
              </Link>
            )
          })}
        </div>
      </nav>
      {meta ? <AnalysisNotices meta={meta} /> : null}
      {body}
    </div>
  )
}

export interface TabContext {
  names: Map<string, string>
  query: AnalysisQuery
  params: URLSearchParams
  setExtra: (extra: Record<string, string | undefined>) => void
}

function renderTab(tab: AnalysisTab, data: AnalysisMeta, context: TabContext): ReactNode {
  switch (tab) {
    case 'overview':
      return <OverviewTab data={data as never} context={context} />
    case 'spending':
      return <SpendingTab data={data as never} context={context} />
    case 'income':
      return <IncomeTab data={data as never} context={context} />
    case 'categories':
      return <CategoriesTab data={data as never} context={context} />
    case 'merchants':
      return <MerchantsTab data={data as never} context={context} />
    case 'evolution':
      return <EvolutionTab data={data as never} context={context} />
  }
}

function AnalysisHeader({ query, meta, onChange }: {
  query: AnalysisQuery
  meta: AnalysisMeta | null
  onChange: (query: AnalysisQuery) => void
}) {
  const id = useId()
  const today = businessToday()
  const current = today.slice(0, 7)
  const months = monthOptions(today)
  return (
    <header className={styles.header}>
      <div className={styles.titleRow}>
        <div>
          <h1 className={styles.title}>Análise</h1>
          <p className={styles.subtitle} aria-live="polite">
            {meta ? periodHeading(meta) : 'Carregando o período…'}
          </p>
        </div>
        <div className={styles.selectors}>
          <label className={styles.field} htmlFor={`${id}-month`}>
            Mês
            <select id={`${id}-month`} className={styles.select} value={query.month ?? current}
              onChange={(event) => onChange({ ...query, month: event.target.value === current ? undefined : event.target.value })}>
              {months.map((month) => (
                <option key={month} value={month}>
                  {capitalize(longMonthLabel(month))}
                  {month === current ? ' (parcial)' : ''}
                </option>
              ))}
            </select>
          </label>
          <label className={styles.field} htmlFor={`${id}-compare`}>
            Comparar com
            <select id={`${id}-compare`} className={styles.select} value={query.compare}
              onChange={(event) => onChange({ ...query, compare: event.target.value as CompareOption })}>
              {COMPARE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
        </div>
      </div>
    </header>
  )
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1)
}
