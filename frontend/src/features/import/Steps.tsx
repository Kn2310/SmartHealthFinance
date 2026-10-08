import { Check } from 'lucide-react'
import styles from './Import.module.css'

const STEPS = ['Arquivo', 'Conferir', 'Importar'] as const

/** Onde o usuário está no fluxo. `done` marca a última etapa como concluída. */
export function Steps({ current, done = false }: { current: 1 | 2 | 3; done?: boolean }) {
  return (
    <ol className={styles.steps} aria-label="Etapas da importação">
      {STEPS.map((label, index) => {
        const step = index + 1
        const complete = step < current || (done && step === current)
        return (
          <li key={label} className={styles.step} aria-current={step === current && !done ? 'step' : undefined}>
            <span className={`${styles.stepMark} ${complete ? styles.stepComplete : step === current ? styles.stepCurrent : ''}`}>
              {complete ? <Check size={14} strokeWidth={2.5} aria-hidden="true" /> : step}
            </span>
            <span>
              {label}
              {complete ? <span className="sr-only"> (concluída)</span> : null}
            </span>
          </li>
        )
      })}
    </ol>
  )
}
