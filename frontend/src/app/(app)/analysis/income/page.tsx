import type { Metadata } from 'next'
import { Suspense } from 'react'
import { AnalysisSkeleton } from '@/features/analysis/parts'
import { AnalysisView } from '@/features/analysis/AnalysisView'

export const metadata: Metadata = { title: 'Receitas · Análise' }

export default function Page() {
  return (
    // useSearchParams (mês e comparação na URL) exige Suspense.
    <Suspense fallback={<AnalysisSkeleton />}>
      <AnalysisView tab="income" />
    </Suspense>
  )
}
