'use client'

import { ChevronLeft, CircleAlert, Landmark } from 'lucide-react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useEffect, useId, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import { TRANSACTION_DESCRIPTION_MAX_LENGTH } from '@/lib/api/contract'
import { parseAmountInput } from '@/lib/format/amount-input'
import { businessToday, isIsoDate } from '@/lib/format/business-date'
import type { Failure } from '@/lib/bff-client'
import {
  createTransaction,
  newIdempotencyKey,
  type Account,
  type AdjustmentDirection,
  type ManualType,
} from './api'
import { useAccounts } from './hooks'
import { FIELD_ORDER, fieldErrorMessage, requestFailureMessage, type FormField } from './messages'
import { backHref, backLabel, destinationAfterCreate, type ReturnTo } from './return-to'
import accountStyles from '@/features/accounts/Accounts.module.css'
import styles from './Transactions.module.css'

type Errors = Partial<Record<FormField, string>>

interface Values {
  type: ManualType
  adjustmentDirection: AdjustmentDirection | null
  accountId: string
  amount: string
  occurredOn: string
  description: string
  pending: boolean
}

const TYPES: [ManualType, string][] = [
  ['INCOME', 'Receita'],
  ['EXPENSE', 'Despesa'],
  ['ADJUSTMENT', 'Ajuste'],
]
const DIRECTIONS: [AdjustmentDirection, string][] = [
  ['INCREASE', 'Aumenta o saldo'],
  ['DECREASE', 'Diminui o saldo'],
]

/** Só conveniência de UX (o BFF e o domínio validam tudo de novo). O valor nunca vira `number`. */
function validate(values: Values): { errors: Errors; amount: string | null } {
  const errors: Errors = {}
  if (values.type === 'ADJUSTMENT' && !values.adjustmentDirection) {
    errors.adjustmentDirection = fieldErrorMessage('adjustmentDirection', 'REQUIRED')
  }
  if (!values.accountId) errors.accountId = fieldErrorMessage('accountId', 'REQUIRED')
  const amount = parseAmountInput(values.amount)
  if (!amount.ok) errors.amount = fieldErrorMessage('amount', amount.error)
  if (!isIsoDate(values.occurredOn)) errors.occurredOn = fieldErrorMessage('occurredOn', values.occurredOn ? 'INVALID' : 'REQUIRED')
  const description = values.description.trim()
  if (!description) errors.description = fieldErrorMessage('description', 'REQUIRED')
  else if (description.length > TRANSACTION_DESCRIPTION_MAX_LENGTH) errors.description = fieldErrorMessage('description', 'TOO_LONG')
  return { errors, amount: amount.ok ? amount.value : null }
}

function fromFailure(failure: Failure): Errors {
  const errors: Errors = {}
  for (const { field, code } of failure.details ?? []) {
    if ((FIELD_ORDER as string[]).includes(field)) errors[field as FormField] ??= fieldErrorMessage(field as FormField, code)
  }
  return errors
}

/**
 * Lançamento manual de receita, despesa ou ajuste (página, não drawer — adendo do ADR-0005). Padrões: lançada
 * (POSTED) e data de hoje no fuso de negócio. Cada abertura do formulário é UMA intenção com uma
 * `Idempotency-Key` estável: duplo clique e "tentar de novo" depois de uma falha de rede reenviam a mesma chave,
 * e o backend devolve a transação já criada em vez de duplicar.
 */
export function NewTransactionView({ initialAccountId, returnTo }: { initialAccountId: string | null; returnTo: ReturnTo }) {
  const accounts = useAccounts()
  const back = (
    <Link href={backHref(returnTo)} className={accountStyles.back}>
      <ChevronLeft size={18} strokeWidth={1.75} aria-hidden="true" />
      {backLabel(returnTo)}
    </Link>
  )

  let body
  if (accounts.status === 'loading') {
    body = (
      <div className={accountStyles.panel} role="status" aria-busy="true">
        <span className="sr-only">Carregando suas contas…</span>
        <Skeleton height="row" />
        <Skeleton height="row" />
        <Skeleton height="row" />
      </div>
    )
  } else if (accounts.status === 'error') {
    body = (
      <section className={`${accountStyles.panel} ${accountStyles.danger}`} role="alert" aria-labelledby="accounts-error-title">
        <h2 id="accounts-error-title" className={accountStyles.sectionTitle}>
          Não foi possível carregar suas contas
        </h2>
        <p className={accountStyles.muted}>{requestFailureMessage(accounts.failure.code)}</p>
        <div>
          <Button onClick={accounts.retry}>Tentar novamente</Button>
        </div>
      </section>
    )
  } else {
    const active = accounts.data.filter((account) => account.status === 'ACTIVE')
    body =
      active.length === 0 ? (
        <section className={accountStyles.empty} aria-labelledby="no-accounts-title">
          <span className={accountStyles.stateIcon} aria-hidden="true">
            <Landmark size={24} strokeWidth={1.75} />
          </span>
          <h2 id="no-accounts-title" className={accountStyles.sectionTitle}>
            Crie uma conta primeiro
          </h2>
          <p className={accountStyles.muted}>Toda transação pertence a uma conta ativa. Crie a conta e volte para registrar.</p>
          <Button href="/accounts/new">Adicionar conta</Button>
        </section>
      ) : (
        <section className={accountStyles.panel} aria-labelledby="new-transaction-title">
          <h2 id="new-transaction-title" className={accountStyles.sectionTitle}>
            Dados da transação
          </h2>
          <TransactionForm accounts={active} initialAccountId={initialAccountId} returnTo={returnTo} />
        </section>
      )
  }

  return (
    <div className={accountStyles.narrow}>
      {back}
      <header className={accountStyles.pageHeader}>
        <h1 className={accountStyles.pageTitle}>Nova transação</h1>
        <p className={accountStyles.pageSubtitle}>
          Registre uma receita, uma despesa ou um ajuste de saldo. O saldo é recalculado pelo servidor.
        </p>
      </header>
      {body}
    </div>
  )
}

function TransactionForm({
  accounts,
  initialAccountId,
  returnTo,
}: {
  accounts: Account[]
  initialAccountId: string | null
  returnTo: ReturnTo
}) {
  const router = useRouter()
  const id = useId()
  const preselected = accounts.find((account) => account.id === initialAccountId)?.id ?? (accounts.length === 1 ? accounts[0]!.id : '')
  const [values, setValues] = useState<Values>(() => ({
    type: 'EXPENSE',
    adjustmentDirection: null,
    accountId: preselected,
    amount: '',
    occurredOn: businessToday(),
    description: '',
    pending: false,
  }))
  const [errors, setErrors] = useState<Errors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  // Uma intenção = uma chave. Só muda depois de um 422 (a chave anterior já registrou outro conteúdo).
  const [idempotencyKey, setIdempotencyKey] = useState(newIdempotencyKey)
  // Trava síncrona: dois cliques no mesmo quadro não disparam dois envios (e, se disparassem, a chave é a mesma).
  const inFlight = useRef(false)
  const fields = useRef<Partial<Record<FormField, HTMLElement | null>>>({})
  const focusFirstError = useRef(false)

  useEffect(() => {
    if (!focusFirstError.current) return
    focusFirstError.current = false
    const first = FIELD_ORDER.find((field) => errors[field])
    if (first) fields.current[first]?.focus()
  }, [errors])

  const set = <K extends keyof Values>(key: K, value: Values[K]) => {
    setValues((current) => ({ ...current, [key]: value }))
    const field = (key === 'pending' ? 'status' : key) as FormField
    if (errors[field]) setErrors((current) => ({ ...current, [field]: undefined }))
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (inFlight.current) return
    setFormError(null)
    const { errors: invalid, amount } = validate(values)
    if (Object.keys(invalid).length > 0 || amount === null) {
      focusFirstError.current = true
      setErrors(invalid)
      return
    }
    inFlight.current = true
    setSending(true)
    const status = values.pending ? 'PENDING' : 'POSTED'
    const result = await createTransaction(
      {
        type: values.type,
        accountId: values.accountId,
        adjustmentDirection: values.type === 'ADJUSTMENT' ? values.adjustmentDirection : null,
        amount,
        occurredOn: values.occurredOn,
        description: values.description.trim(),
        status,
      },
      idempotencyKey,
    )
    if (result.ok) {
      // O botão continua ocupado até a navegação: nada de segundo envio.
      router.push(destinationAfterCreate(returnTo, status))
      return
    }
    inFlight.current = false
    setSending(false)
    if (result.code === 'IDEMPOTENCY_KEY_REUSED') {
      // A chave já registrou um envio anterior com outro conteúdo: avisa e trata o próximo envio como nova intenção.
      setIdempotencyKey(newIdempotencyKey())
      setFormError(requestFailureMessage(result.code))
      return
    }
    const byField = fromFailure(result)
    if (result.code === 'ACCOUNT_ARCHIVED' || result.code === 'ACCOUNT_NOT_FOUND') {
      byField.accountId = requestFailureMessage(result.code)
    }
    if (Object.keys(byField).length > 0) {
      focusFirstError.current = true
      setErrors(byField)
    } else {
      setFormError(requestFailureMessage(result.code))
    }
  }

  const describedBy = (field: FormField, hint?: boolean) =>
    [hint ? `${id}-${field}-hint` : null, errors[field] ? `${id}-${field}-error` : null].filter(Boolean).join(' ') || undefined

  const fieldError = (field: FormField) =>
    errors[field] ? (
      <p id={`${id}-${field}-error`} className={accountStyles.fieldError}>
        <CircleAlert size={16} strokeWidth={1.75} aria-hidden="true" />
        <span>{errors[field]}</span>
      </p>
    ) : null

  return (
    <form className={accountStyles.form} onSubmit={submit} aria-busy={sending} noValidate>
      <fieldset className={accountStyles.fields} disabled={sending}>
        <fieldset className={styles.fieldset}>
          <legend className={styles.legend}>Tipo</legend>
          <div className={styles.segments}>
            {TYPES.map(([value, label], index) => (
              <label key={value} className={styles.segment}>
                <input
                  ref={(element) => {
                    if (index === 0) fields.current.type = element
                  }}
                  type="radio"
                  name="type"
                  value={value}
                  className={styles.segmentInput}
                  checked={values.type === value}
                  onChange={() => set('type', value)}
                />
                {label}
              </label>
            ))}
          </div>
          {fieldError('type')}
        </fieldset>

        {values.type === 'ADJUSTMENT' ? (
          <fieldset className={styles.fieldset} aria-describedby={describedBy('adjustmentDirection', true)}>
            <legend className={styles.legend}>Direção do ajuste</legend>
            <p id={`${id}-adjustmentDirection-hint`} className={accountStyles.fieldHint}>
              Use ajustes para acertar o saldo com o do banco (ex.: saldo inicial). Ajuste não conta como receita nem
              despesa.
            </p>
            <div className={`${styles.segments} ${styles.two}`}>
              {DIRECTIONS.map(([value, label], index) => (
                <label key={value} className={styles.segment}>
                  <input
                    ref={(element) => {
                    if (index === 0) fields.current.adjustmentDirection = element
                  }}
                    type="radio"
                    name="adjustmentDirection"
                    value={value}
                    className={styles.segmentInput}
                    checked={values.adjustmentDirection === value}
                    onChange={() => set('adjustmentDirection', value)}
                  />
                  {label}
                </label>
              ))}
            </div>
            {fieldError('adjustmentDirection')}
          </fieldset>
        ) : null}

        <div className={accountStyles.field}>
          <label htmlFor={`${id}-accountId`} className={accountStyles.label}>
            Conta
          </label>
          <select
            ref={(element) => {
              fields.current.accountId = element
            }}
            id={`${id}-accountId`}
            name="accountId"
            className={accountStyles.select}
            value={values.accountId}
            onChange={(event) => set('accountId', event.target.value)}
            aria-invalid={errors.accountId ? true : undefined}
            aria-describedby={describedBy('accountId')}
          >
            {values.accountId === '' ? <option value="">Escolha a conta</option> : null}
            {accounts.map((account) => (
              <option key={account.id} value={account.id}>
                {account.name}
              </option>
            ))}
          </select>
          {fieldError('accountId')}
        </div>

        <div className={accountStyles.field}>
          <label htmlFor={`${id}-amount`} className={accountStyles.label}>
            Valor
          </label>
          <div className={styles.money}>
            <span className={styles.currency} aria-hidden="true">
              R$
            </span>
            <input
              ref={(element) => {
              fields.current.amount = element
            }}
              id={`${id}-amount`}
              name="amount"
              className={`${accountStyles.input} ${styles.moneyInput}`}
              inputMode="decimal"
              autoComplete="off"
              placeholder="0,00"
              value={values.amount}
              onChange={(event) => set('amount', event.target.value)}
              aria-invalid={errors.amount ? true : undefined}
              aria-describedby={describedBy('amount', true)}
            />
          </div>
          <p id={`${id}-amount-hint`} className={accountStyles.fieldHint}>
            Em reais, sempre positivo: o tipo diz se entra ou sai. Ex.: 1.234,56.
          </p>
          {fieldError('amount')}
        </div>

        <div className={accountStyles.field}>
          <label htmlFor={`${id}-occurredOn`} className={accountStyles.label}>
            Data
          </label>
          <input
            ref={(element) => {
              fields.current.occurredOn = element
            }}
            id={`${id}-occurredOn`}
            name="occurredOn"
            type="date"
            className={accountStyles.input}
            value={values.occurredOn}
            onChange={(event) => set('occurredOn', event.target.value)}
            aria-invalid={errors.occurredOn ? true : undefined}
            aria-describedby={describedBy('occurredOn')}
          />
          {fieldError('occurredOn')}
        </div>

        <div className={accountStyles.field}>
          <label htmlFor={`${id}-description`} className={accountStyles.label}>
            Descrição
          </label>
          <input
            ref={(element) => {
              fields.current.description = element
            }}
            id={`${id}-description`}
            name="description"
            className={accountStyles.input}
            autoComplete="off"
            value={values.description}
            onChange={(event) => set('description', event.target.value)}
            aria-invalid={errors.description ? true : undefined}
            aria-describedby={describedBy('description', true)}
          />
          <p id={`${id}-description-hint`} className={accountStyles.fieldHint}>
            Como você reconhece esta transação. Até {TRANSACTION_DESCRIPTION_MAX_LENGTH} caracteres.
          </p>
          {fieldError('description')}
        </div>

        <div className={styles.checkRow}>
          <input
            ref={(element) => {
              fields.current.status = element
            }}
            id={`${id}-pending`}
            name="pending"
            type="checkbox"
            className={styles.checkbox}
            checked={values.pending}
            onChange={(event) => set('pending', event.target.checked)}
            aria-describedby={`${id}-status-hint`}
          />
          <div className={styles.checkText}>
            <label htmlFor={`${id}-pending`} className={styles.checkLabel}>
              Ainda pendente
            </label>
            <p id={`${id}-status-hint`} className={accountStyles.fieldHint}>
              Marque se ainda não aconteceu ou não foi confirmada. Pendentes não entram no saldo até serem efetivadas.
            </p>
          </div>
        </div>
        {fieldError('status')}
      </fieldset>

      {formError ? (
        <p className={accountStyles.error} role="alert">
          <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
          <span>{formError}</span>
        </p>
      ) : null}

      <div className={accountStyles.actions}>
        <Button href={backHref(returnTo)} variant="secondary">
          Cancelar
        </Button>
        <Button type="submit" disabled={sending}>
          {sending ? 'Registrando…' : 'Registrar transação'}
        </Button>
      </div>
    </form>
  )
}
