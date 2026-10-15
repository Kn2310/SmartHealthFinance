'use client'

import { CircleAlert, RotateCcw, Search } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { Skeleton } from '@/components/ui/Skeleton'
import { formatShortDate } from '@/lib/format/date'
import { fetchTransactions, markAsRefund, type Transaction } from './api'
import { refundMarkErrorMessage, requestFailureMessage } from './messages'
import accountStyles from '@/features/accounts/Accounts.module.css'
import styles from './Transactions.module.css'

/** Quantas compras candidatas mostrar: o suficiente para achar a certa sem virar uma segunda lista de transações. */
const MAX_CANDIDATES = 10

/** Só receita importada e lançada pode virar reembolso (o estorno do extrato entra como receita — ADR-0009 §9). */
export const canMarkAsRefund = (transaction: Transaction) =>
  transaction.type === 'INCOME' && transaction.source === 'IMPORT' && transaction.status === 'POSTED'

type Candidates =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'error'; code: string }
  | { status: 'success'; items: Transaction[] }

/**
 * "Marcar como reembolso": estorna a receita importada e cria um reembolso igual, com vínculo opcional à compra
 * original (despesas lançadas até a data do estorno). Toda a regra fica no backend; aqui só se escolhe o vínculo.
 */
export function RefundMarkSection({
  transaction,
  onMarked,
  onConflict,
}: {
  transaction: Transaction
  onMarked: (refund: Transaction) => void
  onConflict: () => void
}) {
  const id = useId()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState(transaction.merchantName ?? '')
  const [candidates, setCandidates] = useState<Candidates>({ status: 'idle' })
  const [link, setLink] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)

  async function search(event?: React.FormEvent) {
    event?.preventDefault()
    setCandidates({ status: 'loading' })
    const result = await fetchTransactions({
      type: 'EXPENSE',
      status: 'POSTED',
      to: transaction.occurredOn,
      q: query.trim() || undefined,
    })
    if (!result.ok) return setCandidates({ status: 'error', code: result.code })
    setCandidates({ status: 'success', items: result.data.items.slice(0, MAX_CANDIDATES) })
    if (!result.data.items.some((item) => item.id === link)) setLink('')
  }

  async function confirm() {
    if (inFlight.current) return
    inFlight.current = true
    setSending(true)
    setError(null)
    const result = await markAsRefund(transaction.id, link || null)
    inFlight.current = false
    setSending(false)
    if (result.ok) return onMarked(result.data)
    if (result.code === 'TRANSACTION_NOT_FOUND' || result.code === 'TRANSACTION_STATUS_CONFLICT') return onConflict()
    const detail = result.details?.[0]
    setError(detail ? refundMarkErrorMessage(detail.code) : requestFailureMessage(result.code))
  }

  return (
    <section className={accountStyles.panel} aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={accountStyles.sectionTitle}>
        É um estorno ou reembolso?
      </h2>
      <p className={accountStyles.muted}>
        Estornos do extrato entram como receita. Marcando como reembolso, esta entrada deixa de contar como receita e
        passa a abater suas despesas. O saldo não muda, e a receita original fica no histórico como estornada.
      </p>

      {!open ? (
        <div className={styles.actionRow}>
          <Button variant="secondary" onClick={() => setOpen(true)}>
            <RotateCcw size={18} strokeWidth={1.75} aria-hidden="true" />
            Marcar como reembolso
          </Button>
        </div>
      ) : (
        <div className={styles.confirm} role="group" aria-labelledby={`${id}-confirm`}>
          <p id={`${id}-confirm`} className={styles.confirmText}>
            Se quiser, escolha a compra que foi reembolsada. Sem vínculo, a entrada continua sendo tratada como
            reembolso.
          </p>

          <form className={styles.searchRow} onSubmit={search} role="search" aria-label="Buscar a compra original">
            <div className={accountStyles.field}>
              <label htmlFor={`${id}-query`} className={accountStyles.label}>
                Buscar a compra original (opcional)
              </label>
              <input
                id={`${id}-query`}
                className={accountStyles.input}
                value={query}
                disabled={sending}
                autoComplete="off"
                onChange={(event) => setQuery(event.target.value)}
              />
            </div>
            <Button type="submit" variant="secondary" disabled={sending || candidates.status === 'loading'}>
              <Search size={18} strokeWidth={1.75} aria-hidden="true" />
              Buscar
            </Button>
          </form>

          <fieldset className={styles.fieldset} disabled={sending}>
            <legend className={styles.legend}>Compra original</legend>
            <div className={styles.choiceList}>
              <label className={styles.checkRow}>
                <input
                  type="radio"
                  name={`${id}-link`}
                  className={styles.checkbox}
                  checked={link === ''}
                  onChange={() => setLink('')}
                />
                <span className={styles.checkLabel}>Sem vínculo</span>
              </label>
              {candidates.status === 'success'
                ? candidates.items.map((item) => (
                    <label key={item.id} className={styles.checkRow}>
                      <input
                        type="radio"
                        name={`${id}-link`}
                        className={styles.checkbox}
                        checked={link === item.id}
                        onChange={() => setLink(item.id)}
                      />
                      <span className={styles.checkText}>
                        <span className={styles.checkLabel}>{item.description}</span>
                        <span className={accountStyles.fieldHint}>
                          {formatShortDate(item.occurredOn)} · <Money value={item.amount} sign="never" />
                        </span>
                      </span>
                    </label>
                  ))
                : null}
            </div>
            {candidates.status === 'loading' ? (
              <div role="status" aria-busy="true">
                <span className="sr-only">Buscando compras…</span>
                <Skeleton height="row" />
              </div>
            ) : null}
            {candidates.status === 'success' && candidates.items.length === 0 ? (
              <p className={accountStyles.fieldHint} role="status">
                Nenhuma despesa lançada até {formatShortDate(transaction.occurredOn)} com essa busca.
              </p>
            ) : null}
            {candidates.status === 'error' ? (
              <p className={accountStyles.fieldHint} role="alert">
                {requestFailureMessage(candidates.code)}
              </p>
            ) : null}
          </fieldset>

          {error ? (
            <p className={accountStyles.error} role="alert">
              <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
              <span>{error}</span>
            </p>
          ) : null}

          <div className={styles.actionRow}>
            <Button onClick={confirm} disabled={sending}>
              {sending ? 'Marcando…' : 'Confirmar: marcar como reembolso'}
            </Button>
            <Button variant="secondary" onClick={() => setOpen(false)} disabled={sending}>
              Voltar
            </Button>
          </div>
        </div>
      )}
    </section>
  )
}
