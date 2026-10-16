import { px } from './geometry'
import styles from './Score.module.css'

interface Props {
  /** 0–100; nulo = sem score (anel vazio, "—"). */
  score: number | null
  /** Score estimado (< 3 meses): "≈" e traço tracejado (Trust UX, 07 §4). */
  estimated?: boolean
  /** `dark` sobre o card Navy da Home; `light` em D-Health. */
  tone?: 'dark' | 'light'
  size?: 'sm' | 'md' | 'lg'
}

const VIEW = 100
const STROKE = 9
const RADIUS = (VIEW - STROKE) / 2
const CENTER = VIEW / 2

/** Arco a partir do topo, no sentido horário; 100 vira o círculo inteiro (dois arcos). */
export function arcPath(value: number): string {
  const top = `M${CENTER},${px(CENTER - RADIUS)}`
  if (value >= 100) {
    return `${top} A${RADIUS},${RADIUS} 0 1 1 ${CENTER},${px(CENTER + RADIUS)} A${RADIUS},${RADIUS} 0 1 1 ${CENTER},${px(CENTER - RADIUS)}`
  }
  const angle = (value / 100) * 2 * Math.PI
  const x = px(CENTER + RADIUS * Math.sin(angle))
  const y = px(CENTER - RADIUS * Math.cos(angle))
  return `${top} A${RADIUS},${RADIUS} 0 ${value > 50 ? 1 : 0} 1 ${x},${y}`
}

/**
 * Ring do Financial Health (design/specs/02-components): Teal, começa no topo, cap arredondado, número ~30% do
 * diâmetro e "de 100". Geometria só em atributos SVG (CSP); o desenho é decorativo e o nome acessível diz o score.
 * Só exibe o número que veio do backend.
 */
export function ScoreRing({ score, estimated = false, tone = 'light', size = 'md' }: Props) {
  const value = score === null ? 0 : Math.max(0, Math.min(100, score))
  const label = score === null ? 'Sem score ainda' : `Score ${estimated ? 'estimado ' : ''}${score} de 100`
  return (
    <div className={`${styles.ring} ${styles[size]} ${styles[tone]}`} role="img" aria-label={label}>
      <svg className={styles.ringSvg} viewBox={`0 0 ${VIEW} ${VIEW}`} aria-hidden="true" focusable="false">
        <circle className={styles.ringTrack} cx={CENTER} cy={CENTER} r={RADIUS} strokeWidth={STROKE} />
        {value > 0 ? (
          <path
            className={`${styles.ringValue} ${estimated ? styles.ringEstimated : ''}`}
            d={arcPath(value)}
            strokeWidth={STROKE}
          />
        ) : null}
      </svg>
      <div className={styles.ringCenter} aria-hidden="true">
        <span className={styles.ringNumber}>{score === null ? '—' : `${estimated ? '≈' : ''}${score}`}</span>
        <span className={styles.ringScale}>de 100</span>
      </div>
    </div>
  )
}
