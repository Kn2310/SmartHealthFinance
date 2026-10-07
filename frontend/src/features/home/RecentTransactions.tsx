import Link from 'next/link'
import { ChevronRight } from 'lucide-react'
import type { OverviewTransaction } from '@/lib/api/types'
import { Card } from '@/components/ui/Card'
import { TransactionItem } from './TransactionItem'
import styles from './RecentTransactions.module.css'

interface Props {
  transactions: OverviewTransaction[]
  id?: string
  title?: string
  subtitle?: string
}

export function RecentTransactions({ transactions, id = 'recent', title = 'Movimentações recentes', subtitle }: Props) {
  return (
    <Card
      id={id}
      title={title}
      subtitle={subtitle}
      action={
        <Link href="/transactions" className={styles.seeAll}>
          Ver todas <ChevronRight size={16} aria-hidden="true" />
        </Link>
      }
    >
      {transactions.length > 0 ? (
        <ul>
          {transactions.map((transaction) => (
            <TransactionItem key={transaction.id} transaction={transaction} />
          ))}
        </ul>
      ) : (
        <p className={styles.empty}>Nenhuma movimentação recente neste período.</p>
      )}
    </Card>
  )
}
