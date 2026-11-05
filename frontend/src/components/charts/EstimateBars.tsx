import { useId } from 'react'
import type { MoneyDto } from '@/lib/api/types'
import { Money } from '@/components/ui/Money'
import { formatMoneyApprox, formatMoneyApproxSpoken } from '@/lib/format/money'
import { magnitude, monthLabel, px, scale } from './geometry'
import styles from './Charts.module.css'

export interface EstimateBarsDatum {
  /** YYYY-MM */
  month: string
  /** Parte já confirmada (fato): trecho sólido. */
  fact: MoneyDto
  /** Parte estimada: trecho tracejado; `null` = sem estimativa neste mês. */
  estimated: MoneyDto | null
  /** Total do mês como a API o devolve (≈ quando há estimativa); só vai à tabela. */
  total: MoneyDto
}

interface Props {
  title: string
  factLabel: string
  estimatedLabel: string
  totalLabel: string
  data: EstimateBarsDatum[]
}

const WIDTH = 600
const HEIGHT = 200
const AXIS = 22

/**
 * Barras empilhadas por mês: o fato em trecho sólido e o estimado em trecho tracejado e translúcido (Trust UX, o mesmo
 * traço dos números e do mês parcial). O SVG é decorativo para leitores de tela: os mesmos valores estão na tabela
 * "Ver dados" logo abaixo, com o estimado como "≈". A geometria só dá altura em pixels; nada exibido é somado aqui.
 */
export function EstimateBars({ title, factLabel, estimatedLabel, totalLabel, data }: Props) {
  const id = useId()
  const stackHeights = data.map((d) => ({
    fact: magnitude(d.fact),
    estimated: d.estimated ? magnitude(d.estimated) : 0,
  }))
  const max = Math.max(0, ...stackHeights.map((h) => h.fact + h.estimated))
  const slot = data.length > 0 ? WIDTH / data.length : WIDTH
  const barWidth = slot * 0.5
  const chartHeight = HEIGHT - AXIS
  const plot = chartHeight - 8

  return (
    <figure className={styles.figure} aria-labelledby={`${id}-title`}>
      <figcaption id={`${id}-title`} className="sr-only">
        {title}
      </figcaption>
      <svg className={styles.svg} viewBox={`0 0 ${WIDTH} ${HEIGHT}`} aria-hidden="true" focusable="false">
        <line className={styles.axis} x1={0} x2={WIDTH} y1={chartHeight} y2={chartHeight} />
        {data.map((datum, i) => {
          const x = i * slot + (slot - barWidth) / 2
          const factHeight = scale(stackHeights[i]!.fact, max, plot)
          const estimatedHeight = scale(stackHeights[i]!.estimated, max, plot)
          return (
            <g key={datum.month}>
              <rect className={styles.expense} x={px(x)} y={px(chartHeight - factHeight)} width={px(barWidth)}
                height={px(factHeight)} rx={2} />
              {estimatedHeight > 0 ? (
                <rect className={`${styles.expense} ${styles.partial}`} x={px(x)}
                  y={px(chartHeight - factHeight - estimatedHeight)} width={px(barWidth)}
                  height={px(estimatedHeight)} rx={2} />
              ) : null}
              <text className={styles.tick} x={px(i * slot + slot / 2)} y={HEIGHT - 6} textAnchor="middle">
                {monthLabel(datum.month)}
              </text>
            </g>
          )
        })}
      </svg>
      <div className={styles.legend} aria-hidden="true">
        <span className={styles.legendItem}>
          <span className={`${styles.swatch} ${styles.swatchExpense}`} />
          {factLabel}
        </span>
        {data.some((d) => d.estimated) ? (
          <span className={styles.legendItem}>
            <span className={`${styles.swatch} ${styles.swatchPartial}`} />
            {estimatedLabel}
          </span>
        ) : null}
      </div>
      <details className={styles.data}>
        <summary>Ver dados de “{title}”</summary>
        <table className={styles.table}>
          <caption className="sr-only">{title}</caption>
          <thead>
            <tr>
              <th scope="col">Mês</th>
              <th scope="col">{factLabel}</th>
              <th scope="col">{estimatedLabel}</th>
              <th scope="col">{totalLabel}</th>
            </tr>
          </thead>
          <tbody>
            {data.map((datum) => (
              <tr key={datum.month}>
                <th scope="row">{monthLabel(datum.month, true)}</th>
                <td>
                  <Money value={datum.fact} />
                </td>
                <td>{datum.estimated ? <Approx value={datum.estimated} /> : '—'}</td>
                <td>{datum.estimated ? <Approx value={datum.total} /> : <Money value={datum.total} />}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  )
}

/** Estimativa na tabela: "≈ R$ 2.532" (leitor de tela: "aproximadamente R$ 2.532, estimado"). */
function Approx({ value }: { value: MoneyDto }) {
  return (
    <>
      <span aria-hidden="true">≈ {formatMoneyApprox(value)}</span>
      <span className="sr-only">aproximadamente {formatMoneyApproxSpoken(value)}, estimado</span>
    </>
  )
}
