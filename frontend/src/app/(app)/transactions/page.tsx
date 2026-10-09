import type { Metadata } from 'next'
import { Suspense } from 'react'
import { TransactionsView } from '@/features/transactions/TransactionsView'

export const metadata: Metadata = { title: 'Transações' }

export default function TransactionsPage() {
  // Filtros na URL (useSearchParams) exigem Suspense; a lista mostra o próprio skeleton logo em seguida.
  return (
    <Suspense fallback={null}>
      <TransactionsView />
    </Suspense>
  )
}
