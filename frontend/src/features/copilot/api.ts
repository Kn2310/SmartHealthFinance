import type { components } from '@/lib/api/schema'
import { bffCall } from '@/lib/bff-client'

type Schemas = components['schemas']
export type CopilotAnswer = Schemas['CopilotAnswer']
export type AnswerBody = Schemas['CopilotAnswerBody']
export type AnswerValue = Schemas['CopilotValue']
export type AnswerAction = Schemas['CopilotAction']
export type Segment = Schemas['CopilotSegment']
export type EntryContext = NonNullable<Schemas['CopilotAskQuestionRequest']['context']>
export type AskRequest = Schemas['CopilotAskQuestionRequest']
export type Usage = Schemas['CopilotUsage']
export type ConversationPage = Schemas['CopilotConversationPage']
export type ConversationSummary = Schemas['CopilotConversationSummary']
export type Conversation = Schemas['CopilotConversation']
export type FeedbackRequest = Schemas['CopilotFeedbackRequest']

const JSON_BODY = { 'Content-Type': 'application/json' }
const BASE = '/api/bff/copilot'
const conversationPath = (id: string) => `${BASE}/conversations/${encodeURIComponent(id)}`

/**
 * Pergunta síncrona (ADR-0040 §1). A `Idempotency-Key` é gerada por envio pelo chamador e só se repete no "Tentar
 * novamente" do mesmo envio (ADR-0041 §1).
 */
export const askQuestion = (body: AskRequest, idempotencyKey: string) =>
  bffCall<CopilotAnswer>(`${BASE}/questions`, {
    method: 'POST',
    headers: { ...JSON_BODY, 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  })

export const fetchUsage = () => bffCall<Usage>(`${BASE}/usage`)

export const fetchConversations = (before?: string) =>
  bffCall<ConversationPage>(`${BASE}/conversations${before ? `?before=${encodeURIComponent(before)}` : ''}`)

export const fetchConversation = (id: string) => bffCall<Conversation>(conversationPath(id))

export const deleteConversation = (id: string) =>
  bffCall<{ deleted: true }>(conversationPath(id), { method: 'DELETE' })

export const deleteAllConversations = () => bffCall<{ deleted: true }>(`${BASE}/conversations`, { method: 'DELETE' })

export const sendFeedback = (requestId: string, feedback: FeedbackRequest) =>
  bffCall<{ saved: true }>(`${BASE}/questions/${encodeURIComponent(requestId)}/feedback`, {
    method: 'PUT',
    headers: JSON_BODY,
    body: JSON.stringify(feedback),
  })
