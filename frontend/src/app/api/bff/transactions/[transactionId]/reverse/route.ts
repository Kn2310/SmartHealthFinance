import type { NextRequest } from 'next/server'
import { transitionTransaction } from '@/server/transactions'

export const dynamic = 'force-dynamic'

/** Estorna uma transação lançada (POSTED → REVERSED). O registro é mantido e sai do saldo. */
export async function POST(request: NextRequest, { params }: { params: Promise<{ transactionId: string }> }) {
  return transitionTransaction(request, params, 'reverse')
}
