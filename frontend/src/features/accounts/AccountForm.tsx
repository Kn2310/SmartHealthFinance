'use client'

import { CircleAlert } from 'lucide-react'
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Button } from '@/components/ui/Button'
import { ACCOUNT_NAME_MAX_LENGTH, ACCOUNT_TYPES, INSTITUTION_NAME_MAX_LENGTH } from '@/lib/api/contract'
import type { Failure } from '@/lib/bff-client'
import { accountTypeLabel } from '@/features/home/labels'
import type { AccountFields, AccountType } from './api'
import { FIELD_ORDER, fieldErrorMessage, requestFailureMessage, type AccountField } from './messages'
import styles from './Accounts.module.css'

type Errors = Partial<Record<AccountField, string>>

interface Props {
  initial: AccountFields
  submitLabel: string
  submittingLabel: string
  /**
   * Falha = mostrada aqui, por campo quando possível; `leaving` = a tela vai navegar (o botão segue ocupado);
   * `null` = a tela já tratou o resultado.
   */
  onSubmit: (fields: AccountFields) => Promise<Failure | 'leaving' | null>
  /** Conta arquivada: campos visíveis, mas só leitura. */
  readOnly?: boolean
  cancelHref?: string
  /** Avisos entre os campos e as ações (ex.: Open Finance indisponível). */
  children?: ReactNode
}

/** Mesmas regras do BFF e do domínio, só para responder antes (o servidor continua validando tudo). */
function validate(values: AccountFields): Errors {
  const errors: Errors = {}
  const name = values.name.trim()
  if (!name) errors.name = fieldErrorMessage('name', 'REQUIRED')
  else if (name.length > ACCOUNT_NAME_MAX_LENGTH) errors.name = fieldErrorMessage('name', 'TOO_LONG')
  if ((values.institutionName ?? '').trim().length > INSTITUTION_NAME_MAX_LENGTH) {
    errors.institutionName = fieldErrorMessage('institutionName', 'TOO_LONG')
  }
  if (!(ACCOUNT_TYPES as readonly string[]).includes(values.type)) errors.type = fieldErrorMessage('type', 'REQUIRED')
  return errors
}

function fromFailure(failure: Failure): Errors {
  const errors: Errors = {}
  for (const { field, code } of failure.details ?? []) {
    if ((FIELD_ORDER as string[]).includes(field)) {
      errors[field as AccountField] ??= fieldErrorMessage(field as AccountField, code)
    }
  }
  return errors
}

/**
 * Campos da conta (design D-EditAccount; "Criar conta manual" de D-AddAccount): nome, instituição, tipo e
 * "Incluir no saldo total". Não há campo de saldo: o saldo é derivado das movimentações (ADR-0004 §4).
 */
export function AccountForm({ initial, submitLabel, submittingLabel, onSubmit, readOnly = false, cancelHref, children }: Props) {
  const [values, setValues] = useState<AccountFields>(initial)
  const [errors, setErrors] = useState<Errors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const fields = useRef<Partial<Record<AccountField, HTMLInputElement | HTMLSelectElement | null>>>({})
  const focusFirstError = useRef(false)
  const id = useId()

  // Foco no primeiro campo com erro depois que a mensagem já está associada a ele (aria-describedby).
  useEffect(() => {
    if (!focusFirstError.current) return
    focusFirstError.current = false
    const first = FIELD_ORDER.find((field) => errors[field])
    if (first) fields.current[first]?.focus()
  }, [errors])

  const set = <K extends keyof AccountFields>(key: K, value: AccountFields[K]) => {
    setValues((current) => ({ ...current, [key]: value }))
    if (errors[key]) setErrors((current) => ({ ...current, [key]: undefined }))
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (sending || readOnly) return
    setFormError(null)
    const invalid = validate(values)
    if (Object.keys(invalid).length > 0) {
      focusFirstError.current = true
      setErrors(invalid)
      return
    }
    setSending(true)
    const outcome = await onSubmit({
      name: values.name.trim(),
      type: values.type,
      institutionName: values.institutionName?.trim() || null,
      includedInTotal: values.includedInTotal,
    })
    if (outcome === 'leaving') return
    setSending(false)
    if (!outcome) return
    const byField = fromFailure(outcome)
    if (Object.keys(byField).length > 0) {
      focusFirstError.current = true
      setErrors(byField)
    } else {
      setFormError(requestFailureMessage(outcome.code))
    }
  }

  const describedBy = (field: AccountField, hint?: boolean) =>
    [hint ? `${id}-${field}-hint` : null, errors[field] ? `${id}-${field}-error` : null].filter(Boolean).join(' ') || undefined

  const fieldError = (field: AccountField) =>
    errors[field] ? (
      <p id={`${id}-${field}-error`} className={styles.fieldError}>
        <CircleAlert size={16} strokeWidth={1.75} aria-hidden="true" />
        <span>{errors[field]}</span>
      </p>
    ) : null

  return (
    <form className={styles.form} onSubmit={submit} aria-busy={sending} noValidate>
      <fieldset className={styles.fields} disabled={readOnly || sending}>
        <div className={styles.field}>
          <label htmlFor={`${id}-name`} className={styles.label}>
            Nome da conta
          </label>
          <input
            ref={(element) => {
              fields.current.name = element
            }}
            id={`${id}-name`}
            name="name"
            className={styles.input}
            value={values.name}
            onChange={(event) => set('name', event.target.value)}
            autoComplete="off"
            required
            aria-required="true"
            aria-invalid={errors.name ? true : undefined}
            aria-describedby={describedBy('name', true)}
          />
          <p id={`${id}-name-hint`} className={styles.fieldHint}>
            Como você reconhece esta conta. Até {ACCOUNT_NAME_MAX_LENGTH} caracteres.
          </p>
          {fieldError('name')}
        </div>

        <div className={styles.field}>
          <label htmlFor={`${id}-institutionName`} className={styles.label}>
            Instituição <span className={styles.optional}>(opcional)</span>
          </label>
          <input
            ref={(element) => {
              fields.current.institutionName = element
            }}
            id={`${id}-institutionName`}
            name="institutionName"
            className={styles.input}
            value={values.institutionName ?? ''}
            onChange={(event) => set('institutionName', event.target.value)}
            autoComplete="off"
            aria-invalid={errors.institutionName ? true : undefined}
            aria-describedby={describedBy('institutionName', true)}
          />
          <p id={`${id}-institutionName-hint`} className={styles.fieldHint}>
            Banco ou instituição onde a conta fica.
          </p>
          {fieldError('institutionName')}
        </div>

        <div className={styles.field}>
          <label htmlFor={`${id}-type`} className={styles.label}>
            Tipo
          </label>
          <select
            ref={(element) => {
              fields.current.type = element
            }}
            id={`${id}-type`}
            name="type"
            className={styles.select}
            value={values.type}
            onChange={(event) => set('type', event.target.value as AccountType)}
            aria-invalid={errors.type ? true : undefined}
            aria-describedby={describedBy('type')}
          >
            {ACCOUNT_TYPES.map((type) => (
              <option key={type} value={type}>
                {accountTypeLabel(type)}
              </option>
            ))}
          </select>
          {fieldError('type')}
        </div>

        <div className={styles.switchRow}>
          <div className={styles.switchText}>
            <label htmlFor={`${id}-includedInTotal`} className={styles.switchLabel}>
              Incluir no saldo total
            </label>
            <p id={`${id}-includedInTotal-hint`} className={styles.fieldHint}>
              Desative para contas que você não quer somar na visão geral.
            </p>
          </div>
          <input
            ref={(element) => {
              fields.current.includedInTotal = element
            }}
            id={`${id}-includedInTotal`}
            name="includedInTotal"
            type="checkbox"
            role="switch"
            className={styles.switch}
            checked={values.includedInTotal}
            onChange={(event) => set('includedInTotal', event.target.checked)}
            aria-invalid={errors.includedInTotal ? true : undefined}
            aria-describedby={describedBy('includedInTotal', true)}
          />
        </div>
        {fieldError('includedInTotal')}
      </fieldset>

      {children}

      {formError ? (
        <p className={styles.error} role="alert">
          <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
          <span>{formError}</span>
        </p>
      ) : null}

      {readOnly ? null : (
        <div className={styles.actions}>
          {cancelHref ? (
            <Button href={cancelHref} variant="secondary">
              Cancelar
            </Button>
          ) : null}
          <Button type="submit" disabled={sending}>
            {sending ? submittingLabel : submitLabel}
          </Button>
        </div>
      )}
    </form>
  )
}
