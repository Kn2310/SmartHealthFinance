'use client'

import Link from 'next/link'
import { usePathname, useRouter, useSearchParams } from 'next/navigation'
import { Skeleton } from '@/components/ui/Skeleton'
import { longMonthLabel } from '@/components/charts/geometry'
import type { InsightCounts, InsightItem, InsightList } from '@/lib/api/types'
import { useInsightList, type InsightFilter } from './api'
import { InsightCard } from './InsightCard'
import styles from './Insights.module.css'

const FILTERS: { value: InsightFilter; label: string; count: (c: InsightCounts) => number | null }[] = [
  { value: 'ALL', label: 'Todos', count: (c) => c.all },
  { value: 'IMPORTANT', label: 'Importantes', count: (c) => c.important },
  { value: 'SPENDING', label: 'Gastos', count: (c) => c.spending },
  { value: 'CARDS', label: 'Cartões', count: (c) => (c.cards.available ? c.cards.count : null) },
  { value: 'HEALTH', label: 'Saúde', count: (c) => c.health },
]

function parseFilter(value: string | null): InsightFilter {
  return value === 'IMPORTANT' || value === 'SPENDING' || value === 'CARDS' || value === 'HEALTH' ? value : 'ALL'
}

/**
 * Insights (`/insights`, D-Insights / M-Insights): chips com contagem (grupo com estado selecionado; Cartões desde a
 * insights-v2, ADR-0028), seções "Importantes" e "Outros insights". Só exibe o que a geração gravou (ADR-0023).
 */
export function InsightsView() {
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const filter = parseFilter(searchParams.get('filter'))
  const list = useInsightList(filter)

  const select = (next: InsightFilter) => {
    router.replace(next === 'ALL' ? pathname : `${pathname}?filter=${next}`, { scroll: false })
  }

  const counts = list.status === 'success' && list.data ? list.data.counts : null

  return (
    <div className={styles.page}>
      <header className={styles.pageHeader}>
        <div>
          <h1 className={styles.pageTitle}>Insights</h1>
          {list.status === 'success' && list.data ? <Subtitle list={list.data} /> : null}
        </div>
      </header>

      <div role="group" aria-label="Filtrar insights">
        <ul className={styles.chips}>
          {FILTERS.map(({ value, label, count }) => {
            const n = counts ? count(counts) : null
            return (
              <li key={value}>
                <button type="button" className={styles.chip} aria-pressed={filter === value}
                  onClick={() => select(value)}>
                  {label}{' '}
                  {n !== null ? <span className={styles.chipCount}>{n}</span> : null}
                </button>
              </li>
            )
          })}
        </ul>
      </div>

      {list.status === 'loading' ? (
        <ListSkeleton />
      ) : list.status === 'error' ? (
        <div role="alert" className={styles.panel}>
          <p className={styles.panelTitle}>Não conseguimos carregar seus insights agora.</p>
          <p>Seus dados estão seguros. Tente de novo em instantes.</p>
          <div className={styles.actions}>
            <button type="button" className={styles.secondaryButton} onClick={list.retry}>
              Tentar novamente
            </button>
          </div>
        </div>
      ) : list.data ? (
        <Content list={list.data} filter={filter} />
      ) : null}
    </div>
  )
}

function Subtitle({ list }: { list: InsightList }) {
  const month = list.period.month ? capitalize(longMonthLabel(list.period.month)) : null
  const parts = [
    month ? `${month}${list.period.partial ? ' (em andamento)' : ''}` : null,
    'gerados a partir dos seus dados, atualizados a cada importação',
    list.freshness.state === 'UPDATING' ? 'atualizando…' : null,
  ]
  return <p className={styles.pageSubtitle}>{parts.filter(Boolean).join(' · ')}</p>
}

function ListSkeleton() {
  return (
    <div className={styles.panel} role="status" aria-busy="true">
      <span className="sr-only">Carregando seus insights…</span>
      <Skeleton width="lg" />
      <Skeleton width="md" />
      <Skeleton width="lg" />
    </div>
  )
}

function Content({ list, filter }: { list: InsightList; filter: InsightFilter }) {
  if (list.state === 'NO_DATA') {
    return (
      <section className={styles.panel} aria-labelledby="insights-empty-title">
        <h2 id="insights-empty-title" className={styles.panelTitle}>Seus insights aparecem aqui</h2>
        <p>Importe um extrato ou lance suas movimentações: comparamos cada mês com o seu próprio histórico.</p>
        <div className={styles.actions}>
          <Link href="/import" className={styles.secondaryButton}>Importar extrato</Link>
          <Link href="/transactions/new" className={styles.secondaryButton}>Lançar movimentação</Link>
        </div>
      </section>
    )
  }
  if (list.state === 'INSUFFICIENT_HISTORY') {
    return (
      <section className={styles.panel} aria-labelledby="insights-short-title">
        <h2 id="insights-short-title" className={styles.panelTitle}>Ainda é cedo para comparar</h2>
        <p>
          Os insights comparam o mês com a sua média. Eles aparecem quando houver pelo menos 2 meses de histórico antes
          do mês analisado.
        </p>
      </section>
    )
  }
  if (list.items.length === 0) {
    return (
      <section className={styles.panel} aria-labelledby="insights-none-title">
        <h2 id="insights-none-title" className={styles.panelTitle}>
          {list.freshness.state === 'NOT_BUILT' ? 'Estamos gerando seus insights…' : 'Nenhum insight por aqui'}
        </h2>
        <p>
          {filter === 'ALL'
            ? 'Nada mudou de forma relevante no período. Avisamos quando algo merecer sua atenção.'
            : 'Nenhum insight neste filtro agora.'}
        </p>
      </section>
    )
  }
  const important = list.items.filter((i) => i.importance === 'IMPORTANT')
  const others = list.items.filter((i) => i.importance !== 'IMPORTANT')
  return (
    <>
      {important.length > 0 ? (
        <Section id="important" title="Importantes" subtitle="Merecem sua atenção agora" items={important} />
      ) : null}
      {others.length > 0 ? (
        <Section id="others" title={important.length > 0 ? 'Outros insights' : 'Insights'} subtitle="Para acompanhar"
          items={others} />
      ) : null}
    </>
  )
}

function Section({ id, title, subtitle, items }: { id: string; title: string; subtitle: string; items: InsightItem[] }) {
  return (
    <section className={styles.section} aria-labelledby={`insights-${id}`}>
      <div className={styles.sectionHead}>
        <h2 id={`insights-${id}`} className={styles.sectionTitle}>{title}</h2>
        <p className={styles.muted}>{subtitle}</p>
      </div>
      <ul className={styles.cards}>
        {items.map((item) => (
          <li key={item.id}>
            <InsightCard item={item} />
          </li>
        ))}
      </ul>
    </section>
  )
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1)
}
