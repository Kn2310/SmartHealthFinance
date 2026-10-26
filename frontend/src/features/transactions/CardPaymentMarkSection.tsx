'use client'

import { CircleAlert, CreditCard } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import { fetchAllCards, reconcileCardPayment, type Card, type Transaction } from './api'
import { cardPaymentMarkErrorMessage, requestFailureMessage } from './messages'
import accountStyles from '@/features/accounts/Accounts.module.css'
import styles from './Transactions.module.css'

/** Só despesa importada, lançada e de uma conta pode virar pagamento de fatura (ADR-0025 §7, adendo). */
export const canMarkAsCardPayment = (transaction: Transaction) =>
  transaction.type === 'EXPENSE' && transaction.source === 'IMPORT' && transaction.status === 'POSTED'
  && transaction.accountId !== null

type Cards = { status: 'loading' } | { status: 'error'; code: string } | { status: 'success'; items: Card[] }

/**
 * "Marcar como pagamento de fatura": o débito do extrato da conta ("PAG FATURA") deixa de ser despesa e vira a
 * transferência para o cartão. Se o pagamento já foi registrado à mão, o backend vincula a ele em vez de criar outro.
 * Toda a regra fica no backend; aqui só se escolhe o cartão.
 */
export function CardPaymentMarkSection({
  transaction,
  onMarked,
  onConflict,
}: {
  transaction: Transaction
  onMarked: (payment: Transaction) => void
  onConflict: () => void
}) {
  const id = useId()
  const [open, setOpen] = useState(false)
  const [cards, setCards] = useState<Cards>({ status: 'loading' })
  const [cardId, setCardId] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)

  useEffect(() => {
    if (!open) return
    let active = true
    void fetchAllCards().then((result) => {
      if (!active) return
      if (!result.ok) return setCards({ status: 'error', code: result.code })
      const items = result.data.items.filter((card) => card.status === 'ACTIVE')
      setCards({ status: 'success', items })
      // Sugestão: o cartão que tem esta conta como conta de pagamento padrão; senão, o único cartão.
      const suggested = items.find((card) => card.defaultPaymentAccountId === transaction.accountId)
        ?? (items.length === 1 ? items[0] : undefined)
      setCardId((current) => current || suggested?.id || '')
    })
    return () => {
      active = false
    }
  }, [open, transaction.accountId])

  async function confirm() {
    if (inFlight.current || !cardId) return
    inFlight.current = true
    setSending(true)
    setError(null)
    const result = await reconcileCardPayment(cardId, transaction.id)
    inFlight.current = false
    setSending(false)
    if (result.ok) return onMarked(result.data.payment)
    if (result.code === 'TRANSACTION_NOT_FOUND' || result.code === 'TRANSACTION_STATUS_CONFLICT') return onConflict()
    const detail = result.details?.[0]
    setError(cardPaymentMarkErrorMessage(detail?.code ?? result.code))
  }

  return (
    <section className={accountStyles.panel} aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={accountStyles.sectionTitle}>
        É o pagamento de uma fatura?
      </h2>
      <p className={accountStyles.muted}>
        O pagamento da fatura sai da conta, mas não é gasto: as compras já foram contadas no cartão. Marcando como
        pagamento de fatura, esta saída deixa de contar como despesa e passa a abater a fatura do cartão. O saldo da conta
        não muda.
      </p>

      {!open ? (
        <div className={styles.actionRow}>
          <Button variant="secondary" onClick={() => setOpen(true)}>
            <CreditCard size={18} strokeWidth={1.75} aria-hidden="true" />
            Marcar como pagamento de fatura
          </Button>
        </div>
      ) : (
        <div className={styles.confirm} role="group" aria-labelledby={`${id}-confirm`}>
          <p id={`${id}-confirm`} className={styles.confirmText}>
            Escolha o cartão pago. Se você já registrou este pagamento à mão, ele é reconhecido e nada é duplicado.
          </p>

          {cards.status === 'loading' ? (
            <div role="status" aria-busy="true">
              <span className="sr-only">Carregando seus cartões…</span>
              <Skeleton height="row" />
            </div>
          ) : cards.status === 'error' ? (
            <p className={accountStyles.fieldHint} role="alert">
              {requestFailureMessage(cards.code)}
            </p>
          ) : cards.items.length === 0 ? (
            <p className={accountStyles.fieldHint} role="status">
              Nenhum cartão ativo. Cadastre o cartão para registrar o pagamento da fatura.
            </p>
          ) : (
            <fieldset className={styles.fieldset} disabled={sending}>
              <legend className={styles.legend}>Cartão</legend>
              <div className={styles.choiceList}>
                {cards.items.map((card) => (
                  <label key={card.id} className={styles.checkRow}>
                    <input
                      type="radio"
                      name={`${id}-card`}
                      className={styles.checkbox}
                      checked={cardId === card.id}
                      onChange={() => setCardId(card.id)}
                    />
                    <span className={styles.checkText}>
                      <span className={styles.checkLabel}>{card.nickname}</span>
                      <span className={accountStyles.fieldHint}>Cartão •••• {card.last4}</span>
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
          )}

          {error ? (
            <p className={accountStyles.error} role="alert">
              <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
              <span>{error}</span>
            </p>
          ) : null}

          <div className={styles.actionRow}>
            <Button onClick={confirm} disabled={sending || !cardId}>
              {sending ? 'Marcando…' : 'Confirmar: marcar como pagamento de fatura'}
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
