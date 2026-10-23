import type { NextRequest } from 'next/server'
import { recordCardOperation } from '@/server/cards'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ cardId: string }> }

/** Lançamento no cartão (ADR-0026 §10), idempotente pela `Idempotency-Key` do browser. */
export async function POST(request: NextRequest, { params }: Context) {
  return recordCardOperation(request, params, 'purchases')
}
