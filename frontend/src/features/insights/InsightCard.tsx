import { ArrowRight } from 'lucide-react'
import Link from 'next/link'
import type { InsightItem } from '@/lib/api/types'
import { cardName, dayLabel } from './format'
import styles from './Insights.module.css'

/**
 * Insight Card (design/specs/02-components): "✦ Insight · {tipo}" + Importante/Informativo, frase com número, apoio,
 * meta (data e assunto) e "Ver detalhes". O card inteiro é um link com nome acessível (tipo, importância e frase).
 * O ✦ é só o rótulo de conteúdo interpretado; não há IA (ADR-0021 §9).
 */
export function InsightCard({ item, compact = false }: { item: InsightItem; compact?: boolean }) {
  const important = item.importance === 'IMPORTANT'
  const isNew = item.status === 'ACTIVE' && !item.seen
  return (
    <Link href={`/insights/${item.id}`} className={`${styles.card} ${important ? styles.important : ''}`}
      aria-label={`${cardName(item)}${isNew ? ' (novo)' : ''}`}>
      <span className={styles.cardHead}>
        <span className={styles.label}>✦ Insight · {item.typeLabel}</span>
        <span className={styles.badges}>
          {isNew ? <span className={`${styles.badge} ${styles.badgeNew}`}>Novo</span> : null}
          <span className={`${styles.badge} ${important ? styles.badgeImportant : ''}`}>
            {important ? 'Importante' : 'Informativo'}
          </span>
        </span>
      </span>
      <span className={styles.cardTitle}>{item.title}</span>
      {compact ? null : <span className={styles.cardSupport}>{item.support}</span>}
      <span className={styles.cardFoot}>
        <span className={styles.meta}>
          {dayLabel(item.updatedAt)} · {item.subjectLabel}
        </span>
        <span className={styles.more} aria-hidden="true">
          Ver detalhes <ArrowRight size={16} />
        </span>
      </span>
    </Link>
  )
}
