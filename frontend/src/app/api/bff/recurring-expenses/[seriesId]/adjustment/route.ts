import type { NextRequest } from 'next/server'
import { recurringAdjustment } from '@/server/recurring'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ seriesId: string }> }

/** Ajusta valor e/ou dia da série (expenses); os valores do usuário prevalecem sobre os detectados. */
export async function PUT(request: NextRequest, { params }: Context) {
  return recurringAdjustment(request, 'expenses', params, 'PUT')
}

/** Volta aos valores detectados. */
export async function DELETE(request: NextRequest, { params }: Context) {
  return recurringAdjustment(request, 'expenses', params, 'DELETE')
}
