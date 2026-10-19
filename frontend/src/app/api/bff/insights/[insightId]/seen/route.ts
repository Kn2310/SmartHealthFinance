import type { NextRequest } from 'next/server'
import { insightAction } from '@/server/insights-actions'

export const dynamic = 'force-dynamic'

/** Marca como visto (ADR-0023 §3); idempotente. */
export async function POST(request: NextRequest, { params }: { params: Promise<{ insightId: string }> }) {
  return insightAction(request, params, 'seen')
}
