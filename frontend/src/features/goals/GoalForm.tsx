'use client'

import { CircleAlert } from 'lucide-react'
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import type { Account } from '@/features/accounts/api'
import { GOAL_NAME_MAX_LENGTH } from '@/lib/api/contract'
import type { Failure } from '@/lib/bff-client'
import { businessToday } from '@/lib/format/business-date'
import { parseAmountInput } from '@/lib/format/amount-input'
import type { MoneyDto } from '@/lib/api/types'
import type { GoalType } from './api'
import { TYPE_LABEL } from './format'
import { fieldErrors, GOAL_FIELD_ORDER, goalFailureMessage, goalFieldMessage, type GoalField } from './messages'
import styles from './Goals.module.css'
import accountStyles from '@/features/accounts/Accounts.module.css'

export interface GoalFormValues {
  type: GoalType | ''
  name: string
  targetAmount: string
  deadline: string
  plannedMonthly: string
  destinationAccountId: string
  sourceAccountId: string
}

export const EMPTY_GOAL: GoalFormValues = {
  type: '',
  name: '',
  targetAmount: '',
  deadline: '',
  plannedMonthly: '',
  destinationAccountId: '',
  sourceAccountId: '',
}

/** Campos já validados, como o BFF espera (dinheiro em string decimal, prazo `AAAA-MM`). */
export interface GoalFields {
  type: GoalType
  name: string
  targetAmount: string
  plannedMonthly: string
  deadline: string | null
  destinationAccountId: string | null
  sourceAccountId: string | null
}

type Errors = Partial<Record<GoalField, string>>

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/

/** Aporte mensal aceita zero ("sem plano", ADR-0034 §1); o resto segue o valor do DS. */
export function parsePlanned(raw: string): { ok: true; value: string } | { ok: false; error: string } {
  const parsed = parseAmountInput(raw)
  if (parsed.ok) return parsed
  if (parsed.error === 'NOT_POSITIVE' && !raw.trim().startsWith('-')) return { ok: true, value: '0.00' }
  return parsed
}

/** "1800.00" → "1.800,00" para preencher um campo (só texto, sem `number`). */
export function amountToInput(amount: string): string {
  const [integer = '0', fraction = ''] = amount.split('.')
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, '.')
  return `${grouped},${(fraction + '00').slice(0, 2)}`
}

/** Mês seguinte ao de hoje (fuso de negócio), só para o `min` do campo de prazo; a regra é do backend. */
function nextMonth(): string {
  const [y, m] = businessToday().split('-').map(Number)
  const month = (m ?? 1) % 12 + 1
  return `${(y ?? 0) + ((m ?? 1) === 12 ? 1 : 0)}-${String(month).padStart(2, '0')}`
}

function validate(values: GoalFormValues): { errors: Errors; fields: GoalFields | null } {
  const errors: Errors = {}
  if (!values.type) errors.type = goalFieldMessage('type', 'REQUIRED')
  const name = values.name.trim()
  if (!name) errors.name = goalFieldMessage('name', 'REQUIRED')
  else if (name.length > GOAL_NAME_MAX_LENGTH) errors.name = goalFieldMessage('name', 'TOO_LONG')
  const target = parseAmountInput(values.targetAmount)
  if (!target.ok) errors.targetAmount = goalFieldMessage('targetAmount', target.error)
  const planned = parsePlanned(values.plannedMonthly)
  if (!planned.ok) errors.plannedMonthly = goalFieldMessage('plannedMonthly', planned.error)
  if (values.deadline && !MONTH.test(values.deadline)) errors.deadline = goalFieldMessage('deadline', 'INVALID')
  const reserve = values.type === 'EMERGENCY_RESERVE'
  if (values.type && !reserve && !values.destinationAccountId) {
    errors.destinationAccountId = goalFieldMessage('destinationAccountId', 'REQUIRED')
  }
  if (Object.keys(errors).length > 0 || !target.ok || !planned.ok || !values.type) return { errors, fields: null }
  return {
    errors,
    fields: {
      type: values.type,
      name,
      targetAmount: target.value,
      plannedMonthly: planned.value,
      deadline: values.deadline || null,
      destinationAccountId: reserve ? null : values.destinationAccountId || null,
      sourceAccountId: values.sourceAccountId || null,
    },
  }
}

interface GoalFormProps {
  mode: 'create' | 'edit'
  initial: GoalFormValues
  accounts: Account[]
  /** Contas que já são destino de outro objetivo aberto (ADR-0034 §2.6). */
  usedDestinations: Set<string>
  /** Já existe uma reserva aberta: o tipo fica indisponível na criação. */
  reserveExists?: boolean
  /** "Já guardado" (fato, só leitura) da prévia; `undefined` esconde o campo. */
  alreadySaved?: MoneyDto | null
  /** Alvo sugerido da reserva (6 × despesa média, da prévia): preenche o alvo enquanto ele está vazio. */
  suggestedTarget?: MoneyDto | null
  header?: ReactNode
  onValuesChange?: (values: GoalFormValues) => void
  onSubmit: (fields: GoalFields) => Promise<Failure | null>
  submitLabel: string
  submittingLabel: string
  cancelHref: string
}

/**
 * Campos do objetivo no D-CreateGoal e na edição (ADR-0037): tipo, nome, valor alvo, "Já guardado" (só leitura), prazo,
 * aporte mensal planejado, conta do objetivo (fora da reserva) e conta de origem dos aportes (opcional). A validação
 * aqui é conveniência: o BFF e o domínio validam tudo de novo.
 */
export function GoalForm({
  mode,
  initial,
  accounts,
  usedDestinations,
  reserveExists = false,
  alreadySaved,
  suggestedTarget,
  header,
  onValuesChange,
  onSubmit,
  submitLabel,
  submittingLabel,
  cancelHref,
}: GoalFormProps) {
  const id = useId()
  const [values, setValues] = useState<GoalFormValues>(initial)
  const [errors, setErrors] = useState<Errors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const inFlight = useRef(false)
  const focusFirstError = useRef(false)
  const targetTouched = useRef(initial.targetAmount !== '')

  useEffect(() => {
    onValuesChange?.(values)
  }, [values, onValuesChange])

  // Sugestão da reserva: preenche o alvo só enquanto a pessoa não digitou nada nele.
  const suggestion = suggestedTarget?.amount
  useEffect(() => {
    if (!suggestion || targetTouched.current) return
    setValues((current) => (current.targetAmount ? current : { ...current, targetAmount: amountToInput(suggestion) }))
  }, [suggestion])

  useEffect(() => {
    if (!focusFirstError.current) return
    focusFirstError.current = false
    const first = GOAL_FIELD_ORDER.find((field) => errors[field])
    if (first) document.getElementById(`${id}-${first}`)?.focus()
  }, [errors, id])

  const set = (key: GoalField, value: string) => {
    if (key === 'targetAmount') targetTouched.current = true
    setValues((current) => {
      const next = { ...current, [key]: value }
      if (key === 'type' && value === 'EMERGENCY_RESERVE') {
        next.destinationAccountId = ''
        // A origem da reserva não pode ser uma poupança (ADR-0035 §2).
        if (accounts.some((a) => a.id === current.sourceAccountId && a.type === 'SAVINGS')) next.sourceAccountId = ''
      }
      // Saindo da reserva, o alvo sugerido (6 × despesa) não vale para o novo tipo: só some se a pessoa não o editou.
      if (key === 'type' && current.type === 'EMERGENCY_RESERVE' && value !== current.type && !targetTouched.current) next.targetAmount = ''
      if (key === 'destinationAccountId' && value && value === current.sourceAccountId) next.sourceAccountId = ''
      return next
    })
    if (errors[key]) setErrors((current) => ({ ...current, [key]: undefined }))
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (inFlight.current) return
    setFormError(null)
    const { errors: invalid, fields } = validate(values)
    if (!fields) {
      focusFirstError.current = true
      setErrors(invalid)
      return
    }
    inFlight.current = true
    setSending(true)
    const failure = await onSubmit(fields)
    if (!failure) return // a tela navega; continua "enviando" para não duplicar
    inFlight.current = false
    setSending(false)
    const byField = fieldErrors(failure, GOAL_FIELD_ORDER)
    if (failure.code === 'GOAL_DESTINATION_IN_USE') byField.destinationAccountId = goalFieldMessage('destinationAccountId', failure.code)
    if (Object.keys(byField).length > 0) {
      focusFirstError.current = true
      setErrors(byField)
    } else {
      setFormError(goalFailureMessage(failure.code))
    }
  }

  const reserve = values.type === 'EMERGENCY_RESERVE'
  const active = accounts.filter((a) => a.status === 'ACTIVE')
  const destinations = active.filter((a) => a.type !== 'SAVINGS' && (!usedDestinations.has(a.id) || a.id === initial.destinationAccountId))
  const sources = active.filter((a) => a.id !== values.destinationAccountId && (!reserve || a.type !== 'SAVINGS'))

  const describedBy = (field: GoalField, hint = false) =>
    [hint ? `${id}-${field}-hint` : null, errors[field] ? `${id}-${field}-error` : null].filter(Boolean).join(' ') || undefined
  const fieldError = (field: GoalField) =>
    errors[field] ? (
      <p id={`${id}-${field}-error`} className={accountStyles.fieldError}>
        <CircleAlert size={16} strokeWidth={1.75} aria-hidden="true" />
        <span>{errors[field]}</span>
      </p>
    ) : null
  const common = (field: GoalField, hint = false) => ({
    id: `${id}-${field}`,
    name: field,
    'aria-invalid': errors[field] ? true : undefined,
    'aria-describedby': describedBy(field, hint),
  })

  return (
    <form className={accountStyles.form} onSubmit={submit} aria-busy={sending} noValidate>
      {header}
      <fieldset className={accountStyles.fields} disabled={sending}>
        {mode === 'create' ? (
          <fieldset className={styles.radios} aria-describedby={describedBy('type')} id={`${id}-type`} tabIndex={-1}>
            <legend>Tipo</legend>
            {(['EMERGENCY_RESERVE', 'PURCHASE', 'CUSTOM'] as const).map((type) => {
              const disabled = type === 'EMERGENCY_RESERVE' && reserveExists
              return (
                <label key={type} className={styles.radio}>
                  <input type="radio" name="type" value={type} checked={values.type === type} disabled={disabled}
                    onChange={() => set('type', type)} />
                  <span className={styles.radioText}>
                    {TYPE_LABEL[type]}
                    <span className={styles.radioHint}>
                      {type === 'EMERGENCY_RESERVE'
                        ? disabled ? 'Você já tem uma reserva de emergência.' : 'O guardado é a soma das suas contas de reserva (poupança).'
                        : type === 'PURCHASE' ? 'Uma viagem, um carro, um curso: guardado numa conta própria.' : 'Qualquer outra meta, numa conta própria.'}
                    </span>
                  </span>
                </label>
              )
            })}
            {fieldError('type')}
          </fieldset>
        ) : (
          <div className={accountStyles.field}>
            <span className={accountStyles.label}>Tipo</span>
            <span className={styles.readonly}>{values.type ? TYPE_LABEL[values.type] : '—'}</span>
          </div>
        )}

        <div className={accountStyles.field}>
          <label htmlFor={`${id}-name`} className={accountStyles.label}>Nome do objetivo</label>
          <input {...common('name')} className={accountStyles.input} autoComplete="off" maxLength={GOAL_NAME_MAX_LENGTH}
            value={values.name} onChange={(e) => set('name', e.target.value)} />
          {fieldError('name')}
        </div>

        <div className={styles.pair}>
          <div className={accountStyles.field}>
            <label htmlFor={`${id}-targetAmount`} className={accountStyles.label}>Valor alvo</label>
            <input {...common('targetAmount', Boolean(suggestedTarget))} className={accountStyles.input} inputMode="decimal"
              autoComplete="off" placeholder="0,00" value={values.targetAmount} onChange={(e) => set('targetAmount', e.target.value)} />
            {suggestedTarget && reserve ? (
              <p id={`${id}-targetAmount-hint`} className={accountStyles.fieldHint}>
                Sugestão: <Money value={suggestedTarget} />, 6 meses da sua despesa média. Você pode mudar.
              </p>
            ) : null}
            {fieldError('targetAmount')}
          </div>
          {alreadySaved !== undefined ? (
            <div className={accountStyles.field}>
              <span id={`${id}-saved-label`} className={accountStyles.label}>Já guardado</span>
              <span className={styles.readonly} aria-labelledby={`${id}-saved-label`} aria-describedby={`${id}-saved-hint`}>
                {alreadySaved ? <Money value={alreadySaved} /> : '—'}
              </span>
              <p id={`${id}-saved-hint`} className={accountStyles.fieldHint}>
                {reserve ? 'Saldo das suas contas de reserva hoje.' : 'Saldo de hoje da conta do objetivo.'}
              </p>
            </div>
          ) : null}
        </div>

        <div className={styles.pair}>
          <div className={accountStyles.field}>
            <label htmlFor={`${id}-deadline`} className={accountStyles.label}>
              Prazo <span className={accountStyles.optional}>(opcional)</span>
            </label>
            <input {...common('deadline', true)} className={accountStyles.input} type="month" min={nextMonth()}
              value={values.deadline} onChange={(e) => set('deadline', e.target.value)} />
            <p id={`${id}-deadline-hint`} className={accountStyles.fieldHint}>Mês e ano, a partir do próximo mês.</p>
            {fieldError('deadline')}
          </div>
          <div className={accountStyles.field}>
            <label htmlFor={`${id}-plannedMonthly`} className={accountStyles.label}>Aporte mensal planejado</label>
            <input {...common('plannedMonthly', true)} className={accountStyles.input} inputMode="decimal" autoComplete="off"
              placeholder="0,00" value={values.plannedMonthly} onChange={(e) => set('plannedMonthly', e.target.value)} />
            <p id={`${id}-plannedMonthly-hint`} className={accountStyles.fieldHint}>Quanto você pretende guardar por mês.</p>
            {fieldError('plannedMonthly')}
          </div>
        </div>

        {values.type && !reserve ? (
          <div className={accountStyles.field}>
            <label htmlFor={`${id}-destinationAccountId`} className={accountStyles.label}>Conta do objetivo</label>
            <select {...common('destinationAccountId', true)} className={accountStyles.select} value={values.destinationAccountId}
              onChange={(e) => set('destinationAccountId', e.target.value)}>
              <option value="">Escolha a conta onde o dinheiro fica</option>
              {destinations.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
            <p id={`${id}-destinationAccountId-hint`} className={accountStyles.fieldHint}>
              Uma conta só deste objetivo (como uma caixinha). O guardado é o saldo dela.
            </p>
            {fieldError('destinationAccountId')}
          </div>
        ) : null}

        <div className={accountStyles.field}>
          <label htmlFor={`${id}-sourceAccountId`} className={accountStyles.label}>
            Conta de origem dos aportes <span className={accountStyles.optional}>(opcional)</span>
          </label>
          <select {...common('sourceAccountId', true)} className={accountStyles.select} value={values.sourceAccountId}
            onChange={(e) => set('sourceAccountId', e.target.value)}>
            <option value="">Nenhuma</option>
            {sources.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
          <p id={`${id}-sourceAccountId-hint`} className={accountStyles.fieldHint}>Já vem escolhida ao registrar um aporte.</p>
          {fieldError('sourceAccountId')}
        </div>
      </fieldset>

      {formError ? (
        <p className={accountStyles.error} role="alert">
          <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
          <span>{formError}</span>
        </p>
      ) : null}

      <div className={accountStyles.actions}>
        <Button href={cancelHref} variant="secondary">Cancelar</Button>
        <Button type="submit" disabled={sending}>{sending ? submittingLabel : submitLabel}</Button>
      </div>
    </form>
  )
}
