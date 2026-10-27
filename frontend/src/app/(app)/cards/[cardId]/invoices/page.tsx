import type { Metadata } from 'next'
import { FutureInvoicesView } from '@/features/cards/FutureInvoicesView'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Próximas faturas' }

/** D-FutureInvoices: só o valor já comprometido em parcelas. */
export default async function FutureInvoicesPage({ params }: { params: Promise<{ cardId: string }> }) {
  const { cardId } = await params
  return <FutureInvoicesView cardId={isUuid(cardId) ? cardId : null} />
}
