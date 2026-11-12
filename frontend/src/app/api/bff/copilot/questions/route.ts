import type { NextRequest } from 'next/server'
import { askQuestion } from '@/server/copilot'

export const dynamic = 'force-dynamic'

/** Pergunta ao Copilot (ADR-0040 §3), idempotente pela `Idempotency-Key` do browser. */
export async function POST(request: NextRequest) {
  return askQuestion(request)
}
