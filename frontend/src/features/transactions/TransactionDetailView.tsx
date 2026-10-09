'use client'

import { Ban, CheckCircle2, ChevronLeft, CircleAlert, Download, SearchX, Undo2 } from 'lucide-react'
import Link from 'next/link'
import { useId, useRef, useState } from 'react'
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
import { transitionTransaction, updateDescription, type Transaction, type TransitionAction } from './api'
import { accountNames, useAccounts, useTransaction } from './hooks'
import { SOURCE_LABEL, statusBadge, statusEffect, transactionSign } from './labels'
import { fieldErrorMessage, requestFailureMessage } from './messages'
import accountStyles from '@/features/accounts/Accounts.module.css'
import styles from './Transactions.module.css'

const BACK = (
  <Link href="/transactions" className={accountStyles.back}>
    <ChevronLeft size={18} strokeWidth={1.75} aria-hidden="true" />
    Transações
  </Link>
)

/**
 * D-TxnDetail como página (`/transactions/{id}`): valor, tipo, status e origem, conta, datas; editar a descrição
 * (único campo editável — ADR-0005 §8) e efetivar, cancelar ou estornar conforme o status permitir.
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
  const accounts = useAccounts()
  const names = accountNames(accounts)
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
        <p className={accountStyles.muted}>{statusEffect(transaction.status)}</p>
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
          <div className={styles.fact}>
            <dt>{transaction.destinationAccountId ? 'Conta de origem' : 'Conta'}</dt>
            <dd>
              <Link href={`/accounts/${transaction.accountId}`} className={styles.link}>
                {accountName(transaction.accountId)}
              </Link>
            </dd>
          </div>
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

      <DescriptionForm transaction={transaction} onSaved={(saved) => {
        onChange(saved)
        setNotice('Descrição atualizada.')
      }} onConflict={onReload} />
    </div>
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
