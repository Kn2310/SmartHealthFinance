import type { Metadata } from 'next'
import { CardDetailView, type CardTab } from '@/features/cards/CardDetailView'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Cartão' }

const TABS: CardTab[] = ['overview', 'invoices', 'installments']

/** D-CardDetail. Id que não é UUID nem chega ao BFF: é o mesmo "não encontrado" de outro Workspace. */
export default async function CardPage({
  params,
  searchParams,
}: {
  params: Promise<{ cardId: string }>
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { cardId } = await params
  const { tab } = await searchParams
  return (
    <CardDetailView
      id={isUuid(cardId) ? cardId : null}
      tab={TABS.includes(tab as CardTab) ? (tab as CardTab) : 'overview'}
    />
  )
}
