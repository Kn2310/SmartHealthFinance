import type { NextRequest } from 'next/server'
import { changeCardStatus } from '@/server/cards'

export const dynamic = 'force-dynamic'

/** Reativa um cartão arquivado (ADR-0025 §1). */
export async function POST(request: NextRequest, { params }: { params: Promise<{ cardId: string }> }) {
  return changeCardStatus(request, params, 'reactivate')
}
