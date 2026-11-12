'use client'

import { useRouter } from 'next/navigation'
import { useCallback, useEffect, useRef, useState } from 'react'
import { AiResponse } from './AiResponse'
import { deleteAllConversations, deleteConversation, type Conversation } from './api'
import type { Entry } from './entry'
import { clearStashedConversations, stashConversation, useChat, useHistory, useUsage, type Turn } from './hooks'
import {
  CONFIRM_DELETE_ONE,
  ENTRY_SUGGESTIONS,
  FIRST_USE_SUGGESTIONS,
  FIXED_WARNING,
  INITIAL_SUGGESTIONS,
  LOADING,
  READY,
  continuationFor,
  dataScopeLabel,
} from './messages'
import { Composer, ConfirmDelete, FailurePanel, LoadingPanel, NewQuestionButton, Sidebar, SuggestionList } from './parts'
import styles from './Copilot.module.css'

export interface SavedConversation {
  id: string
  title: string
  turns: Turn[]
}

const TITLE_MAX = 80

/** A conversa recém-criada, no formato da API, para a tela `/copilot/{id}` abrir sem recarregar (hooks.stash). */
function toConversation(id: string, turns: Turn[]): Conversation {
  const answered = turns.filter((turn) => turn.status === 'answered' && turn.answer)
  const first = answered[0]?.question ?? ''
  return {
    id,
    title: first.length > TITLE_MAX ? first.slice(0, TITLE_MAX).trimEnd() : first,
    createdAt: answered[0]?.answer?.createdAt ?? new Date().toISOString(),
    messages: answered.map((turn) => ({
      id: turn.answer!.messageId ?? turn.id,
      requestId: turn.answer!.requestId,
      question: turn.question,
      createdAt: turn.answer!.createdAt,
      answer: turn.answer!,
    })),
  }
}

/**
 * `/copilot` e `/copilot/{id}` (D-Copilot, D-CopilotChat, M-Copilot, M-CopilotChat; ADR-0041 §1). Conversa efêmera:
 * o chat aparece sem mudar a URL. Com histórico: após a primeira resposta, `router.replace('/copilot/{id}')`.
 */
export function CopilotWorkspace({ conversation, entry }: { conversation?: SavedConversation; entry: Entry | null }) {
  const router = useRouter()
  const usage = useUsage()
  const history = useHistory()
  const inputRef = useRef<HTMLInputElement>(null)
  const answerRef = useRef<HTMLElement>(null)
  const [draft, setDraft] = useState('')
  const draftRef = useRef('')
  useEffect(() => {
    draftRef.current = draft
  })
  // `/copilot` sem id nunca consome conversa entregue: se o `replace` anterior falhou, nada fica pendurado.
  const isSaved = Boolean(conversation)
  useEffect(() => {
    if (!isSaved) clearStashedConversations()
  }, [isSaved])

  const onSaved = useCallback(
    (id: string, turns: Turn[]) => {
      stashConversation(toConversation(id, turns))
      router.replace(`/copilot/${id}`)
    },
    [router],
  )
  const { refresh: refreshUsage } = usage
  const chat = useChat({
    initialTurns: conversation?.turns ?? [],
    initialConversationId: conversation?.id ?? null,
    entry,
    onAnswered: refreshUsage,
    onSaved,
  })

  const last = chat.turns.at(-1)
  const chatting = chat.turns.length > 0
  // Foco vai para a resposta quando ela chega, a menos que a pessoa já esteja digitando a próxima pergunta.
  const focusId = last?.status === 'answered' && !last.historic ? last.id : null
  useEffect(() => {
    if (focusId && draftRef.current === '') answerRef.current?.focus()
  }, [focusId])

  function submit(question: string) {
    if (chat.ask(question)) setDraft('')
  }

  function newQuestion() {
    chat.reset()
    setDraft('')
    inputRef.current?.focus()
  }

  async function deleteOne() {
    if (!conversation) return false
    const result = await deleteConversation(conversation.id)
    if (result.ok) router.replace('/copilot')
    return result.ok
  }

  async function deleteAll() {
    const result = await deleteAllConversations()
    if (!result.ok) return false
    history.remove()
    if (conversation) router.replace('/copilot')
    return true
  }

  const firstUse =
    !entry &&
    usage.status === 'success' &&
    usage.data.month.used === 0 &&
    history.status === 'success' &&
    history.items.length === 0
  const scope = usage.status === 'success' ? usage.data.dataScope : null
  const suggestions = chat.entry
    ? ENTRY_SUGGESTIONS[chat.entry.kind]
    : firstUse
      ? FIRST_USE_SUGGESTIONS
      : INITIAL_SUGGESTIONS
  const title = conversation?.title || chat.turns[0]?.question || ''

  return (
    <div className={`${styles.page} ${chatting ? styles.pageChat : styles.pageInitial}`}>
      <div className={styles.main}>
        <header className={styles.header}>
          <p className={styles.brand}>
            <span aria-hidden="true">✦</span> Financial Copilot
          </p>
          {chatting ? (
            <>
              <h1 className={styles.title}>{title}</h1>
              {scope ? <p className={styles.subtitle}>Dados de {dataScopeLabel(scope)}</p> : null}
            </>
          ) : firstUse ? (
            <>
              <h1 className={styles.title}>Pergunte sobre seu dinheiro em linguagem comum</h1>
              <p className={styles.subtitle}>Eu uso só os dados que você importou e mostro de onde vem cada número.</p>
            </>
          ) : (
            <>
              <h1 className={styles.title}>O que você quer entender sobre seu dinheiro?</h1>
              <p className={styles.subtitle}>
                Respostas com base nos seus dados importados{scope ? ` (${dataScopeLabel(scope)})` : ''}. O Copilot
                explica e sugere — as decisões são sempre suas.
              </p>
            </>
          )}
          {conversation ? (
            <ConfirmDelete label="Apagar conversa" question={CONFIRM_DELETE_ONE} onConfirm={deleteOne} placement="compact" />
          ) : null}
        </header>

        {/* Região viva dedicada (ADR-0041 §2): anuncia o carregamento e a chegada; o foco vai para o <article>. */}
        <div className="sr-only" aria-live="polite" aria-atomic="true">
          {last?.status === 'pending' ? LOADING : focusId ? READY : ''}
        </div>

        {chatting ? (
          <div className={styles.thread}>
            {chat.turns.map((turn) => (
              <div key={turn.id} className={styles.turn}>
                <p className={styles.bubble}>
                  <span className="sr-only">Você perguntou: </span>
                  {turn.question}
                </p>
                {turn.status === 'pending' ? <LoadingPanel /> : null}
                {turn.status === 'answered' && turn.answer ? (
                  <AiResponse ref={turn === last ? answerRef : undefined} response={turn.answer} historic={turn.historic} />
                ) : null}
                {turn.status === 'failed' && turn.failure ? (
                  <FailurePanel
                    failure={turn.failure}
                    entry={chat.entry}
                    usage={usage.status === 'success' ? usage.data : undefined}
                    onRetry={() => chat.retry(turn.id)}
                  />
                ) : null}
              </div>
            ))}
          </div>
        ) : null}

        {chatting && last?.status === 'answered' ? (
          <SuggestionList title="Continuar" items={continuationFor(last.answer?.intent)} disabled={chat.busy} onPick={submit} />
        ) : null}

        <Composer ref={inputRef} value={draft} busy={chat.busy} onChange={setDraft} onSubmit={() => submit(draft)} sticky={chatting} />

        {!chatting ? (
          <>
            <SuggestionList title="Sugestões para você" items={suggestions} disabled={chat.busy} onPick={submit} />
            <div className={styles.scopeGrid}>
              <section aria-labelledby="copilot-does" className={styles.scopeCard}>
                <h2 id="copilot-does" className={styles.label}>O que o Copilot faz</h2>
                <ul className={styles.scopeList}>
                  <li>Explica mudanças nos seus números</li>
                  <li>Responde sobre gastos, cartões e objetivos</li>
                  <li>Mostra os dados usados em cada resposta</li>
                </ul>
              </section>
              <section aria-labelledby="copilot-doesnt" className={styles.scopeCard}>
                <h2 id="copilot-doesnt" className={styles.label}>O que ele não faz</h2>
                <ul className={styles.scopeList}>
                  <li>Movimentar dinheiro ou pagar contas</li>
                  <li>Usar dados fora do Smart Health Finance</li>
                  <li>Decidir por você</li>
                </ul>
              </section>
            </div>
          </>
        ) : null}
        <p className={styles.warning}>{FIXED_WARNING}</p>
      </div>

      <Sidebar
        history={history}
        usage={usage}
        activeId={chat.conversationId}
        initial={!chatting}
        newQuestion={conversation ? <NewQuestionButton href="/copilot" /> : <NewQuestionButton onClick={newQuestion} />}
        onDeleteAll={deleteAll}
      />
    </div>
  )
}
