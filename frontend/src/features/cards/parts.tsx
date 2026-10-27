import { CreditCard as CardIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { Money } from '@/components/ui/Money'
import { magnitude, px } from '@/components/charts/geometry'
import type { Card, Utilization } from './api'
import styles from './Cards.module.css'

/**
 * Visual genérico do cartão (07-design-decisions §9): sem marca real, só apelido, instituição e os 4 últimos dígitos.
 * Decorativo para leitores de tela quando o nome já aparece no título da região.
 */
export function CardVisual({ card, compact = false }: { card: Pick<Card, 'nickname' | 'institutionName' | 'last4' | 'status'>; compact?: boolean }) {
  return (
    <div className={`${styles.visual} ${compact ? styles.visualCompact : ''} ${card.status === 'ARCHIVED' ? styles.visualArchived : ''}`}>
      <div className={styles.visualTop}>
        <span className={styles.visualInstitution}>{card.institutionName ?? 'Cartão de crédito'}</span>
        <CardIcon size={20} strokeWidth={1.75} aria-hidden="true" />
      </div>
      <div className={styles.visualBottom}>
        <span className={styles.visualName}>{card.nickname}</span>
        <span className={styles.visualDigits}>
          <span aria-hidden="true">•••• </span>
          <span className="sr-only">final </span>
          {card.last4}
        </span>
      </div>
    </div>
  )
}

export function Badge({ tone, children }: { tone: 'info' | 'success' | 'neutral' | 'warning' | 'teal'; children: ReactNode }) {
  return <span className={`${styles.badge} ${styles[`badge-${tone}`]}`}>{children}</span>
}

const RANGE_WIDTH = 100

/**
 * Financial Range da utilização (02-components): em uso (faturas em aberto + fatura atual) sólido e parcelas futuras
 * tracejadas. Os valores e o percentual vêm do backend; a geometria só posiciona as faixas (ADR-0025 §9).
 */
export function UtilizationRange({ utilization, label }: { utilization: Utilization; label: string }) {
  const limit = magnitude(utilization.limit)
  const { previousOutstanding, currentInvoice, futureInstallments } = utilization.composition
  const width = (value: number) => (limit > 0 ? Math.min(RANGE_WIDTH, (value / limit) * RANGE_WIDTH) : 0)
  const solid = width(magnitude(previousOutstanding) + magnitude(currentInvoice))
  const future = Math.min(RANGE_WIDTH - solid, width(magnitude(futureInstallments)))
  return (
    <div className={styles.range}>
      <div className={styles.rangeHead}>
        <span>
          Utilizado <strong><Money value={utilization.utilized} /></strong>
        </span>
        <span>
          Disponível <strong><Money value={utilization.available} /></strong>
        </span>
      </div>
      <svg className={styles.rangeBar} viewBox={`0 0 ${RANGE_WIDTH} 8`} preserveAspectRatio="none" role="img" aria-label={label}>
        <rect className={styles.rangeTrack} x={0} y={0} width={RANGE_WIDTH} height={8} rx={4} />
        {solid > 0 ? <rect className={styles.rangeUsed} x={0} y={0} width={px(solid)} height={8} rx={4} /> : null}
        {future > 0 ? <rect className={styles.rangeFuture} x={px(solid)} y={1} width={px(future)} height={6} /> : null}
      </svg>
      <p className={styles.caption}>
        {utilization.utilizationPercent}% do limite de <Money value={utilization.limit} />
      </p>
    </div>
  )
}
