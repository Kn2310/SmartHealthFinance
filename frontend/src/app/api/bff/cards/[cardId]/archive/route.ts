import type { NextRequest } from 'next/server'
import { changeCardStatus } from '@/server/cards'

export const dynamic = 'force-dynamic'

/** Arquiva o cartão (ADR-0025 §1): sai das listas e não recebe lançamentos novos; o histórico continua. */
export async function POST(request: NextRequest, { params }: { params: Promise<{ cardId: string }> }) {
  return changeCardStatus(request, params, 'archive')
}
