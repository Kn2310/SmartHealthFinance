import type { NextRequest } from 'next/server'
import { insightAction } from '@/server/insights-actions'

export const dynamic = 'force-dynamic'

/** Dispensa um insight ativo (ADR-0023 §3); definitivo e idempotente. Resolvido ou expirado → 409 INSIGHT_NOT_ACTIVE. */
export async function POST(request: NextRequest, { params }: { params: Promise<{ insightId: string }> }) {
  return insightAction(request, params, 'dismiss')
}
