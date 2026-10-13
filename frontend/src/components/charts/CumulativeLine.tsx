import { useId } from 'react'
import type { AnalysisDay } from '@/lib/api/types'
import { Money } from '@/components/ui/Money'
import { magnitude, px, scale } from './geometry'
import styles from './Charts.module.css'

interface Props {
  title: string
  current: AnalysisDay[]
  previous: AnalysisDay[]
  currentLabel: string
  previousLabel: string
  caption?: string
}

const WIDTH = 600
const HEIGHT = 200
const AXIS = 22
const DAYS = 31

function path(points: AnalysisDay[], max: number): string {
  const height = HEIGHT - AXIS
  return points
    .map((point, i) => {
      const x = px(((point.day - 1) / (DAYS - 1)) * WIDTH)
      const y = px(height - scale(magnitude(point.amount), max, height - 8))
      return `${i === 0 ? 'M' : 'L'}${x},${y}`
    })
    .join(' ')
}

/**
 * "Ritmo dentro do mês": gasto acumulado do mês atual (sólido) × mês anterior (tracejado cinza). Os valores ficam na
 * tabela "Ver dados" (o SVG é decorativo para leitores de tela).
 */
export function CumulativeLine({ title, current, previous, currentLabel, previousLabel, caption }: Props) {
  const id = useId()
  const max = Math.max(0, ...current.map((p) => magnitude(p.amount)), ...previous.map((p) => magnitude(p.amount)))
  const days = Math.max(current.length, previous.length)
  const height = HEIGHT - AXIS

  return (
    <figure className={styles.figure} aria-labelledby={`${id}-title`}>
      <figcaption id={`${id}-title`} className="sr-only">
        {title}
      </figcaption>
      <svg className={styles.svg} viewBox={`0 0 ${WIDTH} ${HEIGHT}`} aria-hidden="true" focusable="false">
        <line className={styles.axis} x1={0} x2={WIDTH} y1={height} y2={height} />
        {previous.length > 0 ? <path className={styles.linePrevious} d={path(previous, max)} /> : null}
        {current.length > 0 ? <path className={styles.lineCurrent} d={path(current, max)} /> : null}
        {[1, 10, 20, 30].map((day) => (
          <text key={day} className={styles.tick} x={px(((day - 1) / (DAYS - 1)) * WIDTH)} y={HEIGHT - 6}
            textAnchor={day === 1 ? 'start' : 'middle'}>
            {day}
          </text>
        ))}
      </svg>
      <div className={styles.legend} aria-hidden="true">
        <span className={styles.legendItem}>
          <span className={`${styles.swatch} ${styles.swatchExpense}`} />
          {currentLabel}
        </span>
        {previous.length > 0 ? (
          <span className={styles.legendItem}>
            <span className={`${styles.swatch} ${styles.swatchPrevious}`} />
            {previousLabel}
          </span>
        ) : null}
      </div>
      {caption ? <p className={styles.caption}>{caption}</p> : null}
      <details className={styles.data}>
        <summary>Ver dados de “{title}”</summary>
        <table className={styles.table}>
          <caption className="sr-only">{title}</caption>
          <thead>
            <tr>
              <th scope="col">Dia</th>
              <th scope="col">{currentLabel}</th>
              {previous.length > 0 ? <th scope="col">{previousLabel}</th> : null}
            </tr>
          </thead>
          <tbody>
            {Array.from({ length: days }, (_, i) => i + 1).map((day) => (
              <tr key={day}>
                <th scope="row">{day}</th>
                <td>
                  <Money value={current.find((p) => p.day === day)?.amount ?? null} />
                </td>
                {previous.length > 0 ? (
                  <td>
                    <Money value={previous.find((p) => p.day === day)?.amount ?? null} />
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  )
}
