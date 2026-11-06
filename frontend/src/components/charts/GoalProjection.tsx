import { useId } from 'react'
import type { MoneyDto } from '@/lib/api/types'
import { Money } from '@/components/ui/Money'
import { magnitude, monthLabel, px, scale } from './geometry'
import styles from './Charts.module.css'

export interface GoalProjectionPoint {
  month: string
  amount: MoneyDto
}

interface Props {
  title: string
  /** Guardado no fim de cada mês (fato). */
  actual: GoalProjectionPoint[]
  /** Do guardado hoje até a conclusão pelo plano (Estimado); vazio sem previsão. */
  projected: GoalProjectionPoint[]
  target: MoneyDto
  deadline: string | null
  actualLabel: string
  projectedLabel: string
}

const WIDTH = 600
const HEIGHT = 220
const AXIS = 22
const TOP = 16

/** "2026-09" → índice absoluto de meses (só para posicionar no eixo x). */
const index = (month: string) => {
  const [y, m] = month.split('-').map(Number)
  return (y ?? 0) * 12 + ((m ?? 1) - 1)
}

/**
 * Previsão de objetivo (02-components "Gráficos"): linha sólida (guardado, fato) + tracejada (projeção, Estimado) +
 * linha vertical do prazo. Os pontos vêm prontos do backend (ADR-0036 §5); aqui eles só viram pixels. O SVG é
 * decorativo para leitores de tela: os valores ficam na tabela "Ver dados".
 */
export function GoalProjection({ title, actual, projected, target, deadline, actualLabel, projectedLabel }: Props) {
  const id = useId()
  const months = [...actual, ...projected].map((p) => p.month)
  if (deadline) months.push(deadline)
  if (months.length === 0) return null
  const first = Math.min(...months.map(index))
  const last = Math.max(...months.map(index))
  const span = Math.max(1, last - first)
  const max = Math.max(magnitude(target), ...actual.map((p) => magnitude(p.amount)), ...projected.map((p) => magnitude(p.amount)))
  const plot = HEIGHT - AXIS - TOP
  const x = (month: string) => px(((index(month) - first) / span) * (WIDTH - 8) + 4)
  const y = (amount: MoneyDto) => px(TOP + plot - scale(magnitude(amount), max, plot))
  const path = (points: GoalProjectionPoint[]) =>
    points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.month)},${y(p.amount)}`).join(' ')

  // Até 4 rótulos no eixo: o primeiro, o último e dois intermediários.
  const ticks = [...new Set([0, Math.round(span / 3), Math.round((2 * span) / 3), span])].map((offset) => {
    const absolute = first + offset
    return `${Math.floor(absolute / 12)}-${String((absolute % 12) + 1).padStart(2, '0')}`
  })
  const end = projected.at(-1)
  const rows = [...new Set([...actual.map((p) => p.month), ...projected.map((p) => p.month)])].sort()

  return (
    <figure className={styles.figure} aria-labelledby={`${id}-title`}>
      <figcaption id={`${id}-title`} className="sr-only">{title}</figcaption>
      <svg className={styles.svg} viewBox={`0 0 ${WIDTH} ${HEIGHT}`} aria-hidden="true" focusable="false">
        <line className={styles.grid} x1={0} x2={WIDTH} y1={y(target)} y2={y(target)} />
        <line className={styles.axis} x1={0} x2={WIDTH} y1={TOP + plot} y2={TOP + plot} />
        {deadline ? (
          <>
            <line className={styles.deadline} x1={x(deadline)} x2={x(deadline)} y1={TOP} y2={TOP + plot} />
            <text className={styles.deadlineLabel} x={x(deadline) - 4} y={TOP - 4} textAnchor="end">
              Prazo {monthLabel(deadline, true)}
            </text>
          </>
        ) : null}
        {projected.length > 1 ? <path className={styles.lineProjected} d={path(projected)} /> : null}
        {actual.length > 1 ? <path className={styles.lineSaved} d={path(actual)} /> : null}
        {actual.length === 1 ? <circle className={styles.dotSaved} cx={x(actual[0]!.month)} cy={y(actual[0]!.amount)} r={4} /> : null}
        {end ? <circle className={styles.dotProjected} cx={x(end.month)} cy={y(end.amount)} r={5} /> : null}
        {ticks.map((month, i) => (
          <text key={month} className={styles.tick} x={x(month)} y={HEIGHT - 6}
            textAnchor={i === 0 ? 'start' : i === ticks.length - 1 ? 'end' : 'middle'}>
            {monthLabel(month, true)}
          </text>
        ))}
      </svg>
      <div className={styles.legend} aria-hidden="true">
        <span className={styles.legendItem}><span className={`${styles.swatch} ${styles.swatchSaved}`} />{actualLabel}</span>
        {projected.length > 0 ? (
          <span className={styles.legendItem}><span className={`${styles.swatch} ${styles.swatchProjected}`} />{projectedLabel}</span>
        ) : null}
      </div>
      <details className={styles.data}>
        <summary>Ver dados de “{title}”</summary>
        <table className={styles.table}>
          <caption className="sr-only">{title}</caption>
          <thead>
            <tr>
              <th scope="col">Mês</th>
              <th scope="col">{actualLabel}</th>
              {projected.length > 0 ? <th scope="col">{projectedLabel}</th> : null}
            </tr>
          </thead>
          <tbody>
            {rows.map((month) => (
              <tr key={month}>
                <th scope="row">{monthLabel(month, true)}</th>
                <td><Money value={actual.find((p) => p.month === month)?.amount ?? null} /></td>
                {projected.length > 0 ? (
                  <td><Money value={projected.find((p) => p.month === month)?.amount ?? null} /></td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  )
}
