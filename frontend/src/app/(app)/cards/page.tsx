import type { Metadata } from 'next'
import { CardsView } from '@/features/cards/CardsView'

export const metadata: Metadata = { title: 'Cartões' }

export default function CardsPage() {
  return <CardsView />
}
