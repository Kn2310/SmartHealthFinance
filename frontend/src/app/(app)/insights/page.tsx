import type { Metadata } from 'next'
import { Suspense } from 'react'
import { InsightsView } from '@/features/insights/InsightsView'

export const metadata: Metadata = { title: 'Insights' }

export default function InsightsPage() {
  return (
    // Filtro na URL (useSearchParams) exige Suspense; a lista mostra o próprio skeleton logo em seguida.
    <Suspense fallback={null}>
      <InsightsView />
    </Suspense>
  )
}
