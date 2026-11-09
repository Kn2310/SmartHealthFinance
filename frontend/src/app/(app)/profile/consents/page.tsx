import type { Metadata } from 'next'
import { ConsentsView } from '@/features/consents/ConsentsView'

export const metadata: Metadata = { title: 'Consentimentos' }

export default function ConsentsPage() {
  return <ConsentsView />
}
