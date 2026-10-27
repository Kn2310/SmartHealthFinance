'use client'

import { Layers } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import type { Transaction } from '@/features/transactions/api'
import { invoiceFullName } from './format'
import { useInstallmentPlan } from './hooks'
import accountStyles from '@/features/accounts/Accounts.module.css'
import styles from './Cards.module.css'

/**
 * Compra ou estorno no cartão no detalhe da transação (D-TxnDetail, ADR-0025 §14): "Ver fatura" leva à fatura da parcela
 * atual (ou da 1ª), lida do parcelamento no backend; "Ver parcelamento" quando há mais de uma parcela; "Registrar
 * estorno" para a compra lançada (ADR-0029 §7).
 */
export function CardInvoiceLinks({ transaction }: { transaction: Transaction }) {
  const purchase = transaction.type === 'EXPENSE' && transaction.status === 'POSTED'
  const cardId = transaction.cardId!
  const plan = useInstallmentPlan(purchase ? cardId : null, purchase ? transaction.id : null)
  const installments = plan.status === 'success' && plan.data ? plan.data.installments : []
  const target = installments.find((item) => item.status === 'CURRENT') ?? installments.find((item) => item.status !== 'BEFORE_TRACKING')

  return (
    <section className={accountStyles.panel} aria-labelledby="card-links-title">
      <h2 id="card-links-title" className={accountStyles.sectionTitle}>
        Cartão e fatura
      </h2>
      {purchase && plan.status === 'success' && plan.data && plan.data.count > 1 ? (
        <p className={styles.note}>
          <Layers size={16} strokeWidth={1.75} aria-hidden="true" />
          <span>
            Compra em {plan.data.count} parcelas
            {target ? `; a ${target.status === 'CURRENT' ? 'parcela atual' : 'primeira acompanhada'} está na ${invoiceFullName(target.dueMonth).toLowerCase()}` : ''}.
          </span>
        </p>
      ) : null}
      {purchase && plan.status === 'error' ? (
        <p className={styles.caption}>Não conseguimos localizar a fatura desta compra agora. O cartão continua disponível.</p>
      ) : null}
      <div className={styles.actions}>
        {target ? <Button href={`/cards/${cardId}/invoices/${target.dueMonth}`}>Ver fatura</Button> : null}
        {purchase && plan.status === 'success' && plan.data && plan.data.count > 1 ? (
          <Button href={`/cards/${cardId}/installments/${transaction.id}`} variant="secondary">
            Ver parcelamento
          </Button>
        ) : null}
        <Button href={`/cards/${cardId}`} variant="secondary">
          Ver cartão
        </Button>
        {purchase ? (
          <Button href={`/transactions/new?type=CARD_CREDIT&cardId=${cardId}&refundOf=${transaction.id}`} variant="secondary">
            Registrar estorno
          </Button>
        ) : null}
      </div>
    </section>
  )
}
