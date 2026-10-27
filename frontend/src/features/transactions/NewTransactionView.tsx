'use client'

import { ChevronLeft, CircleAlert, Landmark, Link2Off } from 'lucide-react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useEffect, useId, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import { MAX_INSTALLMENTS, TRANSACTION_DESCRIPTION_MAX_LENGTH } from '@/lib/api/contract'
import { parseAmountInput } from '@/lib/format/amount-input'
import { businessToday, isIsoDate } from '@/lib/format/business-date'
import { cardLabel } from '@/lib/format/card'
import type { Failure } from '@/lib/bff-client'
import {
  createTransaction,
  newIdempotencyKey,
  recordCardCredit,
  recordCardPayment,
  recordCardPurchase,
  type Account,
  type AdjustmentDirection,
  type Card,
  type ManualType,
} from './api'
import { useAccounts, useCards } from './hooks'
import { FIELD_ORDER, fieldErrorMessage, requestFailureMessage, type FormField } from './messages'
import { backHref, backLabel, destinationAfterCreate, type ReturnTo } from './return-to'
import accountStyles from '@/features/accounts/Accounts.module.css'
import styles from './Transactions.module.css'

/** Tipos do formulário: os lançamentos de conta e os três de cartão (ADR-0026 §10; estorno pela UI, ADR-0029 §7). */
export type FormType = ManualType | 'CARD_PURCHASE' | 'CARD_PAYMENT' | 'CARD_CREDIT'

type Errors = Partial<Record<FormField, string>>

interface Values {
  type: FormType
  adjustmentDirection: AdjustmentDirection | null
  accountId: string
  destinationAccountId: string
  cardId: string
  installmentCount: string
  amount: string
  occurredOn: string
  description: string
  pending: boolean
}

const TYPES: [FormType, string][] = [
  ['EXPENSE', 'Despesa'],
  ['INCOME', 'Receita'],
  ['TRANSFER', 'Transferência'],
  ['REFUND', 'Reembolso'],
  ['ADJUSTMENT', 'Ajuste'],
  ['CARD_PURCHASE', 'Compra no cartão'],
  ['CARD_PAYMENT', 'Pagamento de fatura'],
  ['CARD_CREDIT', 'Estorno no cartão'],
]
const DIRECTIONS: [AdjustmentDirection, string][] = [
  ['INCREASE', 'Aumenta o saldo'],
  ['DECREASE', 'Diminui o saldo'],
]

const isCardType = (type: FormType) => type === 'CARD_PURCHASE' || type === 'CARD_PAYMENT' || type === 'CARD_CREDIT'
/** Tipos que vinculam a uma compra ou despesa original (ADR-0005 §6, ADR-0025 §8). */
const isRefundType = (type: FormType) => type === 'REFUND' || type === 'CARD_CREDIT'

/** Só conveniência de UX (o BFF e o domínio validam tudo de novo). O valor nunca vira `number`. */
function validate(values: Values): { errors: Errors; amount: string | null } {
  const errors: Errors = {}
  const { type } = values
  if (type === 'ADJUSTMENT' && !values.adjustmentDirection) {
    errors.adjustmentDirection = fieldErrorMessage('adjustmentDirection', 'REQUIRED')
  }
  if (isCardType(type) && !values.cardId) errors.cardId = fieldErrorMessage('cardId', 'REQUIRED')
  if (type !== 'CARD_PURCHASE' && type !== 'CARD_CREDIT' && !values.accountId) errors.accountId = fieldErrorMessage('accountId', 'REQUIRED')
  if (type === 'TRANSFER') {
    if (!values.destinationAccountId) errors.destinationAccountId = fieldErrorMessage('destinationAccountId', 'REQUIRED')
    else if (values.destinationAccountId === values.accountId) {
      errors.destinationAccountId = fieldErrorMessage('destinationAccountId', 'SAME_ACCOUNT')
    }
  }
  if (type === 'CARD_PURCHASE') {
    const count = Number(values.installmentCount)
    if (!Number.isInteger(count) || count < 1 || count > MAX_INSTALLMENTS) {
      errors.installmentCount = fieldErrorMessage('installmentCount', 'OUT_OF_RANGE')
    }
  }
  const amount = parseAmountInput(values.amount)
  if (!amount.ok) errors.amount = fieldErrorMessage('amount', amount.error)
  if (!isIsoDate(values.occurredOn)) errors.occurredOn = fieldErrorMessage('occurredOn', values.occurredOn ? 'INVALID' : 'REQUIRED')
  const description = values.description.trim()
  // No pagamento de fatura a descrição é opcional ("Pagamento de fatura").
  if (!description && type !== 'CARD_PAYMENT') errors.description = fieldErrorMessage('description', 'REQUIRED')
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

interface Props {
  initialAccountId: string | null
  returnTo: ReturnTo
  /** `?type=` pré-seleciona o tipo (ex.: reembolso a partir do detalhe de uma despesa). */
  initialType?: FormType | null
  /** `?refundOf=`: a despesa que este reembolso devolve (ADR-0005 §6). */
  refundOf?: string | null
  /** `?cardId=`: o cartão já escolhido (ex.: "Registrar pagamento" de um insight de fatura, ADR-0028 §4). */
  initialCardId?: string | null
}

/**
 * Lançamento manual (página, não drawer — adendo do ADR-0005): receita, despesa, ajuste, transferência entre contas,
 * reembolso, compra no cartão (à vista ou parcelada) e pagamento de fatura (ADR-0026 §10). Cada abertura do
 * formulário é UMA intenção com uma `Idempotency-Key` estável: duplo clique e "tentar de novo" reenviam a mesma
 * chave, e o backend devolve o lançamento já criado em vez de duplicar. Nenhum cálculo financeiro aqui: parcelas,
 * faturas e saldos são do servidor.
 */
export function NewTransactionView({
  initialAccountId,
  returnTo,
  initialType = null,
  refundOf = null,
  initialCardId = null,
}: Props) {
  const accounts = useAccounts()
  const cards = useCards()
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
          <TransactionForm
            accounts={active}
            cards={cards}
            initialAccountId={initialAccountId}
            returnTo={returnTo}
            initialType={initialType}
            refundOf={refundOf}
            initialCardId={initialCardId}
          />
        </section>
      )
  }

  return (
    <div className={accountStyles.narrow}>
      {back}
      <header className={accountStyles.pageHeader}>
        <h1 className={accountStyles.pageTitle}>Nova transação</h1>
        <p className={accountStyles.pageSubtitle}>
          Registre uma movimentação de conta, uma compra no cartão ou o pagamento de uma fatura. Saldos e faturas são
          calculados pelo servidor.
        </p>
      </header>
      {body}
    </div>
  )
}

function TransactionForm({
  accounts,
  cards,
  initialAccountId,
  returnTo,
  initialType,
  refundOf,
  initialCardId,
}: {
  accounts: Account[]
  cards: ReturnType<typeof useCards>
  initialAccountId: string | null
  returnTo: ReturnTo
  initialType: FormType | null
  refundOf: string | null
  initialCardId: string | null
}) {
  const router = useRouter()
  const id = useId()
  const preselected = accounts.find((account) => account.id === initialAccountId)?.id ?? (accounts.length === 1 ? accounts[0]!.id : '')
  const [values, setValues] = useState<Values>(() => ({
    type: initialType ?? 'EXPENSE',
    adjustmentDirection: null,
    accountId: preselected,
    destinationAccountId: '',
    cardId: initialType !== null && isCardType(initialType) ? initialCardId ?? '' : '',
    installmentCount: '1',
    amount: '',
    occurredOn: businessToday(),
    description: '',
    pending: false,
  }))
  const [link, setLink] = useState<string | null>(initialType !== null && isRefundType(initialType) ? refundOf : null)
  const [errors, setErrors] = useState<Errors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  // Uma intenção = uma chave. Só muda depois de um 422 (a chave anterior já registrou outro conteúdo).
  const [idempotencyKey, setIdempotencyKey] = useState(newIdempotencyKey)
  // Trava síncrona: dois cliques no mesmo quadro não disparam dois envios (e, se disparassem, a chave é a mesma).
  const inFlight = useRef(false)
  const fields = useRef<Partial<Record<FormField, HTMLElement | null>>>({})
  const focusFirstError = useRef(false)

  const activeCards: Card[] = cards.status === 'success' ? cards.data.filter((card) => card.status === 'ACTIVE') : []
  const cardType = isCardType(values.type)

  // O cartão vindo da URL só vale se for um cartão ativo deste Workspace; no pagamento, a conta padrão dele é sugerida.
  // Ajuste feito uma vez, na chegada dos cartões (estado derivado no render, sem efeito): depois disso a escolha é do
  // usuário.
  const [initialCardChecked, setInitialCardChecked] = useState(false)
  if (!initialCardChecked && cards.status === 'success' && initialCardId) {
    setInitialCardChecked(true)
    if (values.cardId === initialCardId) {
      const card = activeCards.find((item) => item.id === initialCardId)
      if (!card) {
        setValues({ ...values, cardId: '' })
      }
      else if (values.type === 'CARD_PAYMENT' && !values.accountId && card.defaultPaymentAccountId
        && accounts.some((a) => a.id === card.defaultPaymentAccountId)) {
        setValues({ ...values, accountId: card.defaultPaymentAccountId })
      }
    }
  }

  useEffect(() => {
    if (!focusFirstError.current) return
    focusFirstError.current = false
    const first = FIELD_ORDER.find((field) => errors[field])
    if (first) fields.current[first]?.focus()
  }, [errors])

  const set = <K extends keyof Values>(key: K, value: Values[K]) => {
    setValues((current) => {
      const next = { ...current, [key]: value }
      // Pagamento: a conta padrão do cartão é só sugestão (ADR-0025 §1); o usuário pode trocar.
      if (key === 'cardId' && current.type === 'CARD_PAYMENT') {
        const card = activeCards.find((item) => item.id === value)
        if (card?.defaultPaymentAccountId && accounts.some((a) => a.id === card.defaultPaymentAccountId)) {
          next.accountId = card.defaultPaymentAccountId
        }
      }
      return next
    })
    const field = (key === 'pending' ? 'status' : key) as FormField
    if (errors[field]) setErrors((current) => ({ ...current, [field]: undefined }))
  }

  async function send(amount: string) {
    const { type } = values
    const description = values.description.trim()
    if (type === 'CARD_PURCHASE') {
      return recordCardPurchase(
        values.cardId,
        { amount, occurredOn: values.occurredOn, description, installmentCount: Number(values.installmentCount) },
        idempotencyKey,
      )
    }
    if (type === 'CARD_CREDIT') {
      return recordCardCredit(
        values.cardId,
        { amount, occurredOn: values.occurredOn, description, refundOfTransactionId: link },
        idempotencyKey,
      )
    }
    if (type === 'CARD_PAYMENT') {
      return recordCardPayment(
        values.cardId,
        { accountId: values.accountId, amount, occurredOn: values.occurredOn, description: description || null },
        idempotencyKey,
      )
    }
    return createTransaction(
      {
        type,
        accountId: values.accountId,
        destinationAccountId: type === 'TRANSFER' ? values.destinationAccountId : null,
        adjustmentDirection: type === 'ADJUSTMENT' ? values.adjustmentDirection : null,
        amount,
        occurredOn: values.occurredOn,
        description,
        status: values.pending ? 'PENDING' : 'POSTED',
        refundOfTransactionId: type === 'REFUND' ? link : null,
      },
      idempotencyKey,
    )
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
    const result = await send(amount)
    if (result.ok) {
      // O botão continua ocupado até a navegação: nada de segundo envio.
      router.push(destinationAfterCreate(returnTo, !cardType && values.pending ? 'PENDING' : 'POSTED'))
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
    if (result.code === 'CARD_ARCHIVED' || result.code === 'CARD_NOT_FOUND') {
      byField.cardId = requestFailureMessage(result.code)
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

  const accountSelect = (field: 'accountId' | 'destinationAccountId', label: string, hint?: string) => (
    <div className={accountStyles.field}>
      <label htmlFor={`${id}-${field}`} className={accountStyles.label}>
        {label}
      </label>
      <select
        ref={(element) => {
          fields.current[field] = element
        }}
        id={`${id}-${field}`}
        name={field}
        className={accountStyles.select}
        value={values[field]}
        onChange={(event) => set(field, event.target.value)}
        aria-invalid={errors[field] ? true : undefined}
        aria-describedby={describedBy(field, Boolean(hint))}
      >
        {values[field] === '' ? <option value="">Escolha a conta</option> : null}
        {accounts.map((account) => (
          <option key={account.id} value={account.id}>
            {account.name}
          </option>
        ))}
      </select>
      {hint ? (
        <p id={`${id}-${field}-hint`} className={accountStyles.fieldHint}>
          {hint}
        </p>
      ) : null}
      {fieldError(field)}
    </div>
  )

  let cardField = null
  if (cardType) {
    if (cards.status === 'loading') {
      cardField = (
        <div role="status" aria-busy="true">
          <span className="sr-only">Carregando seus cartões…</span>
          <Skeleton height="row" />
        </div>
      )
    } else if (cards.status === 'error') {
      cardField = (
        <div className={accountStyles.error} role="alert">
          <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
          <span>Não foi possível carregar seus cartões. {requestFailureMessage(cards.failure.code)}</span>
          <Button variant="secondary" onClick={cards.retry}>
            Tentar novamente
          </Button>
        </div>
      )
    } else if (activeCards.length === 0) {
      cardField = (
        <div className={accountStyles.field}>
          <p className={accountStyles.muted}>Você ainda não tem cartão ativo. Cadastre um para registrar compras e pagamentos.</p>
          <div>
            <Button href="/cards/new" variant="secondary">
              Adicionar cartão
            </Button>
          </div>
        </div>
      )
    } else {
      cardField = (
        <div className={accountStyles.field}>
          <label htmlFor={`${id}-cardId`} className={accountStyles.label}>
            Cartão
          </label>
          <select
            ref={(element) => {
              fields.current.cardId = element
            }}
            id={`${id}-cardId`}
            name="cardId"
            className={accountStyles.select}
            value={values.cardId}
            onChange={(event) => set('cardId', event.target.value)}
            aria-invalid={errors.cardId ? true : undefined}
            aria-describedby={describedBy('cardId')}
          >
            {values.cardId === '' ? <option value="">Escolha o cartão</option> : null}
            {activeCards.map((card) => (
              <option key={card.id} value={card.id}>
                {cardLabel(card)}
              </option>
            ))}
          </select>
          {fieldError('cardId')}
          <p className={accountStyles.fieldHint}>
            <Link href="/cards/new" className={styles.link}>
              Adicionar outro cartão
            </Link>
          </p>
        </div>
      )
    }
  }

  const amountLabel = values.type === 'CARD_PURCHASE' ? 'Valor total da compra' : 'Valor'
  const amountHint =
    values.type === 'CARD_PURCHASE'
      ? 'O total da compra. A compra entra nos gastos uma vez, na data dela; as parcelas só dividem o valor entre as faturas.'
      : values.type === 'CARD_CREDIT'
        ? 'Quanto voltou para o cartão. O estorno abate a fatura e reduz o gasto; não é receita.'
        : values.type === 'CARD_PAYMENT'
        ? 'Quanto você pagou da fatura. É uma transferência da conta para o cartão: sai do saldo, mas não conta como gasto de novo.'
        : 'Em reais, sempre positivo: o tipo diz se entra ou sai. Ex.: 1.234,56.'

  return (
    <form className={accountStyles.form} onSubmit={submit} aria-busy={sending} noValidate>
      <fieldset className={accountStyles.fields} disabled={sending}>
        <fieldset className={styles.fieldset}>
          <legend className={styles.legend}>Tipo</legend>
          <div className={`${styles.segments} ${styles.many}`}>
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

        {cardField}

        {values.type === 'TRANSFER'
          ? accountSelect('accountId', 'Conta de origem')
          : values.type === 'CARD_PAYMENT'
            ? accountSelect('accountId', 'Pagar com a conta')
            : values.type === 'CARD_PURCHASE' || values.type === 'CARD_CREDIT'
              ? null
              : accountSelect('accountId', 'Conta')}
        {values.type === 'TRANSFER'
          ? accountSelect(
              'destinationAccountId',
              'Conta de destino',
              'Transferência entre suas contas não é receita nem despesa: só move o saldo de uma conta para outra.',
            )
          : null}

        {isRefundType(values.type) && link ? (
          <div className={accountStyles.field}>
            <p className={accountStyles.fieldHint} id={`${id}-refundOfTransactionId-hint`}>
              {values.type === 'CARD_CREDIT'
                ? 'Este estorno fica vinculado à compra original. Numa compra parcelada, ele cancela primeiro as parcelas ainda não faturadas.'
                : 'Este reembolso fica vinculado à despesa original. Ele reduz o gasto, não é contado como receita.'}
            </p>
            <div>
              <Button variant="secondary" onClick={() => setLink(null)}>
                <Link2Off size={16} strokeWidth={1.75} aria-hidden="true" />
                Remover vínculo com a despesa
              </Button>
            </div>
            {fieldError('refundOfTransactionId')}
          </div>
        ) : null}

        <div className={accountStyles.field}>
          <label htmlFor={`${id}-amount`} className={accountStyles.label}>
            {amountLabel}
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
            {amountHint}
          </p>
          {fieldError('amount')}
        </div>

        {values.type === 'CARD_PURCHASE' ? (
          <div className={accountStyles.field}>
            <label htmlFor={`${id}-installmentCount`} className={accountStyles.label}>
              Parcelas
            </label>
            <input
              ref={(element) => {
                fields.current.installmentCount = element
              }}
              id={`${id}-installmentCount`}
              name="installmentCount"
              type="number"
              min={1}
              max={MAX_INSTALLMENTS}
              step={1}
              inputMode="numeric"
              className={accountStyles.input}
              value={values.installmentCount}
              onChange={(event) => set('installmentCount', event.target.value)}
              aria-invalid={errors.installmentCount ? true : undefined}
              aria-describedby={describedBy('installmentCount', true)}
            />
            <p id={`${id}-installmentCount-hint`} className={accountStyles.fieldHint}>
              1 = à vista. Até {MAX_INSTALLMENTS} parcelas; os centavos que sobram vão para a primeira.
            </p>
            {fieldError('installmentCount')}
          </div>
        ) : null}

        <div className={accountStyles.field}>
          <label htmlFor={`${id}-occurredOn`} className={accountStyles.label}>
            {values.type === 'CARD_PURCHASE' ? 'Data da compra' : 'Data'}
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
            {values.type === 'CARD_PAYMENT' ? 'Descrição (opcional)' : 'Descrição'}
          </label>
          <input
            ref={(element) => {
              fields.current.description = element
            }}
            id={`${id}-description`}
            name="description"
            className={accountStyles.input}
            autoComplete="off"
            placeholder={values.type === 'CARD_PAYMENT' ? 'Pagamento de fatura' : undefined}
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

        {cardType ? null : (
          <>
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
                  Marque se ainda não aconteceu ou não foi confirmada. Pendentes não entram no saldo até serem
                  efetivadas.
                </p>
              </div>
            </div>
            {fieldError('status')}
          </>
        )}
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
        <Button type="submit" disabled={sending || (cardType && activeCards.length === 0)}>
          {sending ? 'Registrando…' : 'Registrar transação'}
        </Button>
      </div>
    </form>
  )
}
