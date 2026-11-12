import type { NextRequest } from 'next/server'
import { deleteConversation, getConversation } from '@/server/copilot'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ conversationId: string }> }

export async function GET(_request: NextRequest, { params }: Context) {
  const { conversationId } = await params
  return getConversation(conversationId)
}

export async function DELETE(request: NextRequest, { params }: Context) {
  const { conversationId } = await params
  return deleteConversation(request, conversationId)
}
