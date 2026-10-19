import type { Metadata } from 'next'
import { InsightDetailView } from '@/features/insights/InsightDetailView'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Insight' }

export default async function InsightPage({ params }: { params: Promise<{ insightId: string }> }) {
  const { insightId } = await params
  return <InsightDetailView id={isUuid(insightId) ? insightId : null} />
}
