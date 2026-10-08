import type { Metadata } from 'next'
import { Suspense } from 'react'
import { ImportView } from '@/features/import/ImportView'

export const metadata: Metadata = { title: 'Importar extrato' }

export default function ImportPage() {
  return (
    // useSearchParams (batch na URL) exige Suspense.
    <Suspense fallback={null}>
      <ImportView />
    </Suspense>
  )
}
