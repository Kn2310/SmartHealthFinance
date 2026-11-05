import type { NextRequest } from 'next/server'
import { recurringAction } from '@/server/recurring'

export const dynamic = 'force-dynamic'

/** Ação ignore da série (expenses): idempotente; a resposta traz forecastState RECALCULATING. */
export async function POST(request: NextRequest, { params }: { params: Promise<{ seriesId: string }> }) {
  return recurringAction(request, 'expenses', params, 'ignore')
}
