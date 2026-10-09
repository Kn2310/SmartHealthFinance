import type { NextRequest } from 'next/server'
import { transitionTransaction } from '@/server/transactions'

export const dynamic = 'force-dynamic'

/** Cancela uma transação pendente (PENDING → CANCELLED). O registro é mantido. */
export async function POST(request: NextRequest, { params }: { params: Promise<{ transactionId: string }> }) {
  return transitionTransaction(request, params, 'cancel')
}
