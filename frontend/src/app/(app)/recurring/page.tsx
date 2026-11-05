import type { Metadata } from 'next'
import { Suspense } from 'react'
import { RecurringView } from '@/features/forecast/RecurringView'

export const metadata: Metadata = { title: 'Recorrências' }

export default function Page() {
  return (
    // useSearchParams (aba na URL) exige Suspense.
    <Suspense fallback={null}>
      <RecurringView />
    </Suspense>
  )
}
