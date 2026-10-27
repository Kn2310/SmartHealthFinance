import type { Metadata } from 'next'
import { InvoiceView } from '@/features/cards/InvoiceView'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Fatura' }

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/

/** D-Invoice de um mês de vencimento (`YYYY-MM`); mês malformado cai no "fatura não encontrada" do BFF. */
export default async function InvoicePage({ params }: { params: Promise<{ cardId: string; dueMonth: string }> }) {
  const { cardId, dueMonth } = await params
  return <InvoiceView cardId={isUuid(cardId) ? cardId : null} dueMonth={MONTH.test(dueMonth) ? dueMonth : 'invalid'} />
}
