'use client'

import Link from 'next/link'
import type { ReactNode } from 'react'
import { Skeleton } from '@/components/ui/Skeleton'
import type { InsightHighlights } from '@/lib/api/types'
import type { Loadable } from '@/lib/use-keyed'
import { useInsightHighlights } from './api'
import { InsightCard } from './InsightCard'
import styles from './Insights.module.css'

type Highlights = Loadable<InsightHighlights | null> & { retry: () => void }

/** Uma chamada ao BFF para os dois blocos da Home (ADR-0023 §5). */
export function useHomeInsights(): Highlights {
  return useInsightHighlights()
}

/**
 * "O que mudou?" (D-Home / M-Home): até 3 linhas vindas dos insights de mudança, com os números do próprio insight;
 * cada linha leva ao detalhe. Sem mudança relevante, diz isso (não inventa linha).
 */
export function ChangesBlock({ highlights }: { highlights: Highlights }) {
  return (
    <section className={styles.panel} aria-labelledby="home-changes-title">
      <div className={styles.sectionHead}>
        <h2 id="home-changes-title" className={styles.panelTitle}>O que mudou</h2>
        <p className={styles.muted}>Comparado à sua média</p>
      </div>
      <Body highlights={highlights} label="mudanças">
        {(data) =>
          data.changes.length > 0 ? (
            <ul className={styles.changes}>
              {data.changes.map((change) => (
                <li key={change.insightId}>
                  <Link href={`/insights/${change.insightId}`} className={styles.change}>
                    <span className={styles.changeLabel}>{change.label}</span>{' '}
                    <span className={styles.changeValue}>{change.value}</span>{' '}
                    <span className={styles.changeDetail}>{change.detail}</span>
                  </Link>
                </li>
              ))}
            </ul>
          ) : (
            <p className={styles.muted}>Nada mudou de forma relevante no período.</p>
          )
        }
      </Body>
    </section>
  )
}

/** "Merece atenção" (D-Home: 2 cards; M-Home: 1): Importantes ativos e "Ver todos". */
export function AttentionBlock({ highlights }: { highlights: Highlights }) {
  const count = highlights.status === 'success' && highlights.data ? highlights.data.importantCount : null
  return (
    <section className={`${styles.panel} ${styles.homeAttention}`} aria-labelledby="home-attention-title">
      <div className={styles.panelHead}>
        <div className={styles.sectionHead}>
          <h2 id="home-attention-title" className={styles.panelTitle}>Merece atenção</h2>
          {count !== null ? (
            <p className={styles.muted}>
              {count === 0 ? 'Nenhum insight importante' : `${count} ${count === 1 ? 'insight importante' : 'insights importantes'}`}
            </p>
          ) : null}
        </div>
        <Link href="/insights" className={styles.link}>Ver todos</Link>
      </div>
      <Body highlights={highlights} label="insights importantes">
        {(data) =>
          data.attention.length > 0 ? (
            <ul className={styles.cards}>
              {data.attention.map((item) => (
                <li key={item.id}>
                  <InsightCard item={item} />
                </li>
              ))}
            </ul>
          ) : (
            <p className={styles.muted}>Nada pede sua atenção agora. Avisamos quando algo mudar.</p>
          )
        }
      </Body>
    </section>
  )
}

function Body({ highlights, label, children }: {
  highlights: Highlights
  label: string
  children: (data: InsightHighlights) => ReactNode
}) {
  if (highlights.status === 'loading') {
    return (
      <div className={styles.stack} role="status" aria-busy="true">
        <span className="sr-only">Carregando {label}…</span>
        <Skeleton width="lg" />
        <Skeleton width="md" />
      </div>
    )
  }
  if (highlights.status === 'error') {
    return (
      <div role="alert" className={styles.stack}>
        <p className={styles.muted}>Não conseguimos carregar os insights agora.</p>
        <div className={styles.actions}>
          <button type="button" className={styles.secondaryButton} onClick={highlights.retry}>Tentar novamente</button>
        </div>
      </div>
    )
  }
  const data = highlights.data
  if (!data) return null
  if (data.state === 'NO_DATA') {
    return <p className={styles.muted}>Os insights aparecem quando houver movimentações lançadas.</p>
  }
  if (data.state === 'INSUFFICIENT_HISTORY') {
    return <p className={styles.muted}>Os insights aparecem quando houver pelo menos 2 meses de histórico.</p>
  }
  return (
    <>
      {data.freshness.state === 'UPDATING' ? <p className={styles.meta}>Atualizando…</p> : null}
      {children(data)}
    </>
  )
}
