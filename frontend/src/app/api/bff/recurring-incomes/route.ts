import type { NextRequest } from 'next/server'
import { recurringList } from '@/server/recurring'

export const dynamic = 'force-dynamic'

/** Séries recorrentes (incomes), com status opcional na query (ADR-0032 §2). */
export async function GET(request: NextRequest) {
  return recurringList(request, 'incomes')
}
