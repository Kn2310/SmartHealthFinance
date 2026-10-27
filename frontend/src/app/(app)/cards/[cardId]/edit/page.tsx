import type { Metadata } from 'next'
import { EditCardView } from '@/features/cards/EditCardView'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Editar cartão' }

export default async function EditCardPage({ params }: { params: Promise<{ cardId: string }> }) {
  const { cardId } = await params
  return <EditCardView id={isUuid(cardId) ? cardId : null} />
}
