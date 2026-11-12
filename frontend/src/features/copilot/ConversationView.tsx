'use client'

import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import { CopilotWorkspace } from './CopilotWorkspace'
import { useConversation, turnsFromConversation } from './hooks'
import { CONVERSATION_NOT_FOUND, errorMessage } from './messages'
import styles from './Copilot.module.css'

/** D-CopilotChat / M-CopilotChat (`/copilot/{id}`): conversa guardada, aberta do histórico (ADR-0041 §1). */
export function ConversationView({ conversationId }: { conversationId: string | null }) {
  if (!conversationId) return <NotFound />
  return <Loaded conversationId={conversationId} />
}

function NotFound() {
  return (
    <div className={styles.standalone}>
      <h1 className={styles.title}>{CONVERSATION_NOT_FOUND.title}</h1>
      <section className={styles.state} role="alert" aria-label={CONVERSATION_NOT_FOUND.title}>
        <p>{CONVERSATION_NOT_FOUND.text}</p>
        <Button href="/copilot" variant="secondary">{CONVERSATION_NOT_FOUND.action}</Button>
      </section>
    </div>
  )
}

function Loaded({ conversationId }: { conversationId: string }) {
  const conversation = useConversation(conversationId)

  if (conversation.status === 'loading') {
    return (
      <div className={styles.standalone} role="status" aria-busy="true">
        <span className="sr-only">Carregando…</span>
        <div aria-hidden="true" className={styles.skeletons}>
          <Skeleton width="md" height="title" />
          <Skeleton width="full" height="row" />
          <Skeleton width="lg" height="row" />
        </div>
      </div>
    )
  }
  if (conversation.status === 'error') {
    if (conversation.failure.code === 'COPILOT_CONVERSATION_NOT_FOUND') return <NotFound />
    const error = errorMessage(null)
    return (
      <div className={styles.standalone}>
        <h1 className={styles.title}>{error.title}</h1>
        <section className={styles.state} role="alert" aria-label={error.title}>
          <p>{error.text}</p>
          <div className={styles.actions}>
            <Button onClick={conversation.retry}>{error.retry}</Button>
            <Button href="/copilot" variant="secondary">{CONVERSATION_NOT_FOUND.action}</Button>
          </div>
        </section>
      </div>
    )
  }
  const { conversation: data, fresh } = conversation.data
  return (
    <CopilotWorkspace
      key={data.id}
      entry={null}
      conversation={{ id: data.id, title: data.title, turns: turnsFromConversation(data, !fresh) }}
    />
  )
}
