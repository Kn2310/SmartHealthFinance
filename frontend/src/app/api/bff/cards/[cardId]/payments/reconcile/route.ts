import type { NextRequest } from 'next/server'
import { reconcileCardPayment } from '@/server/cards'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ cardId: string }> }

/** Débito importado na conta → pagamento da fatura deste cartão (ADR-0025 §7, adendo). */
export async function POST(request: NextRequest, { params }: Context) {
  return reconcileCardPayment(request, params)
}
