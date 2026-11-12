import type { NextRequest } from 'next/server'
import { deleteAllConversations, listConversations } from '@/server/copilot'

export const dynamic = 'force-dynamic'

/** Histórico da pessoa no Workspace, com cursor (ADR-0040 §6). */
export async function GET(request: NextRequest) {
  return listConversations(request)
}

/** "Apagar todas" as conversas guardadas. */
export async function DELETE(request: NextRequest) {
  return deleteAllConversations(request)
}
