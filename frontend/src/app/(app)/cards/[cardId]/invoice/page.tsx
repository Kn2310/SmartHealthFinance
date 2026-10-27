import type { Metadata } from 'next'
import { InvoiceView } from '@/features/cards/InvoiceView'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Fatura atual' }

/** D-Invoice da fatura atual (período que contém hoje). */
export default async function CurrentInvoicePage({ params }: { params: Promise<{ cardId: string }> }) {
  const { cardId } = await params
  return <InvoiceView cardId={isUuid(cardId) ? cardId : null} dueMonth={null} />
}
