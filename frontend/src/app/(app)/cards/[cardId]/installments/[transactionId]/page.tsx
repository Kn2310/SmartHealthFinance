import type { Metadata } from 'next'
import { InstallmentView } from '@/features/cards/InstallmentView'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Parcelamento' }

export default async function InstallmentPage({ params }: { params: Promise<{ cardId: string; transactionId: string }> }) {
  const { cardId, transactionId } = await params
  return (
    <InstallmentView cardId={isUuid(cardId) ? cardId : null} transactionId={isUuid(transactionId) ? transactionId : null} />
  )
}
