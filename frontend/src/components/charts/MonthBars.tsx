import { useId } from 'react'
import type { MoneyDto } from '@/lib/api/types'
import { Money } from '@/components/ui/Money'
import { magnitude, monthLabel, px, scale } from './geometry'
import styles from './Charts.module.css'

export interface MonthBarsDatum {
  /** YYYY-MM */
  month: string
  partial: boolean
  values: MoneyDto[]
}

export interface MonthBarsSeries {
  label: string
  tone: 'income' | 'expense'
}

interface Props {
  title: string
  series: MonthBarsSeries[]
  data: MonthBarsDatum[]
  caption?: string
}

const WIDTH = 600
const HEIGHT = 200
const AXIS = 22

/**
 * "Comparar meses": barras agrupadas por mês; o mês parcial fica translúcido e tracejado (Trust UX). O SVG é
 * decorativo para leitores de tela: os mesmos valores estão na tabela "Ver dados" logo abaixo.
 */
export function MonthBars({ title, series, data, caption }: Props) {
  const id = useId()
  const max = Math.max(0, ...data.flatMap((d) => d.values.map(magnitude)))
  const slot = data.length > 0 ? WIDTH / data.length : WIDTH
  const groupWidth = slot * 0.7
  const barWidth = series.length > 0 ? groupWidth / series.length : groupWidth
  const chartHeight = HEIGHT - AXIS

  return (
    <figure className={styles.figure} aria-labelledby={`${id}-title`}>
      <figcaption id={`${id}-title`} className="sr-only">
        {title}
      </figcaption>
      <svg className={styles.svg} viewBox={`0 0 ${WIDTH} ${HEIGHT}`} aria-hidden="true" focusable="false">
        <line className={styles.axis} x1={0} x2={WIDTH} y1={chartHeight} y2={chartHeight} />
        {data.map((datum, i) => {
          const groupX = i * slot + (slot - groupWidth) / 2
          return (
            <g key={datum.month}>
              {datum.values.map((value, s) => {
                const height = scale(magnitude(value), max, chartHeight - 8)
                const tone = series[s]?.tone ?? 'expense'
                return (
                  <rect
                    key={s}
                    className={[styles[tone], datum.partial ? styles.partial : ''].join(' ')}
                    x={px(groupX + s * barWidth + 1)}
                    y={px(chartHeight - height)}
                    width={px(Math.max(barWidth - 2, 1))}
                    height={px(height)}
                    rx={2}
                  />
                )
              })}
              <text className={styles.tick} x={px(i * slot + slot / 2)} y={HEIGHT - 6} textAnchor="middle">
                {monthLabel(datum.month)}
                {datum.partial ? '*' : ''}
              </text>
            </g>
          )
        })}
      </svg>
      <div className={styles.legend} aria-hidden="true">
        {series.map((s) => (
          <span key={s.label} className={styles.legendItem}>
            <span className={`${styles.swatch} ${s.tone === 'income' ? styles.swatchIncome : styles.swatchExpense}`} />
            {s.label}
          </span>
        ))}
        {data.some((d) => d.partial) ? (
          <span className={styles.legendItem}>
            <span className={`${styles.swatch} ${styles.swatchPartial}`} />
            Mês parcial
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
              <th scope="col">Mês</th>
              {series.map((s) => (
                <th key={s.label} scope="col">
                  {s.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {data.map((datum) => (
              <tr key={datum.month}>
                <th scope="row">
                  {monthLabel(datum.month, true)}
                  {datum.partial ? ' (parcial)' : ''}
                </th>
                {datum.values.map((value, s) => (
                  <td key={s}>
                    <Money value={value} />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  )
}
