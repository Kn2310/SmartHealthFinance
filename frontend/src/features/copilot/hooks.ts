'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import type { Failure } from '@/lib/bff-client'
import { useKeyed } from '@/lib/use-keyed'
import {
  askQuestion,
  fetchConversation,
  fetchConversations,
  fetchUsage,
  type AskRequest,
  type Conversation,
  type ConversationSummary,
  type CopilotAnswer,
  type Usage,
} from './api'
import type { Entry } from './entry'
import { COPILOT_MAX_PREVIOUS_QUESTIONS, COPILOT_QUESTION_MAX_LENGTH } from '@/lib/api/contract'

/** Uso do mês e do dia. Ao recarregar depois de uma pergunta, mantém o último valor (sem piscar para "carregando"). */
export function useUsage() {
  const [attempt, setAttempt] = useState(0)
  const { value, previous } = useKeyed<Usage>(`usage#${attempt}`, async () => {
    const result = await fetchUsage()
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })
  const shown = value.status === 'loading' && previous?.status === 'success' ? previous : value
  return { ...shown, refresh: () => setAttempt((n) => n + 1) }
}

interface HistoryState {
  base: unknown
  items: ConversationSummary[]
  nextCursor: string | null
  busy: boolean
  error: boolean
}

/** Histórico com cursor: a primeira página vem do `useKeyed`; "Ver mais" acrescenta as seguintes. */
export function useHistory() {
  const [attempt, setAttempt] = useState(0)
  const { value, replace } = useKeyed<{ historyEnabled: boolean; items: ConversationSummary[]; nextCursor: string | null }>(
    `history#${attempt}`,
    async () => {
      const result = await fetchConversations()
      return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
    },
  )
  const [more, setMore] = useState<HistoryState | null>(null)
  const base = value.status === 'success' ? value.data : null
  const extra = more && more.base === base ? more : null

  const loadMore = useCallback(async () => {
    if (!base) return
    const cursor = extra ? extra.nextCursor : base.nextCursor
    if (!cursor) return
    const items = extra?.items ?? []
    setMore({ base, items, nextCursor: cursor, busy: true, error: false })
    const result = await fetchConversations(cursor)
    setMore(
      result.ok
        ? { base, items: [...items, ...result.data.items], nextCursor: result.data.nextCursor, busy: false, error: false }
        : { base, items, nextCursor: cursor, busy: false, error: true },
    )
  }, [base, extra])

  /** Tira uma conversa (ou todas, sem id) da lista na hora, depois de apagada no backend. */
  const remove = useCallback(
    (id?: string) => {
      if (!base) return
      const keep = (list: ConversationSummary[]) => (id ? list.filter((item) => item.id !== id) : [])
      replace({ ...base, items: keep(base.items), nextCursor: id ? base.nextCursor : null })
      setMore(null)
    },
    [base, replace],
  )

  return {
    status: value.status,
    failure: value.status === 'error' ? value.failure : null,
    historyEnabled: base?.historyEnabled ?? false,
    items: base ? [...base.items, ...(extra?.items ?? [])] : [],
    hasMore: base ? Boolean(extra ? extra.nextCursor : base.nextCursor) : false,
    loadingMore: extra?.busy ?? false,
    moreError: extra?.error ?? false,
    loadMore,
    remove,
    retry: () => setAttempt((n) => n + 1),
  }
}

/**
 * Entrega da conversa que acabou de ser criada para a tela `/copilot/{id}` (ADR-0041 §1): o `router.replace` remonta a
 * tela, e sem isto ela piscaria em "carregando" para reler o que já está na memória. Consumida uma única vez.
 */
const handoff = new Map<string, Conversation>()
export const stashConversation = (conversation: Conversation) => void handoff.set(conversation.id, conversation)
/** Limpa o que ficou pendurado (ex.: o `replace` falhou); chamado ao montar `/copilot` sem id. */
export const clearStashedConversations = () => handoff.clear()
const takeConversation = (id: string) => {
  const conversation = handoff.get(id)
  handoff.delete(id)
  return conversation
}

/** `fresh`: acabou de ser criada nesta sessão (sem aviso de "resposta de {data}"). */
export function useConversation(id: string) {
  const [attempt, setAttempt] = useState(0)
  const { value } = useKeyed<{ conversation: Conversation; fresh: boolean }>(`conversation:${id}#${attempt}`, async () => {
    const stashed = takeConversation(id)
    if (stashed) return { status: 'success', data: { conversation: stashed, fresh: true } }
    const result = await fetchConversation(id)
    return result.ok
      ? { status: 'success', data: { conversation: result.data, fresh: false } }
      : { status: 'error', failure: result }
  })
  return { ...value, retry: () => setAttempt((n) => n + 1) }
}

// --- conversa em andamento ---

export interface Turn {
  id: string
  question: string
  /** Corpo enviado; o retry do mesmo envio o repete com a mesma chave de idempotência. */
  request: AskRequest
  key: string
  status: 'pending' | 'answered' | 'failed'
  answer?: CopilotAnswer
  failure?: Failure
  /** Aberta do histórico (a data aparece no aviso); falso para o que acabou de ser respondido. */
  historic: boolean
}

const newKey = () => crypto.randomUUID()

export function turnsFromConversation(conversation: Conversation, historic: boolean): Turn[] {
  return conversation.messages.map((message) => ({
    id: message.id,
    question: message.question,
    request: { question: message.question },
    key: '',
    status: 'answered',
    answer: message.answer,
    historic,
  }))
}

/**
 * Perguntas e respostas da tela. Conversa efêmera: as até 3 perguntas anteriores vão em `previousQuestions`. Guardada
 * (`conversationId`): só o id. O contexto de entrada vai apenas na primeira pergunta (ADR-0040 §3, ADR-0041 §1).
 */
export function useChat({
  initialTurns,
  initialConversationId,
  entry,
  onAnswered,
  onSaved,
}: {
  initialTurns: Turn[]
  initialConversationId: string | null
  entry: Entry | null
  onAnswered?: () => void
  onSaved?: (conversationId: string, turns: Turn[]) => void
}) {
  const [turns, setTurns] = useState<Turn[]>(initialTurns)
  const [conversationId, setConversationId] = useState<string | null>(initialConversationId)
  const [activeEntry, setActiveEntry] = useState<Entry | null>(entry)
  // Espelhos para os callbacks lerem o estado mais recente sem recriar `ask`/`send` a cada render.
  const turnsRef = useRef(turns)
  const conversationRef = useRef(conversationId)
  useEffect(() => {
    turnsRef.current = turns
    conversationRef.current = conversationId
  })
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

  const patch = useCallback((id: string, change: Partial<Turn>) => {
    setTurns((current) => current.map((turn) => (turn.id === id ? { ...turn, ...change } : turn)))
  }, [])

  /** Geração da conversa: "Nova pergunta" a incrementa e descarta qualquer resposta de um envio anterior. */
  const generation = useRef(0)

  const send = useCallback(
    async (turn: Turn) => {
      const mine = generation.current
      busyRef.current = true
      setBusy(true)
      patch(turn.id, { status: 'pending', failure: undefined })
      const result = await askQuestion(turn.request, turn.key)
      // "Nova pergunta" durante o envio já liberou o campo: a resposta tardia não ressuscita a conversa nem troca a URL.
      if (!alive.current || mine !== generation.current) return
      busyRef.current = false
      setBusy(false)
      if (!turnsRef.current.some((current) => current.id === turn.id)) return
      if (!result.ok) {
        // Chave reutilizada com outro conteúdo: o próximo envio precisa de uma chave nova.
        patch(turn.id, {
          status: 'failed',
          failure: result,
          key: result.code === 'IDEMPOTENCY_KEY_REUSED' ? newKey() : turn.key,
        })
        return
      }
      const answer = result.data
      const answered: Turn = { ...turn, status: 'answered', answer, failure: undefined }
      const next = turnsRef.current.map((current) => (current.id === turn.id ? answered : current))
      setTurns(next)
      onAnswered?.()
      if (answer.conversationId && !conversationRef.current) {
        setConversationId(answer.conversationId)
        onSaved?.(answer.conversationId, next)
      }
    },
    [onAnswered, onSaved, patch],
  )

  /** Valida (1 a 500) e envia. Devolve `false` quando a pergunta não vale ou já há um envio em andamento. */
  const ask = useCallback(
    (raw: string): boolean => {
      const question = raw.trim()
      if (!question || question.length > COPILOT_QUESTION_MAX_LENGTH || busyRef.current) return false
      const answered = turnsRef.current.filter((turn) => turn.status === 'answered')
      const request: AskRequest = { question }
      if (conversationRef.current) request.conversationId = conversationRef.current
      else if (answered.length > 0) {
        request.previousQuestions = answered.slice(-COPILOT_MAX_PREVIOUS_QUESTIONS).map((turn) => turn.question)
      }
      if (activeEntry && !conversationRef.current && answered.length === 0) request.context = activeEntry.context
      const turn: Turn = { id: newKey(), question, request, key: newKey(), status: 'pending', historic: false }
      setTurns((current) => [...current.filter((t) => t.status !== 'failed'), turn])
      void send(turn)
      return true
    },
    [activeEntry, send],
  )

  const retry = useCallback(
    (id: string) => {
      const turn = turnsRef.current.find((t) => t.id === id)
      if (turn && turn.status === 'failed' && !busyRef.current) void send(turn)
    },
    [send],
  )

  /** "Nova pergunta": volta ao início, sem o contexto de entrada. */
  const reset = useCallback(() => {
    generation.current += 1
    busyRef.current = false
    setBusy(false)
    setTurns([])
    setConversationId(null)
    setActiveEntry(null)
  }, [])

  return { turns, conversationId, entry: activeEntry, busy, ask, retry, reset }
}
