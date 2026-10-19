'use client'

import { ArrowRight } from 'lucide-react'
import Link from 'next/link'
import { useRelatedInsights } from './api'
import styles from './Insights.module.css'

/**
 * "Ver insight relacionado" (D-TxnDetail, ADR-0023 §1): só aparece quando há insight ativo para a transação — a compra
 * atípica dela, a alta da categoria dela no mês ou o comerciante recorrente — e leva ao mais prioritário. Sem
 * resultado (ou com falha), não mostra nada: nunca um botão morto.
 */
export function RelatedInsight({ transactionId }: { transactionId: string }) {
  const related = useRelatedInsights({ transactionId })
  const first = related.status === 'success' ? related.data?.items[0] : undefined
  if (!first) return null
  return (
    <section className={styles.panel} aria-labelledby="related-insight-title">
      <div className={styles.sectionHead}>
        <h2 id="related-insight-title" className={styles.label}>✦ Insight relacionado</h2>
        <p>{first.title}</p>
      </div>
      <div className={styles.actions}>
        <Link href={`/insights/${first.id}`} className={styles.button}>
          Ver insight relacionado
          <ArrowRight size={18} aria-hidden="true" />
        </Link>
      </div>
    </section>
  )
}
