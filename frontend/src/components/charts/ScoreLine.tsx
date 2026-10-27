import { useId } from 'react'
import { monthLabel, px } from './geometry'
import styles from './Score.module.css'

export interface ScoreLineDatum {
  /** YYYY-MM */
  month: string
  /** Nulo = mês sem score (sem snapshot ou dados insuficientes): a linha não passa por ele. */
  score: number | null
  estimated: boolean
  partial: boolean
}

interface Props {
  title: string
  data: ScoreLineDatum[]
  /** `dark` sobre Navy (sem tabela visível), `light` nos cards. */
  tone?: 'dark' | 'light'
  caption?: string
  /** Cabeçalho da coluna de valores na tabela "Ver dados" (padrão "Score"). */
  valueLabel?: string
  /** Sufixo dos valores (ex.: "%" na utilização do cartão). */
  unit?: string
}

const WIDTH = 600
const HEIGHT = 160
const AXIS = 22
const TOP = 12

/** Faixa do eixo em dezenas, com folga de 5 pontos, sempre dentro de 0–100 (o layout mostra 60/70/80). */
export function scoreDomain(scores: number[]): [number, number] {
  if (scores.length === 0) return [0, 100]
  const lo = Math.max(0, Math.floor((Math.min(...scores) - 5) / 10) * 10)
  const hi = Math.min(100, Math.ceil((Math.max(...scores) + 5) / 10) * 10)
  return hi - lo < 20 ? [Math.max(0, hi - 20), Math.max(hi, 20)] : [lo, hi]
}

/**
 * "Evolução do score": linha mensal; mês estimado ou em andamento com traço tracejado e ponto vazado (Trust UX).
 * O SVG é decorativo; os valores ficam na tabela "Ver dados" (alternativa textual, ADR-0016 §1).
 */
export function ScoreLine({ title, data, tone = 'light', caption, valueLabel = 'Score', unit = '' }: Props) {
  const id = useId()
  const scores = data.flatMap((d) => (d.score === null ? [] : [d.score]))
  const [lo, hi] = scoreDomain(scores)
  const plot = HEIGHT - AXIS - TOP
  const slot = data.length > 1 ? WIDTH / (data.length - 1) : WIDTH
  const x = (i: number) => px(data.length > 1 ? i * slot : WIDTH / 2)
  const y = (score: number) => px(TOP + plot - ((score - lo) / (hi - lo)) * plot)
  const ticks = Array.from({ length: (hi - lo) / 10 + 1 }, (_, i) => lo + i * 10).filter(
    (_, i, all) => all.length <= 5 || i % 2 === 0,
  )

  const segments = data.slice(1).flatMap((d, i) => {
    const prev = data[i]!
    if (prev.score === null || d.score === null) return []
    const dashed = d.estimated || d.partial || prev.estimated
    return [{ key: d.month, d: `M${x(i)},${y(prev.score)} L${x(i + 1)},${y(d.score)}`, dashed }]
  })

  return (
    <figure className={`${styles.figure} ${styles[tone]}`} aria-labelledby={`${id}-title`}>
      <figcaption id={`${id}-title`} className="sr-only">
        {title}
      </figcaption>
      <svg className={styles.lineSvg} viewBox={`0 0 ${WIDTH} ${HEIGHT}`} aria-hidden="true" focusable="false">
        {ticks.map((tick) => (
          <g key={tick}>
            <line className={styles.grid} x1={0} x2={WIDTH} y1={y(tick)} y2={y(tick)} />
            <text className={styles.tick} x={0} y={y(tick) - 4}>
              {tick}
            </text>
          </g>
        ))}
        {segments.map((s) => (
          <path key={s.key} className={`${styles.line} ${s.dashed ? styles.lineDashed : ''}`} d={s.d} />
        ))}
        {data.map((d, i) =>
          d.score === null ? null : (
            <circle
              key={d.month}
              className={`${styles.point} ${d.estimated || d.partial ? styles.pointOpen : ''}`}
              cx={x(i)}
              cy={y(d.score)}
              r={4}
            />
          ),
        )}
        {data.map((d, i) => (
          <text
            key={d.month}
            className={styles.tick}
            x={x(i)}
            y={HEIGHT - 6}
            textAnchor={i === 0 && data.length > 1 ? 'start' : i === data.length - 1 && data.length > 1 ? 'end' : 'middle'}
          >
            {monthLabel(d.month)}
          </text>
        ))}
      </svg>
      {caption ? <p className={styles.caption}>{caption}</p> : null}
      <details className={styles.data}>
        <summary>Ver dados de “{title}”</summary>
        <table className={styles.table}>
          <caption className="sr-only">{title}</caption>
          <thead>
            <tr>
              <th scope="col">Mês</th>
              <th scope="col">{valueLabel}</th>
            </tr>
          </thead>
          <tbody>
            {data.map((d) => (
              <tr key={d.month}>
                <th scope="row">{monthLabel(d.month, true)}</th>
                <td>
                  {d.score === null ? `Sem ${valueLabel.toLowerCase()}` : `${d.estimated ? '≈ ' : ''}${d.score}${unit}`}
                  {d.partial ? ' (em andamento)' : d.estimated && d.score !== null ? ' (estimado)' : ''}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  )
}
