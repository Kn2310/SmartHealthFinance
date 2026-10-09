import { Download } from 'lucide-react'
import Link from 'next/link'
import { Money } from '@/components/ui/Money'
import {
  FallbackTransactionIcon,
  TRANSACTION_TYPE_ICON,
  transactionTone,
  transactionTypeLabel,
} from '@/features/home/labels'
import type { Transaction } from './api'
import { statusBadge, transactionSign } from './labels'
import styles from './Transactions.module.css'

interface Props {
  transaction: Transaction
  names: Map<string, string>
  /** Página da conta: o sentido da transferência é relativo a ela e o nome da própria conta não se repete. */
  perspectiveAccountId?: string
}

/** Linha da lista (Transaction Item do DS) que abre o detalhe. Status e origem sempre com texto, nunca só cor. */
export function TransactionRow({ transaction, names, perspectiveAccountId }: Props) {
  const Icon = TRANSACTION_TYPE_ICON[transaction.type] ?? FallbackTransactionIcon
  const typeLabel = transactionTypeLabel(transaction)
  const account = names.get(transaction.accountId) ?? 'Conta'
  const destination = transaction.destinationAccountId ? (names.get(transaction.destinationAccountId) ?? 'Conta') : null
  const where = destination ? `${account} → ${destination}` : perspectiveAccountId ? null : account
  const status = transaction.status === 'POSTED' ? null : statusBadge(transaction.status)
  const voided = transaction.status === 'CANCELLED' || transaction.status === 'REVERSED'

  return (
    <li className={styles.row}>
      <Link href={`/transactions/${transaction.id}`} className={styles.rowLink}>
        <span className={`${styles.tile} ${styles[transactionTone(transaction.type)]}`} aria-hidden="true">
          <Icon size={20} strokeWidth={1.75} />
        </span>
        <span className={styles.text}>
          <span className={styles.title}>{transaction.description}</span>
          <span className={styles.meta}>{[typeLabel, where].filter(Boolean).join(' · ')}</span>
          {status || transaction.source === 'IMPORT' ? (
            <span className={styles.badges}>
              {status ? <span className={`${styles.badge} ${styles[status.tone]}`}>{status.label}</span> : null}
              {transaction.source === 'IMPORT' ? (
                // Trust UX (design/specs/01-foundations): "Importado" = selo neutro + ícone de download.
                <span className={`${styles.badge} ${styles.neutral}`}>
                  <Download size={12} strokeWidth={2} aria-hidden="true" />
                  Importado
                </span>
              ) : null}
            </span>
          ) : null}
        </span>
        <span className={`${styles.amount} ${voided ? styles.void : status ? styles.pendingAmount : ''}`}>
          <Money value={transaction.amount} sign={voided ? 'never' : transactionSign(transaction, perspectiveAccountId)} />
        </span>
      </Link>
    </li>
  )
}
