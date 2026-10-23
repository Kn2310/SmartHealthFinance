'use client'

import { ChevronLeft, CircleAlert, ShieldCheck } from 'lucide-react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useEffect, useId, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import { CARD_BRANDS, CARD_NICKNAME_MAX_LENGTH, INSTITUTION_NAME_MAX_LENGTH } from '@/lib/api/contract'
import { parseAmountInput } from '@/lib/format/amount-input'
import type { Failure } from '@/lib/bff-client'
import { useAccounts } from '@/features/transactions/hooks'
import { createCard, type CardBrand } from './api'
import { CARD_FIELD_ORDER, cardFieldErrorMessage, cardRequestFailureMessage, type CardField } from './messages'
import accountStyles from '@/features/accounts/Accounts.module.css'

type Errors = Partial<Record<CardField, string>>

const BRAND_LABEL: Record<CardBrand, string> = {
  VISA: 'Visa',
  MASTERCARD: 'Mastercard',
  ELO: 'Elo',
  AMEX: 'American Express',
  HIPERCARD: 'Hipercard',
  OTHER: 'Outra',
}

interface Values {
  nickname: string
  institutionName: string
  brand: CardBrand | ''
  last4: string
  limit: string
  closingDay: string
  dueDay: string
  defaultPaymentAccountId: string
}

const DAY = /^\d{1,2}$/

/** Só conveniência de UX: o BFF e o domínio validam tudo de novo (inclusive as regras do ciclo). */
function validate(values: Values): { errors: Errors; limit: string | null } {
  const errors: Errors = {}
  const nickname = values.nickname.trim()
  if (!nickname) errors.nickname = cardFieldErrorMessage('nickname', 'REQUIRED')
  else if (nickname.length > CARD_NICKNAME_MAX_LENGTH) errors.nickname = cardFieldErrorMessage('nickname', 'TOO_LONG')
  if (values.institutionName.trim().length > INSTITUTION_NAME_MAX_LENGTH) {
    errors.institutionName = cardFieldErrorMessage('institutionName', 'TOO_LONG')
  }
  if (!values.brand) errors.brand = cardFieldErrorMessage('brand', 'REQUIRED')
  if (!values.last4) errors.last4 = cardFieldErrorMessage('last4', 'REQUIRED')
  else if (!/^\d{4}$/.test(values.last4)) errors.last4 = cardFieldErrorMessage('last4', 'INVALID')
  const limit = parseAmountInput(values.limit)
  if (!limit.ok) errors.limit = cardFieldErrorMessage('limit', limit.error)
  for (const field of ['closingDay', 'dueDay'] as const) {
    const value = values[field]
    if (!value) errors[field] = cardFieldErrorMessage(field, 'REQUIRED')
    else if (!DAY.test(value) || Number(value) < 1 || Number(value) > 31) errors[field] = cardFieldErrorMessage(field, 'OUT_OF_RANGE')
  }
  if (!errors.closingDay && !errors.dueDay && values.closingDay && Number(values.closingDay) === Number(values.dueDay)) {
    errors.dueDay = cardFieldErrorMessage('dueDay', 'SAME_AS_CLOSING')
  }
  return { errors, limit: limit.ok ? limit.value : null }
}

function fromFailure(failure: Failure): Errors {
  const errors: Errors = {}
  for (const { field, code } of failure.details ?? []) {
    if ((CARD_FIELD_ORDER as string[]).includes(field)) errors[field as CardField] ??= cardFieldErrorMessage(field as CardField, code)
  }
  return errors
}

/**
 * Cadastro mínimo de cartão (ADR-0026 §10), até as telas de Cartões da Sessão 6.2. Só os 4 últimos dígitos: o
 * sistema nunca pede número completo, CVV, validade ou senha (ADR-0025 §1). Depois de cadastrar, volta para o
 * lançamento de compra no cartão.
 */
export function NewCardView() {
  const accounts = useAccounts()
  const back = (
    <Link href="/transactions/new?type=CARD_PURCHASE" className={accountStyles.back}>
      <ChevronLeft size={18} strokeWidth={1.75} aria-hidden="true" />
      Nova transação
    </Link>
  )

  return (
    <div className={accountStyles.narrow}>
      {back}
      <header className={accountStyles.pageHeader}>
        <h1 className={accountStyles.pageTitle}>Adicionar cartão</h1>
        <p className={accountStyles.pageSubtitle}>
          Informe só o necessário para acompanhar faturas e parcelas. Limite e datas ajudam a calcular a utilização.
        </p>
      </header>
      {accounts.status === 'loading' ? (
        <div className={accountStyles.panel} role="status" aria-busy="true">
          <span className="sr-only">Carregando suas contas…</span>
          <Skeleton height="row" />
          <Skeleton height="row" />
        </div>
      ) : (
        <section className={accountStyles.panel} aria-labelledby="new-card-title">
          <h2 id="new-card-title" className={accountStyles.sectionTitle}>
            Dados do cartão
          </h2>
          <CardForm
            accounts={accounts.status === 'success' ? accounts.data.filter((account) => account.status === 'ACTIVE') : []}
          />
        </section>
      )}
    </div>
  )
}

function CardForm({ accounts }: { accounts: { id: string; name: string }[] }) {
  const router = useRouter()
  const id = useId()
  const [values, setValues] = useState<Values>({
    nickname: '',
    institutionName: '',
    brand: '',
    last4: '',
    limit: '',
    closingDay: '',
    dueDay: '',
    defaultPaymentAccountId: '',
  })
  const [errors, setErrors] = useState<Errors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const inFlight = useRef(false)
  const fields = useRef<Partial<Record<CardField, HTMLElement | null>>>({})
  const focusFirstError = useRef(false)

  useEffect(() => {
    if (!focusFirstError.current) return
    focusFirstError.current = false
    const first = CARD_FIELD_ORDER.find((field) => errors[field])
    if (first) fields.current[first]?.focus()
  }, [errors])

  const set = (key: CardField, value: string) => {
    setValues((current) => ({ ...current, [key]: value }))
    if (errors[key]) setErrors((current) => ({ ...current, [key]: undefined }))
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (inFlight.current) return
    setFormError(null)
    const { errors: invalid, limit } = validate(values)
    if (Object.keys(invalid).length > 0 || limit === null) {
      focusFirstError.current = true
      setErrors(invalid)
      return
    }
    inFlight.current = true
    setSending(true)
    const result = await createCard({
      nickname: values.nickname.trim(),
      institutionName: values.institutionName.trim() || null,
      brand: values.brand as CardBrand,
      last4: values.last4,
      limit,
      closingDay: Number(values.closingDay),
      dueDay: Number(values.dueDay),
      defaultPaymentAccountId: values.defaultPaymentAccountId || null,
    })
    if (result.ok) {
      router.push('/transactions/new?type=CARD_PURCHASE')
      return
    }
    inFlight.current = false
    setSending(false)
    const byField = fromFailure(result)
    if (result.code === 'ACCOUNT_NOT_FOUND' || result.code === 'ACCOUNT_ARCHIVED') {
      byField.defaultPaymentAccountId = cardRequestFailureMessage(result.code)
    }
    if (Object.keys(byField).length > 0) {
      focusFirstError.current = true
      setErrors(byField)
    } else {
      setFormError(cardRequestFailureMessage(result.code))
    }
  }

  const describedBy = (field: CardField, hint?: boolean) =>
    [hint ? `${id}-${field}-hint` : null, errors[field] ? `${id}-${field}-error` : null].filter(Boolean).join(' ') || undefined

  const fieldError = (field: CardField) =>
    errors[field] ? (
      <p id={`${id}-${field}-error`} className={accountStyles.fieldError}>
        <CircleAlert size={16} strokeWidth={1.75} aria-hidden="true" />
        <span>{errors[field]}</span>
      </p>
    ) : null

  const input = (
    field: CardField,
    label: string,
    props: React.InputHTMLAttributes<HTMLInputElement> = {},
    hint?: string,
  ) => (
    <div className={accountStyles.field}>
      <label htmlFor={`${id}-${field}`} className={accountStyles.label}>
        {label}
      </label>
      <input
        ref={(element) => {
          fields.current[field] = element
        }}
        id={`${id}-${field}`}
        name={field}
        className={accountStyles.input}
        autoComplete="off"
        value={values[field]}
        onChange={(event) => set(field, event.target.value)}
        aria-invalid={errors[field] ? true : undefined}
        aria-describedby={describedBy(field, Boolean(hint))}
        {...props}
      />
      {hint ? (
        <p id={`${id}-${field}-hint`} className={accountStyles.fieldHint}>
          {hint}
        </p>
      ) : null}
      {fieldError(field)}
    </div>
  )

  return (
    <form className={accountStyles.form} onSubmit={submit} aria-busy={sending} noValidate>
      <fieldset className={accountStyles.fields} disabled={sending}>
        {input('nickname', 'Apelido', { maxLength: CARD_NICKNAME_MAX_LENGTH }, 'Como você reconhece o cartão. Ex.: Aurora Platinum.')}
        {input('institutionName', 'Instituição (opcional)', { maxLength: INSTITUTION_NAME_MAX_LENGTH })}

        <div className={accountStyles.field}>
          <label htmlFor={`${id}-brand`} className={accountStyles.label}>
            Bandeira
          </label>
          <select
            ref={(element) => {
              fields.current.brand = element
            }}
            id={`${id}-brand`}
            name="brand"
            className={accountStyles.select}
            value={values.brand}
            onChange={(event) => set('brand', event.target.value)}
            aria-invalid={errors.brand ? true : undefined}
            aria-describedby={describedBy('brand')}
          >
            {values.brand === '' ? <option value="">Escolha a bandeira</option> : null}
            {CARD_BRANDS.map((brand) => (
              <option key={brand} value={brand}>
                {BRAND_LABEL[brand]}
              </option>
            ))}
          </select>
          {fieldError('brand')}
        </div>

        {input(
          'last4',
          '4 últimos dígitos',
          { inputMode: 'numeric', maxLength: 4, pattern: '[0-9]{4}' },
          'Nunca pedimos o número completo do cartão, o CVV, a validade ou a senha.',
        )}

        <div className={accountStyles.field}>
          <label htmlFor={`${id}-limit`} className={accountStyles.label}>
            Limite
          </label>
          <input
            ref={(element) => {
              fields.current.limit = element
            }}
            id={`${id}-limit`}
            name="limit"
            className={accountStyles.input}
            inputMode="decimal"
            autoComplete="off"
            placeholder="0,00"
            value={values.limit}
            onChange={(event) => set('limit', event.target.value)}
            aria-invalid={errors.limit ? true : undefined}
            aria-describedby={describedBy('limit', true)}
          />
          <p id={`${id}-limit-hint`} className={accountStyles.fieldHint}>
            Em reais. Ex.: 12.000,00.
          </p>
          {fieldError('limit')}
        </div>

        {input('closingDay', 'Dia de fechamento', { inputMode: 'numeric', maxLength: 2 },
          'Compras feitas a partir deste dia entram na fatura seguinte.')}
        {input('dueDay', 'Dia de vencimento', { inputMode: 'numeric', maxLength: 2 })}

        <div className={accountStyles.field}>
          <label htmlFor={`${id}-defaultPaymentAccountId`} className={accountStyles.label}>
            Conta que paga a fatura (opcional)
          </label>
          <select
            ref={(element) => {
              fields.current.defaultPaymentAccountId = element
            }}
            id={`${id}-defaultPaymentAccountId`}
            name="defaultPaymentAccountId"
            className={accountStyles.select}
            value={values.defaultPaymentAccountId}
            onChange={(event) => set('defaultPaymentAccountId', event.target.value)}
            aria-invalid={errors.defaultPaymentAccountId ? true : undefined}
            aria-describedby={describedBy('defaultPaymentAccountId')}
          >
            <option value="">Nenhuma</option>
            {accounts.map((account) => (
              <option key={account.id} value={account.id}>
                {account.name}
              </option>
            ))}
          </select>
          {fieldError('defaultPaymentAccountId')}
        </div>
      </fieldset>

      <p className={accountStyles.fieldHint}>
        <ShieldCheck size={16} strokeWidth={1.75} aria-hidden="true" /> A dívida do cartão não entra no saldo total das
        contas: o pagamento da fatura é que sai da conta.
      </p>

      {formError ? (
        <p className={accountStyles.error} role="alert">
          <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
          <span>{formError}</span>
        </p>
      ) : null}

      <div className={accountStyles.actions}>
        <Button href="/transactions/new?type=CARD_PURCHASE" variant="secondary">
          Cancelar
        </Button>
        <Button type="submit" disabled={sending}>
          {sending ? 'Cadastrando…' : 'Cadastrar cartão'}
        </Button>
      </div>
    </form>
  )
}
