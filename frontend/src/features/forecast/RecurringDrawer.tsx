'use client'

import { CircleAlert, X } from 'lucide-react'
import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { Button } from '@/components/ui/Button'
import type { ForecastRecurringSeries } from '@/lib/api/types'
import type { Failure } from '@/lib/bff-client'
import { parseAmountInput, type AmountInputError } from '@/lib/format/amount-input'
import { adjustSeries, resetSeries, type Adjustment, type RecurringKind } from './recurring'
import type { ActionOutcome } from './RecurringView'
import styles from './Recurring.module.css'

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), summary, [tabindex]:not([tabindex="-1"])'

type Field = 'expectedAmount' | 'expectedDay'

const AMOUNT_ERROR: Partial<Record<AmountInputError, string>> = {
  REQUIRED: 'Informe o valor esperado.',
  NOT_POSITIVE: 'Use um valor maior que zero.',
  TOO_MANY_DECIMALS: 'Use no máximo 2 casas decimais.',
  TOO_LARGE: 'Esse valor é grande demais.',
}
const AMOUNT_FALLBACK = 'Digite o valor em reais, por exemplo 1.800,00.'
const SERVER_FIELD_ERROR: Record<Field, string> = {
  expectedAmount: 'Use um valor maior que zero, com no máximo 2 casas decimais.',
  expectedDay: 'Use um dia do mês de 1 a 31.',
}
const NOTHING_TO_SAVE = 'Altere o valor ou o dia antes de salvar.'

const decimal = (amount: string) => amount.replace('.', ',')

/** Mensagem de uma falha que não é de campo. */
function failureMessage(failure: Failure): string {
  return failure.code === 'RECURRING_SERIES_NOT_FOUND'
    ? 'Esta recorrência não existe mais. Atualize a página.'
    : 'Não conseguimos salvar agora. Seus dados estão seguros. Tente de novo.'
}

/**
 * Drawer "Ajustar" (bottom sheet no mobile): valor e dia esperados do usuário e "Voltar ao detectado". Diálogo modal
 * nativo: foco preso, Esc fecha e o foco volta ao gatilho (mesmo padrão do drawer da Saúde). Só os campos alterados
 * vão ao BFF; a validação é conveniência, o backend decide.
 */
export function RecurringDrawer({ series, kind, onClose, onDone, trigger }: {
  series: ForecastRecurringSeries
  kind: RecurringKind
  onClose: () => void
  onDone: (outcome: ActionOutcome) => void
  trigger: HTMLElement | null
}) {
  const id = useId()
  const dialog = useRef<HTMLDialogElement>(null)
  const initialAmount = decimal(series.expectedAmount.amount)
  const [amount, setAmount] = useState(initialAmount)
  const [day, setDay] = useState(String(series.expectedDay))
  const [errors, setErrors] = useState<Partial<Record<Field | 'form', string>>>({})
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (!element.open) {
      if (typeof element.showModal === 'function') element.showModal()
      else element.setAttribute('open', '')
    }
    element.querySelector<HTMLElement>('[data-autofocus]')?.focus()
    return () => {
      if (element.open) {
        if (typeof element.close === 'function') element.close()
        else element.removeAttribute('open')
      }
      trigger?.focus()
    }
  }, [trigger])

  const onKeyDown = (event: KeyboardEvent<HTMLDialogElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
      return
    }
    if (event.key !== 'Tab') return
    const items = [...(dialog.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [])]
    if (items.length === 0) return
    const first = items[0]!
    const last = items.at(-1)!
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    const next: Partial<Record<Field | 'form', string>> = {}
    const body: Adjustment = {}

    // Só interpreta o valor que a pessoa alterou: o detectado pode vir com mais de 2 casas (mediana) ou outra escala.
    if (amount.trim() !== initialAmount) {
      const parsed = parseAmountInput(amount)
      if (!parsed.ok) next.expectedAmount = AMOUNT_ERROR[parsed.error] ?? AMOUNT_FALLBACK
      else body.expectedAmount = { amount: parsed.value, currency: series.expectedAmount.currency }
    }
    const dayNumber = Number(day)
    if (!/^\d{1,2}$/.test(day.trim()) || dayNumber < 1 || dayNumber > 31) next.expectedDay = SERVER_FIELD_ERROR.expectedDay
    else if (dayNumber !== series.expectedDay) body.expectedDay = dayNumber

    if (!next.expectedAmount && !next.expectedDay && !body.expectedAmount && body.expectedDay === undefined) {
      next.form = NOTHING_TO_SAVE
    }
    if (Object.keys(next).length > 0) {
      setErrors(next)
      if (next.expectedAmount) document.getElementById(`${id}-amount`)?.focus()
      else if (next.expectedDay) document.getElementById(`${id}-day`)?.focus()
      return
    }

    setBusy(true)
    setErrors({})
    const result = await adjustSeries(kind, series.id, body)
    setBusy(false)
    if (result.ok) {
      onDone({ series: result.data.series, message: 'Ajuste salvo.' })
      return
    }
    const fields = (result.details ?? []).filter((d): d is { field: Field; code: string } =>
      d.field === 'expectedAmount' || d.field === 'expectedDay')
    if (result.code === 'VALIDATION_FAILED' && fields.length > 0) {
      setErrors(Object.fromEntries(fields.map((d) => [d.field, SERVER_FIELD_ERROR[d.field]])))
    } else if (result.code === 'VALIDATION_FAILED' || result.code === 'INVALID_REQUEST') {
      setErrors({ form: NOTHING_TO_SAVE })
    } else {
      setErrors({ form: failureMessage(result) })
    }
  }

  async function reset() {
    setBusy(true)
    setErrors({})
    const result = await resetSeries(kind, series.id)
    setBusy(false)
    if (result.ok) onDone({ series: result.data.series, message: 'Voltamos aos valores detectados.' })
    else setErrors({ form: failureMessage(result) })
  }

  return (
    <dialog
      ref={dialog}
      className={styles.drawer}
      aria-labelledby={`${id}-title`}
      aria-modal="true"
      onKeyDown={onKeyDown}
      // Sem onClose nativo: o efeito fecha o diálogo ao desmontar (e o StrictMode do dev o faz uma vez a mais); Esc e fundo já chamam onClose.
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
      onClick={(event) => {
        if (event.target === dialog.current) onClose()
      }}
    >
      <div className={styles.drawerPanel}>
        <div className={styles.drawerHeader}>
          <h2 id={`${id}-title`} className={styles.drawerTitle}>Ajustar recorrência</h2>
          <button type="button" className={styles.iconButton} onClick={onClose} aria-label="Fechar">
            <X size={20} aria-hidden="true" />
          </button>
        </div>
        <form className={styles.drawerBody} onSubmit={submit} noValidate>
          <p className={styles.muted}>
            {series.label ?? 'Recorrência'}. Seus valores valem para as estimativas; o que foi detectado fica guardado.
          </p>
          <div className={styles.field}>
            <label className={styles.label} htmlFor={`${id}-amount`}>Valor esperado</label>
            <input id={`${id}-amount`} className={styles.input} type="text" inputMode="decimal" autoComplete="off"
              value={amount} onChange={(e) => setAmount(e.target.value)} data-autofocus
              aria-invalid={errors.expectedAmount ? true : undefined}
              aria-describedby={errors.expectedAmount ? `${id}-amount-error` : `${id}-amount-hint`} />
            {errors.expectedAmount ? (
              <p id={`${id}-amount-error`} className={styles.fieldError}>
                <CircleAlert size={14} aria-hidden="true" />
                <span>{errors.expectedAmount}</span>
              </p>
            ) : (
              <p id={`${id}-amount-hint`} className={styles.hint}>Em reais, por exemplo 1.800,00.</p>
            )}
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor={`${id}-day`}>Dia do mês</label>
            <input id={`${id}-day`} className={styles.input} type="text" inputMode="numeric" autoComplete="off"
              value={day} onChange={(e) => setDay(e.target.value)}
              aria-invalid={errors.expectedDay ? true : undefined}
              aria-describedby={errors.expectedDay ? `${id}-day-error` : `${id}-day-hint`} />
            {errors.expectedDay ? (
              <p id={`${id}-day-error`} className={styles.fieldError}>
                <CircleAlert size={14} aria-hidden="true" />
                <span>{errors.expectedDay}</span>
              </p>
            ) : (
              <p id={`${id}-day-hint`} className={styles.hint}>De 1 a 31.</p>
            )}
          </div>
          {errors.form ? (
            <p role="alert" className={styles.fieldError}>
              <CircleAlert size={14} aria-hidden="true" />
              <span>{errors.form}</span>
            </p>
          ) : null}
          <div className={styles.drawerActions}>
            <Button type="submit" disabled={busy}>Salvar ajuste</Button>
            {series.userAdjusted ? (
              <Button variant="secondary" disabled={busy} onClick={reset}>Voltar ao detectado</Button>
            ) : null}
          </div>
        </form>
      </div>
    </dialog>
  )
}
