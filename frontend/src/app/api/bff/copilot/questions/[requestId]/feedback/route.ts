import type { NextRequest } from 'next/server'
import { sendFeedback } from '@/server/copilot'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ requestId: string }> }

/** Avaliação de uma resposta (ADR-0040 §8); o último envio vale. */
export async function PUT(request: NextRequest, { params }: Context) {
  const { requestId } = await params
  return sendFeedback(request, requestId)
}
