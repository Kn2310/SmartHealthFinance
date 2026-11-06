import type { NextRequest } from 'next/server'
import { recurringAction } from '@/server/recurring'

export const dynamic = 'force-dynamic'

/** Ação ignore da série (incomes): idempotente; a resposta traz forecastState RECALCULATING. */
export async function POST(request: NextRequest, { params }: { params: Promise<{ seriesId: string }> }) {
  return recurringAction(request, 'incomes', params, 'ignore')
}
