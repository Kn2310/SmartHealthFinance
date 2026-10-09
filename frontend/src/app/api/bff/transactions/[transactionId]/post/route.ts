import type { NextRequest } from 'next/server'
import { transitionTransaction } from '@/server/transactions'

export const dynamic = 'force-dynamic'

/** Efetiva uma transação pendente (PENDING → POSTED). Exige contas ativas no backend. */
export async function POST(request: NextRequest, { params }: { params: Promise<{ transactionId: string }> }) {
  return transitionTransaction(request, params, 'post')
}
