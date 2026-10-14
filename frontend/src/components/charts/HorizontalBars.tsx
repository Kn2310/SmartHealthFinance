import type { ReactNode } from 'react'
import type { MoneyDto } from '@/lib/api/types'
import { Money } from '@/components/ui/Money'
import { magnitude, px, scale } from './geometry'
import styles from './Charts.module.css'

export interface HorizontalBar {
  key: string
  label: ReactNode
  amount: MoneyDto
  /** Texto de apoio (participação, variação…). */
  note?: ReactNode
}

interface Props {
  label: string
  items: HorizontalBar[]
  tone?: 'expense' | 'income'
}

const WIDTH = 100

/**
 * "Onde foi o dinheiro": barras horizontais ordenadas. É uma lista textual (rótulo, valor e nota legíveis por
 * leitores de tela); a barra SVG por linha é só reforço visual.
 */
export function HorizontalBars({ label, items, tone = 'expense' }: Props) {
  const max = Math.max(0, ...items.map((item) => magnitude(item.amount)))
  return (
    <ul className={styles.rows} aria-label={label}>
      {items.map((item) => (
        <li key={item.key} className={styles.row}>
          <div className={styles.rowHead}>
            <span className={styles.rowLabel}>{item.label}</span>
            <span className={styles.rowValue}>
              <Money value={item.amount} />
            </span>
          </div>
          <svg className={styles.bar} viewBox={`0 0 ${WIDTH} 8`} preserveAspectRatio="none" aria-hidden="true"
            focusable="false">
            <rect className={styles.track} x={0} y={0} width={WIDTH} height={8} rx={4} />
            <rect className={tone === 'income' ? styles.barIncome : styles.barExpense} x={0} y={0}
              width={px(scale(magnitude(item.amount), max, WIDTH))} height={8} rx={4} />
          </svg>
          {item.note ? <span className={styles.rowNote}>{item.note}</span> : null}
        </li>
      ))}
    </ul>
  )
}
