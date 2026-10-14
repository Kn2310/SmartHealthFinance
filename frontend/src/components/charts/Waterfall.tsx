import type { ReactNode } from 'react'
import type { MoneyDto } from '@/lib/api/types'
import { Money } from '@/components/ui/Money'
import { isNegative } from '@/lib/format/money'
import { magnitude, px, scale } from './geometry'
import styles from './Charts.module.css'

export interface WaterfallStep {
  key: string
  label: ReactNode
  amount: MoneyDto
  kind: 'inflow' | 'outflow' | 'result'
}

interface Props {
  label: string
  steps: WaterfallStep[]
}

const WIDTH = 100

/** Onde cada barra começa: entradas empilham para cima, saídas descem a partir do nível atual. */
function positions(steps: WaterfallStep[]): number[] {
  const starts: number[] = []
  steps.reduce((level, step) => {
    const size = magnitude(step.amount)
    if (step.kind === 'inflow') {
      starts.push(level)
      return level + size
    }
    if (step.kind === 'outflow') {
      starts.push(level - size)
      return level - size
    }
    starts.push(0)
    return level
  }, 0)
  return starts
}

/**
 * "Por que o saldo mudou": waterfall horizontal (entradas → saídas por categoria → resultado). Cada saída começa
 * onde a anterior terminou, então a última barra cai exatamente no resultado — os valores vêm do backend e fecham
 * ao centavo; aqui só se desenha. Lista textual acessível; as barras são decorativas.
 */
export function Waterfall({ label, steps }: Props) {
  const max = Math.max(0, ...steps.map((s) => magnitude(s.amount)))
  const starts = positions(steps)

  return (
    <ul className={styles.rows} aria-label={label}>
      {steps.map((step, index) => {
        const size = magnitude(step.amount)
        const x = scale(Math.max(starts[index] ?? 0, 0), max, WIDTH)
        const width = scale(size, max, WIDTH)
        const negative = step.kind === 'result' && isNegative(step.amount)
        const tone = step.kind === 'inflow' ? styles.barIncome
          : step.kind === 'outflow' ? styles.barExpense
            : negative ? styles.barNegative : styles.barNet
        return (
          <li key={step.key} className={styles.row}>
            <div className={styles.rowHead}>
              <span className={styles.rowLabel}>{step.label}</span>
              <span className={styles.rowValue}>
                <Money value={step.amount} sign={step.kind === 'inflow' ? 'inflow' : step.kind === 'outflow' ? 'outflow' : 'always'} />
              </span>
            </div>
            <svg className={styles.bar} viewBox={`0 0 ${WIDTH} 8`} preserveAspectRatio="none" aria-hidden="true"
              focusable="false">
              <rect className={styles.track} x={0} y={0} width={WIDTH} height={8} rx={4} />
              <rect className={tone} x={px(x)} y={0} width={px(Math.max(width, size > 0 ? 0.5 : 0))} height={8} rx={2} />
            </svg>
          </li>
        )
      })}
    </ul>
  )
}
