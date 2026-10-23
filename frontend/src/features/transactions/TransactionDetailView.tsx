'use client'

import { Ban, CheckCircle2, ChevronLeft, CircleAlert, Download, SearchX, Undo2 } from 'lucide-react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useEffect, useId, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { Skeleton } from '@/components/ui/Skeleton'
import {
  FallbackTransactionIcon,
  TRANSACTION_TYPE_ICON,
  transactionTone,
  transactionTypeLabel,
} from '@/features/home/labels'
import { TRANSACTION_DESCRIPTION_MAX_LENGTH } from '@/lib/api/contract'
import { formatLongDate } from '@/lib/format/date'
import {
  categorizeTransaction,
  transitionTransaction,
  updateDescription,
  type Category,
  type Transaction,
  type TransitionAction,
} from './api'
import { accountNames, cardNames, categoryNames, useAccounts, useCards, useCategories, useTransaction } from './hooks'
import { acceptedNature, CATEGORY_SOURCE_LABEL, NO_CATEGORY_LABEL, SOURCE_LABEL, statusBadge, statusEffect, transactionSign } from './labels'
import { categoryErrorMessage, fieldErrorMessage, requestFailureMessage } from './messages'
import { RelatedInsight } from '@/features/insights/RelatedInsight'
import { canMarkAsRefund, RefundMarkSection } from './RefundMarkSection'
import accountStyles from '@/features/accounts/Accounts.module.css'
import styles from './Transactions.module.css'

const BACK = (
  <Link href="/transactions" className={accountStyles.back}>
    <ChevronLeft size={18} strokeWidth={1.75} aria-hidden="true" />
    Transações
  </Link>
)

/**
 * D-TxnDetail como página (`/transactions/{id}`): valor, tipo, status e origem, conta, datas; alterar a categoria
 * (ADR-0012) e a descrição (os únicos campos editáveis — ADR-0005 §8) e efetivar, cancelar ou estornar conforme o
 * status permitir. Receita importada pode ser marcada como reembolso (estornada e substituída por um REFUND).
 * `id` que não é UUID nem chega ao BFF: é o mesmo "não encontrada" de outro Workspace.
 */
export function TransactionDetailView({ id }: { id: string | null }) {
  if (!id) return <NotFound />
  return <TransactionDetail id={id} />
}

function NotFound() {
  return (
    <div className={accountStyles.narrow}>
      {BACK}
      <section className={accountStyles.empty} aria-labelledby="not-found-title">
        <span className={accountStyles.stateIcon} aria-hidden="true">
          <SearchX size={28} strokeWidth={1.75} />
        </span>
        <h1 id="not-found-title" className={accountStyles.sectionTitle}>
          Transação não encontrada
        </h1>
        <p className={accountStyles.muted}>Esta transação não existe ou não está disponível para você. Nada foi alterado.</p>
        <Button href="/transactions">Ver transações</Button>
      </section>
    </div>
  )
}

function TransactionDetail({ id }: { id: string }) {
  const transaction = useTransaction(id)

  if (transaction.status === 'loading') {
    return (
      <div className={accountStyles.narrow}>
        {BACK}
        <div className={accountStyles.panel} role="status" aria-busy="true">
          <span className="sr-only">Carregando a transação…</span>
          <Skeleton width="md" height="title" />
          <Skeleton width="sm" height="value" />
          <Skeleton height="row" />
          <Skeleton height="row" />
        </div>
      </div>
    )
  }

  if (transaction.status === 'error') {
    if (transaction.failure.code === 'TRANSACTION_NOT_FOUND') return <NotFound />
    return (
      <div className={accountStyles.narrow}>
        {BACK}
        <section className={`${accountStyles.panel} ${accountStyles.danger}`} role="alert" aria-labelledby="txn-error-title">
          <span className={accountStyles.stateIcon} aria-hidden="true">
            <CircleAlert size={24} strokeWidth={1.75} />
          </span>
          <h1 id="txn-error-title" className={accountStyles.sectionTitle}>
            Não foi possível carregar esta transação
          </h1>
          <p className={accountStyles.muted}>{requestFailureMessage(transaction.failure.code)}</p>
          <div>
            <Button onClick={transaction.retry}>Tentar novamente</Button>
          </div>
        </section>
      </div>
    )
  }

  return <Loaded key={transaction.attempt} transaction={transaction.data} onChange={transaction.replace} onReload={transaction.retry} />
}

const ACTION_NOTICE: Record<TransitionAction, string> = {
  post: 'Transação efetivada. Ela já entra no saldo da conta e da Home.',
  cancel: 'Transação cancelada. Ela não afeta o saldo e continua no histórico.',
  reverse: 'Transação estornada. Ela saiu do saldo e continua no histórico.',
}

const CONFIRM: Record<'cancel' | 'reverse', { question: string; confirm: string; busy: string }> = {
  cancel: {
    question: 'Cancelar esta transação pendente? Ela não vai entrar no saldo e isso não pode ser desfeito.',
    confirm: 'Confirmar cancelamento',
    busy: 'Cancelando…',
  },
  reverse: {
    question:
      'Estornar esta transação? Ela sai do saldo e isso não pode ser desfeito. Para corrigir valor ou data, estorne e registre de novo.',
    confirm: 'Confirmar estorno',
    busy: 'Estornando…',
  },
}

function Loaded({
  transaction,
  onChange,
  onReload,
}: {
  transaction: Transaction
  onChange: (transaction: Transaction) => void
  onReload: () => void
}) {
  const router = useRouter()
  const accounts = useAccounts()
  const names = accountNames(accounts)
  const categories = useCategories()
  const categoriesByCode = categoryNames(categories)
  const nature = acceptedNature(transaction.type)
  const [notice, setNotice] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [busy, setBusy] = useState<TransitionAction | null>(null)
  const [confirming, setConfirming] = useState<'cancel' | 'reverse' | null>(null)
  const inFlight = useRef(false)

  const Icon = TRANSACTION_TYPE_ICON[transaction.type] ?? FallbackTransactionIcon
  const typeLabel = transactionTypeLabel(transaction)
  const badge = statusBadge(transaction.status)
  const voided = transaction.status === 'CANCELLED' || transaction.status === 'REVERSED'
  const accountName = (accountId: string) => names.get(accountId) ?? (accounts.status === 'loading' ? 'Carregando…' : 'Conta')
  const cards = useCards()
  const cardsById = cardNames(cards)
  const cardName = (cardId: string) => cardsById.get(cardId) ?? (cards.status === 'loading' ? 'Carregando…' : 'Cartão')

  async function run(action: TransitionAction) {
    if (inFlight.current) return
    inFlight.current = true
    setBusy(action)
    setNotice(null)
    setActionError(null)
    const result = await transitionTransaction(transaction.id, action)
    inFlight.current = false
    setBusy(null)
    setConfirming(null)
    if (!result.ok) {
      if (result.code === 'TRANSACTION_NOT_FOUND') return onReload()
      return setActionError(result.code)
    }
    onChange(result.data)
    setNotice(ACTION_NOTICE[action])
  }

  return (
    <div className={accountStyles.narrow}>
      {BACK}
      <header className={styles.hero}>
        <span className={`${styles.tile} ${styles.tileLarge} ${styles[transactionTone(transaction.type)]}`} aria-hidden="true">
          <Icon size={24} strokeWidth={1.75} />
        </span>
        <div className={styles.heroText}>
          <h1 className={accountStyles.pageTitle}>{transaction.description}</h1>
          <p className={accountStyles.meta}>
            <time dateTime={transaction.occurredOn}>{formatLongDate(transaction.occurredOn)}</time> · {typeLabel}
          </p>
        </div>
      </header>

      <section className={accountStyles.summary} aria-labelledby="amount-title">
        <div className={accountStyles.summaryMain}>
          <h2 id="amount-title" className={accountStyles.summaryLabel}>
            Valor
          </h2>
          <p className={`${styles.bigAmount} ${voided ? styles.void : ''}`}>
            <Money value={transaction.amount} sign={voided ? 'never' : transactionSign(transaction)} />
          </p>
          <div className={styles.badges}>
            <span className={`${styles.badge} ${styles[badge.tone]}`}>{badge.label}</span>
            <span className={`${styles.badge} ${styles.neutral}`}>
              {transaction.source === 'IMPORT' ? <Download size={12} strokeWidth={2} aria-hidden="true" /> : null}
              {SOURCE_LABEL[transaction.source] ?? 'Origem desconhecida'}
            </span>
          </div>
        </div>
        <p className={accountStyles.muted}>
          {transaction.reclassifiedAsTransactionId
            ? 'Marcada como reembolso: foi substituída por um reembolso de mesmo valor, e o saldo não mudou. O registro fica guardado no histórico.'
            : statusEffect(transaction.status)}
        </p>
      </section>

      <div className={accountStyles.live} role="status">
        {notice}
      </div>
      {actionError ? (
        <div className={accountStyles.error} role="alert">
          <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
          <div className={accountStyles.errorBody}>
            <span>{requestFailureMessage(actionError)}</span>
            {actionError === 'TRANSACTION_STATUS_CONFLICT' || actionError === 'CONFLICT' ? (
              <Button variant="secondary" onClick={onReload}>
                Recarregar dados
              </Button>
            ) : null}
          </div>
        </div>
      ) : null}

      <section className={accountStyles.panel} aria-labelledby="facts-title">
        <h2 id="facts-title" className={accountStyles.sectionTitle}>
          Detalhes
        </h2>
        <dl className={styles.facts}>
          {transaction.accountId ? (
            <div className={styles.fact}>
              <dt>{transaction.destinationAccountId || transaction.destinationCardId ? 'Conta de origem' : 'Conta'}</dt>
              <dd>
                <Link href={`/accounts/${transaction.accountId}`} className={styles.link}>
                  {accountName(transaction.accountId)}
                </Link>
              </dd>
            </div>
          ) : null}
          {transaction.cardId ? (
            // D-TxnDetail: "Pago com" o cartão (só os 4 últimos dígitos — ADR-0025 §1).
            <div className={styles.fact}>
              <dt>Pago com</dt>
              <dd>{cardName(transaction.cardId)}</dd>
            </div>
          ) : null}
          {transaction.destinationCardId ? (
            <div className={styles.fact}>
              <dt>Fatura do cartão</dt>
              <dd>{cardName(transaction.destinationCardId)}</dd>
            </div>
          ) : null}
          {transaction.destinationAccountId ? (
            <div className={styles.fact}>
              <dt>Conta de destino</dt>
              <dd>
                <Link href={`/accounts/${transaction.destinationAccountId}`} className={styles.link}>
                  {accountName(transaction.destinationAccountId)}
                </Link>
              </dd>
            </div>
          ) : null}
          <div className={styles.fact}>
            <dt>Tipo</dt>
            <dd>{typeLabel}</dd>
          </div>
          {nature ? (
            <div className={styles.fact}>
              <dt>Categoria</dt>
              <dd>
                {transaction.categoryCode
                  ? (categoriesByCode.get(transaction.categoryCode) ??
                    (categories.status === 'loading' ? 'Carregando…' : 'Categoria'))
                  : NO_CATEGORY_LABEL}
                {transaction.categoryCode && transaction.categorySource
                  ? ` · ${CATEGORY_SOURCE_LABEL[transaction.categorySource]}`
                  : ''}
              </dd>
            </div>
          ) : null}
          {nature && transaction.merchantName ? (
            <div className={styles.fact}>
              <dt>Comerciante</dt>
              <dd>{transaction.merchantName}</dd>
            </div>
          ) : null}
          <div className={styles.fact}>
            <dt>Status</dt>
            <dd>{badge.label}</dd>
          </div>
          <div className={styles.fact}>
            <dt>Data</dt>
            <dd>{formatLongDate(transaction.occurredOn)}</dd>
          </div>
          <div className={styles.fact}>
            <dt>Origem</dt>
            <dd>{SOURCE_LABEL[transaction.source] ?? 'Origem desconhecida'}</dd>
          </div>
          {transaction.reclassifiedAsTransactionId ? (
            <div className={styles.fact}>
              <dt>Marcada como reembolso</dt>
              <dd>
                <Link href={`/transactions/${transaction.reclassifiedAsTransactionId}`} className={styles.link}>
                  Ver reembolso
                </Link>
              </dd>
            </div>
          ) : null}
          {transaction.reclassifiedFromTransactionId ? (
            <div className={styles.fact}>
              <dt>Substitui a receita importada</dt>
              <dd>
                <Link href={`/transactions/${transaction.reclassifiedFromTransactionId}`} className={styles.link}>
                  Ver receita original
                </Link>
              </dd>
            </div>
          ) : null}
          {transaction.refundOfTransactionId ? (
            <div className={styles.fact}>
              <dt>Reembolso de</dt>
              <dd>
                <Link href={`/transactions/${transaction.refundOfTransactionId}`} className={styles.link}>
                  Ver transação original
                </Link>
              </dd>
            </div>
          ) : null}
        </dl>
      </section>

      {transaction.status === 'POSTED' ? <RelatedInsight transactionId={transaction.id} /> : null}

      {transaction.status === 'PENDING' || transaction.status === 'POSTED' ? (
        <section className={accountStyles.panel} aria-labelledby="actions-title">
          <h2 id="actions-title" className={accountStyles.sectionTitle}>
            {transaction.status === 'PENDING' ? 'Efetivar ou cancelar' : 'Estornar'}
          </h2>
          <p className={accountStyles.muted}>
            {transaction.status === 'PENDING'
              ? 'Efetive quando o valor for confirmado; cancele se não for acontecer.'
              : 'Valor, tipo, conta e data não mudam depois do lançamento. Para corrigir, estorne e registre de novo — o histórico fica completo.'}
          </p>
          {confirming ? (
            <div className={styles.confirm} role="group" aria-labelledby="confirm-text">
              <p id="confirm-text" className={styles.confirmText}>
                {CONFIRM[confirming].question}
              </p>
              <div className={styles.actionRow}>
                <Button onClick={() => run(confirming)} disabled={busy !== null}>
                  {busy === confirming ? CONFIRM[confirming].busy : CONFIRM[confirming].confirm}
                </Button>
                <Button variant="secondary" onClick={() => setConfirming(null)} disabled={busy !== null}>
                  Voltar
                </Button>
              </div>
            </div>
          ) : (
            <div className={styles.actionRow}>
              {transaction.status === 'PENDING' ? (
                <>
                  <Button onClick={() => run('post')} disabled={busy !== null}>
                    <CheckCircle2 size={18} strokeWidth={1.75} aria-hidden="true" />
                    {busy === 'post' ? 'Efetivando…' : 'Efetivar'}
                  </Button>
                  <Button variant="secondary" onClick={() => setConfirming('cancel')} disabled={busy !== null}>
                    <Ban size={18} strokeWidth={1.75} aria-hidden="true" />
                    Cancelar transação
                  </Button>
                </>
              ) : (
                <Button variant="secondary" onClick={() => setConfirming('reverse')} disabled={busy !== null}>
                  <Undo2 size={18} strokeWidth={1.75} aria-hidden="true" />
                  Estornar
                </Button>
              )}
            </div>
          )}
        </section>
      ) : null}

      {transaction.type === 'EXPENSE' && transaction.status === 'POSTED' && transaction.accountId ? (
        // Reembolso pela UI (pendência do M2): o formulário já vem com a conta e o vínculo com esta despesa.
        <section className={accountStyles.panel} aria-labelledby="refund-title">
          <h2 id="refund-title" className={accountStyles.sectionTitle}>
            Recebeu de volta?
          </h2>
          <p className={accountStyles.muted}>
            Registre o reembolso desta despesa. Ele reduz o gasto e não é contado como receita.
          </p>
          <div className={styles.actionRow}>
            <Button
              variant="secondary"
              href={`/transactions/new?type=REFUND&accountId=${transaction.accountId}&refundOf=${transaction.id}`}
            >
              Registrar reembolso
            </Button>
          </div>
        </section>
      ) : null}

      {canMarkAsRefund(transaction) ? (
        <RefundMarkSection
          transaction={transaction}
          onMarked={(refund) => router.push(`/transactions/${refund.id}`)}
          onConflict={onReload}
        />
      ) : null}

      {nature ? (
        <CategoryForm
          key={transaction.categoryCode ?? ''}
          transaction={transaction}
          nature={nature}
          categories={categories}
          onSaved={(saved) => {
            onChange(saved)
            setNotice(saved.categoryCode ? 'Categoria atualizada.' : 'Categoria removida. A transação ficou sem categoria.')
          }}
          onConflict={onReload}
        />
      ) : null}

      <DescriptionForm transaction={transaction} onSaved={(saved) => {
        onChange(saved)
        setNotice('Descrição atualizada.')
      }} onConflict={onReload} />
    </div>
  )
}

/**
 * "Alterar categoria" (ADR-0012 §7): só as categorias ativas da natureza que o tipo aceita (despesa e reembolso →
 * despesa; receita → receita), mais "Sem categoria". Uma categoria já desativada continua visível enquanto for a atual.
 */
function CategoryForm({
  transaction,
  nature,
  categories,
  onSaved,
  onConflict,
}: {
  transaction: Transaction
  nature: Category['nature']
  categories: ReturnType<typeof useCategories>
  onSaved: (transaction: Transaction) => void
  onConflict: () => void
}) {
  const id = useId()
  const current = transaction.categoryCode ?? ''
  const [value, setValue] = useState(current)
  const [applyToSimilar, setApplyToSimilar] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const select = useRef<HTMLSelectElement>(null)

  // O select fica desabilitado durante o envio: o foco no erro só funciona depois que ele volta a ser habilitado.
  useEffect(() => {
    if (error && !sending) select.current?.focus()
  }, [error, sending])

  const options =
    categories.status === 'success'
      ? categories.data.filter((c) => c.nature === nature && (c.active || c.code === transaction.categoryCode))
      : []

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (sending) return
    setFormError(null)
    setSending(true)
    const result = await categorizeTransaction(transaction.id, value || null, applyToSimilar && value !== '')
    setSending(false)
    if (result.ok) return onSaved(result.data)
    const detail = result.details?.find((issue) => issue.field === 'categoryCode')
    if (detail) {
      setError(categoryErrorMessage(detail.code))
    } else if (result.code === 'CONFLICT' || result.code === 'TRANSACTION_NOT_FOUND') {
      onConflict()
    } else {
      setFormError(requestFailureMessage(result.code))
    }
  }

  return (
    <section className={accountStyles.panel} aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={accountStyles.sectionTitle}>
        Alterar categoria
      </h2>
      {categories.status === 'loading' ? (
        <div role="status" aria-busy="true">
          <span className="sr-only">Carregando categorias…</span>
          <Skeleton height="row" />
        </div>
      ) : categories.status === 'error' ? (
        <div className={accountStyles.error} role="alert">
          <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
          <div className={accountStyles.errorBody}>
            <span>Não conseguimos carregar as categorias agora. Nada foi alterado.</span>
            <Button variant="secondary" onClick={categories.retry}>
              Tentar novamente
            </Button>
          </div>
        </div>
      ) : (
        <form className={accountStyles.form} onSubmit={submit} aria-busy={sending} noValidate>
          <div className={accountStyles.field}>
            <label htmlFor={`${id}-category`} className={accountStyles.label}>
              Categoria
            </label>
            <select
              ref={select}
              id={`${id}-category`}
              className={accountStyles.select}
              value={value}
              disabled={sending}
              onChange={(event) => {
                setValue(event.target.value)
                setError(null)
              }}
              aria-invalid={error ? true : undefined}
              aria-describedby={[`${id}-hint`, error ? `${id}-error` : null].filter(Boolean).join(' ')}
            >
              <option value="">{NO_CATEGORY_LABEL}</option>
              {options.map((category) => (
                <option key={category.code} value={category.code}>
                  {category.active ? category.name : `${category.name} (fora de uso)`}
                </option>
              ))}
            </select>
            <p id={`${id}-hint`} className={accountStyles.fieldHint}>
              {nature === 'INCOME' ? 'Categorias de receita.' : 'Categorias de despesa.'} A categoria organiza as
              análises e não muda o valor nem o saldo.
            </p>
            {transaction.merchantName && value !== '' ? (
              <label className={accountStyles.fieldHint}>
                <input
                  type="checkbox"
                  checked={applyToSimilar}
                  disabled={sending}
                  onChange={(event) => setApplyToSimilar(event.target.checked)}
                />{' '}
                Aplicar a outras transações de {transaction.merchantName} que você ainda não categorizou
              </label>
            ) : null}
            {error ? (
              <p id={`${id}-error`} className={accountStyles.fieldError}>
                <CircleAlert size={16} strokeWidth={1.75} aria-hidden="true" />
                <span>{error}</span>
              </p>
            ) : null}
          </div>
          {formError ? (
            <p className={accountStyles.error} role="alert">
              <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
              <span>{formError}</span>
            </p>
          ) : null}
          <div className={accountStyles.actions}>
            <Button type="submit" variant="secondary" disabled={sending || (value === current && !applyToSimilar)}>
              {sending ? 'Salvando…' : 'Salvar categoria'}
            </Button>
          </div>
        </form>
      )}
    </section>
  )
}

function DescriptionForm({
  transaction,
  onSaved,
  onConflict,
}: {
  transaction: Transaction
  onSaved: (transaction: Transaction) => void
  onConflict: () => void
}) {
  const id = useId()
  const [value, setValue] = useState(transaction.description)
  const [error, setError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const input = useRef<HTMLInputElement>(null)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (sending) return
    setFormError(null)
    const text = value.trim()
    const code = !text ? 'REQUIRED' : text.length > TRANSACTION_DESCRIPTION_MAX_LENGTH ? 'TOO_LONG' : null
    if (code) {
      setError(fieldErrorMessage('description', code))
      input.current?.focus()
      return
    }
    setSending(true)
    const result = await updateDescription(transaction.id, text)
    setSending(false)
    if (result.ok) return onSaved(result.data)
    const detail = result.details?.find((issue) => issue.field === 'description')
    if (detail) {
      setError(fieldErrorMessage('description', detail.code))
      input.current?.focus()
    } else if (result.code === 'CONFLICT' || result.code === 'TRANSACTION_NOT_FOUND') {
      onConflict()
    } else {
      setFormError(requestFailureMessage(result.code))
    }
  }

  return (
    <section className={accountStyles.panel} aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={accountStyles.sectionTitle}>
        Editar descrição
      </h2>
      <form className={accountStyles.form} onSubmit={submit} aria-busy={sending} noValidate>
        <div className={accountStyles.field}>
          <label htmlFor={`${id}-description`} className={accountStyles.label}>
            Descrição
          </label>
          <input
            ref={input}
            id={`${id}-description`}
            className={accountStyles.input}
            value={value}
            disabled={sending}
            autoComplete="off"
            onChange={(event) => {
              setValue(event.target.value)
              setError(null)
            }}
            aria-invalid={error ? true : undefined}
            aria-describedby={[`${id}-hint`, error ? `${id}-error` : null].filter(Boolean).join(' ')}
          />
          <p id={`${id}-hint`} className={accountStyles.fieldHint}>
            Só a descrição pode ser alterada. Até {TRANSACTION_DESCRIPTION_MAX_LENGTH} caracteres.
          </p>
          {error ? (
            <p id={`${id}-error`} className={accountStyles.fieldError}>
              <CircleAlert size={16} strokeWidth={1.75} aria-hidden="true" />
              <span>{error}</span>
            </p>
          ) : null}
        </div>
        {formError ? (
          <p className={accountStyles.error} role="alert">
            <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
            <span>{formError}</span>
          </p>
        ) : null}
        <div className={accountStyles.actions}>
          <Button type="submit" variant="secondary" disabled={sending || value.trim() === transaction.description}>
            {sending ? 'Salvando…' : 'Salvar descrição'}
          </Button>
        </div>
      </form>
    </section>
  )
}
