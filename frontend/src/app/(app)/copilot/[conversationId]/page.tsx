import type { Metadata } from 'next'
import { ConversationView } from '@/features/copilot/ConversationView'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Copilot' }

/** D-CopilotChat. Id que não é UUID nem chega ao BFF: é o mesmo "não encontrada" de uma conversa de outra pessoa. */
export default async function ConversationPage({ params }: { params: Promise<{ conversationId: string }> }) {
  const { conversationId } = await params
  return <ConversationView conversationId={isUuid(conversationId) ? conversationId : null} />
}
