import type { Metadata } from 'next'
import { MoreView } from '@/features/transactions/MoreView'

export const metadata: Metadata = { title: 'Mais' }

export default function MorePage() {
  return <MoreView />
}
