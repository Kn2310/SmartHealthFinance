import type { Metadata } from 'next'
import { TransactionDetailView } from '@/features/transactions/TransactionDetailView'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Transação' }

export default async function TransactionPage({ params }: { params: Promise<{ transactionId: string }> }) {
  const { transactionId } = await params
  return <TransactionDetailView id={isUuid(transactionId) ? transactionId : null} />
}
