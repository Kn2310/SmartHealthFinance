'use client'

import { ArrowUp, Plus } from 'lucide-react'
import Link from 'next/link'
import { forwardRef, useEffect, useId, useState, type ReactNode } from 'react'
import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import { COPILOT_QUESTION_MAX_LENGTH } from '@/lib/api/contract'
import { dayLabel } from '@/features/insights/format'
import type { ConversationSummary, Usage } from './api'
import { entryHref, type Entry } from './entry'
import {
  ALREADY_ANSWERED,
  CONFIRM_DELETE_ALL,
  CONSENT_REQUIRED,
  CONVERSATION_NOT_FOUND,
  DELETE_ERROR,
  HISTORY_ERROR,
  HISTORY_OFF,
  LOADING,
  LOADING_SLOW,
  errorMessage,
  quotaMessage,
  rateLimitedMessage,
} from './messages'
import type { Failure } from '@/lib/bff-client'
import styles from './Copilot.module.css'

/** Campo de pergunta (✦ só aqui, nas sugestões e no rótulo do Copilot; ADR-0041 §2). */
export const Composer = forwardRef<
  HTMLInputElement,
  { value: string; busy: boolean; onChange: (value: string) => void; onSubmit: () => void; sticky: boolean }
>(function Composer({ value, busy, onChange, onSubmit, sticky }, ref) {
  return (
    <form
      className={`${styles.composer} ${sticky ? styles.composerChat : ''}`}
      onSubmit={(event) => {
        event.preventDefault()
        onSubmit()
      }}
    >
      <label htmlFor="copilot-question" className="sr-only">Pergunta para o Copilot</label>
      <span className={styles.composerStar} aria-hidden="true">✦</span>
      <input
        ref={ref}
        id="copilot-question"
        className={styles.composerInput}
        type="text"
        autoComplete="off"
        placeholder="Pergunta para o Copilot"
        maxLength={COPILOT_QUESTION_MAX_LENGTH}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
      <button type="submit" className={styles.send} aria-label="Enviar pergunta" disabled={busy || value.trim() === ''}>
        <ArrowUp size={20} strokeWidth={1.75} aria-hidden="true" />
      </button>
    </form>
  )
})

export function SuggestionList({
  title,
  items,
  disabled,
  onPick,
}: {
  title: string
  items: readonly string[]
  disabled: boolean
  onPick: (question: string) => void
}) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId}>
      <h2 id={headingId} className={styles.label}>{title}</h2>
      <ul className={styles.suggestions}>
        {items.map((question) => (
          <li key={question}>
            <button type="button" className={styles.suggestion} disabled={disabled} onClick={() => onPick(question)}>
              <span className={styles.suggestionStar} aria-hidden="true">✦</span>
              {question}
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** Pergunta visível + skeleton + "Analisando seus dados…"; após 10 s, a mensagem extra (05-states, Loading). */
export function LoadingPanel() {
  const [slow, setSlow] = useState(false)
  useEffect(() => {
    const timer = setTimeout(() => setSlow(true), 10_000)
    return () => clearTimeout(timer)
  }, [])
  return (
    <div className={styles.loading} aria-busy="true">
      <p className={styles.answerLabel}>
        <span aria-hidden="true">✦</span> Copilot
      </p>
      {/* O anúncio fica na região viva da tela; aqui é só visual, para não ser lido duas vezes. */}
      <p aria-hidden="true">{LOADING}</p>
      {slow ? <p className={styles.muted}>{LOADING_SLOW}</p> : null}
      <div aria-hidden="true" className={styles.skeletons}>
        <Skeleton width="full" height="body" />
        <Skeleton width="lg" height="body" />
        <Skeleton width="md" height="body" />
      </div>
    </div>
  )
}

function quotaWindow(usage: Usage | undefined, resetsAt: string | undefined): 'month' | 'day' {
  if (!usage) return 'month'
  if (usage.month.used >= usage.month.limit) return 'month'
  if (usage.day.used >= usage.day.limit) return 'day'
  return resetsAt && usage.day.resetsAt === resetsAt ? 'day' : 'month'
}

/** "Tentar novamente" só depois do `Retry-After` (um único timer, sem animação; limpo ao desmontar). */
function RetryAfterButton({ seconds, onRetry }: { seconds: number | undefined; onRetry: () => void }) {
  const [waiting, setWaiting] = useState(true)
  useEffect(() => {
    const timer = setTimeout(() => setWaiting(false), Math.max(1, seconds ?? 1) * 1000)
    return () => clearTimeout(timer)
  }, [seconds])
  return (
    <Button variant="secondary" disabled={waiting} onClick={onRetry}>
      {errorMessage(null).retry}
    </Button>
  )
}

/** Estados de falha da pergunta (ADR-0041 §5). Só códigos estáveis viram texto; nada do servidor é mostrado. */
export function FailurePanel({
  failure,
  entry,
  usage,
  onRetry,
}: {
  failure: Failure
  entry: Entry | null
  usage: Usage | undefined
  onRetry: () => void
}) {
  const id = useId()
  switch (failure.code) {
    case 'COPILOT_CONSENT_REQUIRED':
      return (
        <section className={styles.state} role="alert" aria-labelledby={id}>
          <p id={id}>{CONSENT_REQUIRED.text}</p>
          <Button href="/profile/consents" variant="secondary">{CONSENT_REQUIRED.action}</Button>
        </section>
      )
    case 'COPILOT_QUOTA_EXCEEDED': {
      const quota = quotaMessage(quotaWindow(usage, failure.resetsAt), failure.resetsAt)
      return (
        <section className={styles.state} role="alert" aria-labelledby={id}>
          <h2 id={id} className={styles.stateTitle}>{quota.title}</h2>
          <p>{quota.text}</p>
          <Button href="/insights" variant="secondary">{quota.action}</Button>
        </section>
      )
    }
    case 'COPILOT_RATE_LIMITED':
      return (
        <section className={styles.state} role="alert" aria-labelledby={id}>
          <p id={id}>{rateLimitedMessage(failure.retryAfter)}</p>
          <RetryAfterButton seconds={failure.retryAfter} onRetry={onRetry} />
        </section>
      )
    case 'COPILOT_ALREADY_ANSWERED':
      return (
        <section className={styles.state} role="alert" aria-labelledby={id}>
          <p id={id}>{ALREADY_ANSWERED}</p>
        </section>
      )
    case 'COPILOT_CONVERSATION_NOT_FOUND':
      return (
        <section className={styles.state} role="alert" aria-labelledby={id}>
          <h2 id={id} className={styles.stateTitle}>{CONVERSATION_NOT_FOUND.title}</h2>
          <p>{CONVERSATION_NOT_FOUND.text}</p>
          <Button href="/copilot" variant="secondary">{CONVERSATION_NOT_FOUND.action}</Button>
        </section>
      )
    default: {
      const error = errorMessage(entry?.kind ?? null)
      const alternative = entryHref(entry)
      return (
        <section className={styles.state} role="alert" aria-labelledby={id}>
          <h2 id={id} className={styles.stateTitle}>{error.title}</h2>
          <p>{error.text}</p>
          <div className={styles.actions}>
            <Button onClick={onRetry}>{error.retry}</Button>
            <Button href={alternative.href} variant="secondary">{alternative.label}</Button>
          </div>
        </section>
      )
    }
  }
}

/** Confirmação inline (mesmo padrão de "Dispensar insight"): o botão abre a pergunta; Apagar executa. */
export function ConfirmDelete({
  label,
  question,
  onConfirm,
  placement = 'inline',
}: {
  label: string
  question: string
  onConfirm: () => Promise<boolean>
  placement?: 'inline' | 'compact'
}) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(false)
  const questionId = useId()

  async function confirm() {
    setBusy(true)
    setError(false)
    const ok = await onConfirm()
    setBusy(false)
    if (ok) setConfirming(false)
    else setError(true)
  }

  return (
    <div className={placement === 'compact' ? styles.confirmCompact : styles.confirm}>
      {confirming ? (
        <div role="group" aria-labelledby={questionId} className={styles.confirmGroup}>
          <p id={questionId}>{question}</p>
          <div className={styles.actions}>
            <button type="button" className={styles.dangerButton} disabled={busy} onClick={confirm}>Apagar</button>
            <button type="button" className={styles.linkButton} disabled={busy} onClick={() => setConfirming(false)}>
              Cancelar
            </button>
          </div>
        </div>
      ) : (
        <button type="button" className={styles.linkButton} onClick={() => setConfirming(true)}>{label}</button>
      )}
      {error ? <p role="alert" className={styles.muted}>{DELETE_ERROR}</p> : null}
    </div>
  )
}

export interface HistoryModel {
  status: 'loading' | 'error' | 'success'
  historyEnabled: boolean
  items: ConversationSummary[]
  hasMore: boolean
  loadingMore: boolean
  moreError: boolean
  loadMore: () => void
  retry: () => void
}

function History({
  history,
  activeId,
  onDeleteAll,
}: {
  history: HistoryModel
  activeId: string | null
  onDeleteAll: () => Promise<boolean>
}) {
  if (history.status === 'loading') {
    return (
      <div role="status" aria-busy="true">
        <span className="sr-only">Carregando…</span>
        <div aria-hidden="true" className={styles.skeletons}>
          <Skeleton width="full" height="body" />
          <Skeleton width="md" height="body" />
        </div>
      </div>
    )
  }
  if (history.status === 'error') {
    return (
      <div role="alert" className={styles.sideNote}>
        <p>{HISTORY_ERROR}</p>
        <button type="button" className={styles.linkButton} onClick={history.retry}>Tentar novamente</button>
      </div>
    )
  }
  if (!history.historyEnabled) {
    return (
      <div className={styles.sideNote}>
        <p>{HISTORY_OFF.text}</p>
        <Link href="/profile/consents" className={styles.link}>{HISTORY_OFF.action}</Link>
      </div>
    )
  }
  if (history.items.length === 0) return null
  return (
    <>
      <ul className={styles.history}>
        {history.items.map((item) => (
          <li key={item.id}>
            <Link href={`/copilot/${item.id}`} className={styles.historyItem} aria-current={item.id === activeId ? 'page' : undefined}>
              <span className={styles.historyTitle}>{item.title}</span>
              <span className={styles.historyDate}>{dayLabel(item.lastMessageAt)}</span>
            </Link>
          </li>
        ))}
      </ul>
      {history.hasMore ? (
        <button type="button" className={styles.linkButton} disabled={history.loadingMore} onClick={history.loadMore}>
          Ver mais
        </button>
      ) : null}
      {history.moreError ? <p role="alert" className={styles.muted}>{HISTORY_ERROR}</p> : null}
      <ConfirmDelete label="Apagar todas" question={CONFIRM_DELETE_ALL} onConfirm={onDeleteAll} />
    </>
  )
}

/** Coluna lateral (≥ 1024): Nova pergunta, Histórico e Uso no mês. No mobile fica abaixo do conteúdo. */
export function Sidebar({
  history,
  usage,
  activeId,
  initial,
  newQuestion,
  onDeleteAll,
}: {
  history: HistoryModel
  usage: { status: 'loading' | 'error' | 'success'; data?: Usage }
  activeId: string | null
  initial: boolean
  newQuestion: ReactNode
  onDeleteAll: () => Promise<boolean>
}) {
  return (
    <aside className={`${styles.side} ${initial ? styles.sideInitial : ''}`} aria-label="Conversas e uso">
      <div className={styles.newQuestion}>{newQuestion}</div>
      <section aria-labelledby="copilot-history" className={styles.sidePanel}>
        <h2 id="copilot-history" className={styles.label}>Histórico</h2>
        <History history={history} activeId={activeId} onDeleteAll={onDeleteAll} />
      </section>
      {usage.status === 'success' && usage.data ? (
        <section aria-labelledby="copilot-usage" className={styles.sidePanel}>
          <h2 id="copilot-usage" className={styles.label}>Uso no mês</h2>
          <p>{usage.data.month.used} de {usage.data.month.limit} perguntas</p>
        </section>
      ) : null}
    </aside>
  )
}

export function NewQuestionButton({ onClick, href }: { onClick?: () => void; href?: string }) {
  const content = (
    <>
      <Plus size={16} strokeWidth={1.75} aria-hidden="true" /> Nova pergunta
    </>
  )
  return href ? (
    <Button href={href} variant="secondary">{content}</Button>
  ) : (
    <Button variant="secondary" onClick={onClick}>{content}</Button>
  )
}
