'use client'

import { useId, useState, type FormEvent } from 'react'
import { validateCustomRange, type CustomRangeError } from '@/lib/format/date'
import type { PeriodType } from '@/lib/api/types'
import type { OverviewQuery } from '@/lib/overview-query'
import styles from './PeriodSelector.module.css'

const OPTIONS: { type: PeriodType; label: string }[] = [
  { type: 'CURRENT_MONTH', label: 'Este mês' },
  { type: 'PREVIOUS_MONTH', label: 'Mês anterior' },
  { type: 'CUSTOM', label: 'Personalizado' },
]

const ERRORS: Record<CustomRangeError, string> = {
  REQUIRED: 'Informe a data inicial e a final.',
  INVALID: 'Use datas válidas.',
  ORDER: 'A data final deve ser igual ou posterior à inicial.',
  TOO_LONG: 'O período pode ter no máximo 366 dias.',
}

interface Props {
  value: OverviewQuery
  onChange: (query: OverviewQuery) => void
}

/**
 * Seletor de período do Overview. O intervalo personalizado só é aplicado após validação de UX
 * (ordem e limite de 366 dias); o backend continua sendo a autoridade (400 VALIDATION_FAILED).
 */
export function PeriodSelector({ value, onChange }: Props) {
  const id = useId()
  const [customOpen, setCustomOpen] = useState(value.period === 'CUSTOM')
  const [from, setFrom] = useState(value.from ?? '')
  const [to, setTo] = useState(value.to ?? '')
  const [error, setError] = useState<CustomRangeError | null>(null)
  const showCustom = customOpen || value.period === 'CUSTOM'

  function select(type: PeriodType) {
    setError(null)
    if (type === 'CUSTOM') {
      setCustomOpen(true)
      return
    }
    setCustomOpen(false)
    onChange({ period: type })
  }

  function apply(event: FormEvent) {
    event.preventDefault()
    const problem = validateCustomRange(from, to)
    setError(problem)
    if (!problem) onChange({ period: 'CUSTOM', from, to })
  }

  const active: PeriodType = showCustom ? 'CUSTOM' : value.period

  return (
    <div className={styles.wrap}>
      <div role="group" aria-label="Período" className={styles.segmented}>
        {OPTIONS.map(({ type, label }) => (
          <button key={type} type="button" className={styles.option} aria-pressed={active === type} onClick={() => select(type)}>
            {label}
          </button>
        ))}
      </div>

      {showCustom ? (
        <form className={styles.custom} onSubmit={apply} noValidate aria-label="Período personalizado">
          <label className={styles.field} htmlFor={`${id}-from`}>
            De
            <input id={`${id}-from`} type="date" value={from} onChange={(e) => setFrom(e.target.value)}
              aria-invalid={error ? true : undefined}
              aria-describedby={error ? `${id}-error` : undefined}
            />
          </label>
          <label className={styles.field} htmlFor={`${id}-to`}>
            Até
            <input id={`${id}-to`} type="date" value={to} onChange={(e) => setTo(e.target.value)}
              aria-invalid={error ? true : undefined}
              aria-describedby={error ? `${id}-error` : undefined}
            />
          </label>
          <button type="submit" className={styles.apply}>
            Aplicar
          </button>
          {error ? (
            <p id={`${id}-error`} role="alert" className={styles.error}>
              {ERRORS[error]}
            </p>
          ) : null}
        </form>
      ) : null}
    </div>
  )
}
