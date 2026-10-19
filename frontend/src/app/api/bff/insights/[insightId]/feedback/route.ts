import type { NextRequest } from 'next/server'
import { insightAction } from '@/server/insights-actions'

export const dynamic = 'force-dynamic'

/** "Útil / Não útil" (ADR-0021 §8): `{value: "USEFUL" | "NOT_USEFUL" | null}`; vale o último. */
export async function PUT(request: NextRequest, { params }: { params: Promise<{ insightId: string }> }) {
  return insightAction(request, params, 'feedback')
}
