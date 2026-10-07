import type { OverviewTransaction } from '@/lib/api/types'
import { formatShortDate } from '@/lib/format/date'
import { Money } from '@/components/ui/Money'
import {
  FallbackTransactionIcon,
  flowSign,
  statusLabel,
  TRANSACTION_TYPE_ICON,
  transactionTone,
  transactionTypeLabel,
} from './labels'
import styles from './TransactionItem.module.css'

export function TransactionItem({ transaction }: { transaction: OverviewTransaction }) {
  const Icon = TRANSACTION_TYPE_ICON[transaction.type] ?? FallbackTransactionIcon
  const typeLabel = transactionTypeLabel(transaction)
  const badge = statusLabel(transaction.status)
  const accounts = transaction.destinationAccount
    ? `${transaction.account.name} → ${transaction.destinationAccount.name}`
    : transaction.account.name
  const title = transaction.description?.trim() || typeLabel

  return (
    <li className={styles.item}>
      <span className={`${styles.tile} ${styles[transactionTone(transaction.type)]}`} aria-hidden="true">
        <Icon size={20} strokeWidth={1.75} />
      </span>
      <span className={styles.text}>
        <span className={styles.title}>{title}</span>
        <span className={styles.meta}>
          {accounts} · <time dateTime={transaction.occurredOn}>{formatShortDate(transaction.occurredOn)}</time>
        </span>
        {badge ? <span className={styles.status}>{badge}</span> : null}
      </span>
      <span className={`${styles.amount} ${badge ? styles.muted : ''}`}>
        <Money value={transaction.amount} sign={flowSign(transaction.flow)} />
        <span className={styles.type}>{typeLabel}</span>
      </span>
    </li>
  )
}
