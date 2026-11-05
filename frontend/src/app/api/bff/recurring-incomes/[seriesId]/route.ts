import { recurringDetail } from '@/server/recurring'

export const dynamic = 'force-dynamic'

/** Detalhe de uma série recorrente (incomes). */
export async function GET(_request: Request, { params }: { params: Promise<{ seriesId: string }> }) {
  return recurringDetail('incomes', params)
}
