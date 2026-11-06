import type { ForecastValue } from '@/lib/api/types'
import { ProjectedValue } from '@/components/ui/ProjectedValue'
import { confidenceLine, limitationsText, statusNote, type EstimateStatus } from './format'
import styles from './Forecast.module.css'

/** Confiança, base e limitações de uma estimativa, em frases curtas (ADR-0033 §1). */
export function ConfidenceNotes({ value, months, basis }: { value: ForecastValue; months: number; basis?: string }) {
  const limitation = limitationsText(value.limitations)
  return (
    <>
      <p className={styles.confidence}>{confidenceLine(value, months, basis)}</p>
      {limitation ? <p className={styles.limitation}>{limitation}</p> : null}
    </>
  )
}

/** Valor projetado seguido da confiança/base e das limitações. */
export function ProjectedBlock({ value, months, basis }: { value: ForecastValue; months: number; basis?: string }) {
  return (
    <span className={styles.block}>
      <ProjectedValue value={value} />
      {value.basis === 'ESTIMATED' ? <ConfidenceNotes value={value} months={months} basis={basis} /> : null}
    </span>
  )
}

/** "Atualizando estimativas…" (mantém o valor), "Estimativas em preparação." ou a nota do histórico curto. */
export function StatusNote({ status }: { status: EstimateStatus }) {
  const note = statusNote(status)
  if (!note) return null
  const recalculating = status === 'RECALCULATING'
  return (
    <p className={[styles.note, recalculating ? styles.noteRecalculating : ''].filter(Boolean).join(' ')} aria-live="polite">
      {note}
    </p>
  )
}
