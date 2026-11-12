'use client'

import { Check, Copy, ThumbsDown, ThumbsUp } from 'lucide-react'
import { forwardRef, useEffect, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { COPILOT_FEEDBACK_REASONS } from '@/lib/api/contract'
import type { CopilotFeedbackReason, CopilotNotice } from '@/lib/api/schema'
import { sendFeedback, type AnswerBody, type AnswerValue, type CopilotAnswer, type Segment } from './api'
import { actionHref } from './links'
import { CONFIDENCE_TEXT, FEEDBACK, NOTICE_TEXT, historicNotice } from './messages'
import styles from './Copilot.module.css'

/**
 * Resposta do Copilot (02-components "AI Response"; ADR-0041 §2). Renderiza SÓ a partir dos segmentos tipados: TEXT
 * vira texto (o React escapa tudo), VALUE vira o `formatted` do valor referenciado por `ref`, em negrito. Nunca
 * `dangerouslySetInnerHTML`, nunca markdown. Números, quadro, confiança e avisos vêm prontos do backend.
 */

const KIND_LABEL: Record<AnswerValue['kind'], string | null> = { FACT: null, ESTIMATED: 'Estimado', SIMULATED: 'Simulado' }

function ValueText({ value }: { value: AnswerValue }) {
  const label = KIND_LABEL[value.kind]
  return (
    <>
      <strong className={`${styles.value} ${value.kind === 'ESTIMATED' ? styles.estimated : ''} ${value.kind === 'SIMULATED' ? styles.simulated : ''}`}>
        {value.formatted}
      </strong>
      {label ? <span className={styles.kindTag}>{label}</span> : null}
    </>
  )
}

function Segments({ segments, values }: { segments: Segment[]; values: Map<string, AnswerValue> }) {
  return (
    <>
      {segments.map((segment, index) => {
        if (segment.kind === 'TEXT') return <span key={index}>{segment.text ?? ''}</span>
        const value = segment.ref ? values.get(segment.ref) : undefined
        return value ? <ValueText key={index} value={value} /> : null
      })}
    </>
  )
}

/** Texto plano para "Copiar": os segmentos com o valor formatado e o rótulo Estimado/Simulado. */
export function plainText(answer: AnswerBody): string {
  const values = new Map(answer.values.map((value) => [value.ref, value]))
  return answer.segments
    .map((segment) => {
      if (segment.kind === 'TEXT') return segment.text ?? ''
      const value = segment.ref ? values.get(segment.ref) : undefined
      if (!value) return ''
      const label = KIND_LABEL[value.kind]
      return label ? `${value.formatted} (${label.toLowerCase()})` : value.formatted
    })
    .join('')
}

function BreakdownTable({ answer, values }: { answer: AnswerBody; values: Map<string, AnswerValue> }) {
  const { breakdown } = answer
  if (!breakdown || breakdown.rows.length === 0) return null
  const total = breakdown.totalRef ? values.get(breakdown.totalRef) : undefined
  return (
    <table className={styles.breakdown}>
      <caption className={styles.breakdownTitle}>{breakdown.title}</caption>
      <thead className="sr-only">
        <tr>
          <th scope="col">Fator</th>
          <th scope="col">Valor</th>
        </tr>
      </thead>
      <tbody>
        {breakdown.rows.map((row, index) => {
          const value = values.get(row.valueRef)
          return (
            <tr key={index}>
              <th scope="row" className={styles.rowLabel}>
                {row.label}
                {row.detail ? <span className={styles.rowDetail}>{row.detail}</span> : null}
              </th>
              <td className={styles.rowValue}>{value ? <ValueText value={value} /> : '—'}</td>
            </tr>
          )
        })}
      </tbody>
      {total ? (
        <tfoot>
          <tr>
            <th scope="row" className={styles.rowLabel}>Total</th>
            <td className={styles.rowValue}><ValueText value={total} /></td>
          </tr>
        </tfoot>
      ) : null}
    </table>
  )
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)
  useEffect(() => {
    if (!copied) return
    const timer = setTimeout(() => setCopied(false), 2500)
    return () => clearTimeout(timer)
  }, [copied])

  async function copy() {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
    } catch {
      // Sem permissão de área de transferência: o botão continua disponível, sem mensagem de erro própria.
    }
  }

  return (
    <>
      <button type="button" className={styles.toolButton} onClick={copy}>
        {copied ? <Check size={16} strokeWidth={1.75} aria-hidden="true" /> : <Copy size={16} strokeWidth={1.75} aria-hidden="true" />}
        Copiar
      </button>
      <span role="status" className={styles.copied}>{copied ? 'Copiado' : ''}</span>
    </>
  )
}

/** Feedback da resposta (ADR-0041 §4): dois botões de ícone; "não útil" abre os 4 motivos, opcionais. */
function Feedback({ requestId }: { requestId: string }) {
  const [rating, setRating] = useState<'HELPFUL' | 'NOT_HELPFUL' | null>(null)
  const [reason, setReason] = useState<CopilotFeedbackReason | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(false)

  async function send(nextRating: 'HELPFUL' | 'NOT_HELPFUL', nextReason: CopilotFeedbackReason | null) {
    setBusy(true)
    setError(false)
    const result = await sendFeedback(requestId, nextReason ? { rating: nextRating, reason: nextReason } : { rating: nextRating })
    setBusy(false)
    if (result.ok) {
      setRating(nextRating)
      setReason(nextReason)
    } else setError(true)
  }

  return (
    <>
      <button type="button" className={styles.toolIcon} aria-pressed={rating === 'HELPFUL'} aria-label={FEEDBACK.helpful}
        title={FEEDBACK.helpful} disabled={busy} onClick={() => send('HELPFUL', null)}>
        <ThumbsUp size={16} strokeWidth={1.75} aria-hidden="true" />
      </button>
      <button type="button" className={styles.toolIcon} aria-pressed={rating === 'NOT_HELPFUL'} aria-label={FEEDBACK.notHelpful}
        title={FEEDBACK.notHelpful} disabled={busy} onClick={() => send('NOT_HELPFUL', null)}>
        <ThumbsDown size={16} strokeWidth={1.75} aria-hidden="true" />
      </button>
      {rating ? <span role="status" className={styles.thanks}>{FEEDBACK.thanks}</span> : <span role="status" className="sr-only" />}
      {rating === 'NOT_HELPFUL' ? (
        <div role="group" aria-label={FEEDBACK.reasonGroup} className={styles.reasons}>
          {COPILOT_FEEDBACK_REASONS.map((code) => (
            <button key={code} type="button" className={styles.chip} aria-pressed={reason === code} disabled={busy}
              onClick={() => send('NOT_HELPFUL', code)}>
              {FEEDBACK.reasons[code]}
            </button>
          ))}
        </div>
      ) : null}
      {error ? <p role="alert" className={styles.feedbackError}>{FEEDBACK.error}</p> : null}
    </>
  )
}

function Confidence({ level }: { level: NonNullable<CopilotAnswer['confidence']> }) {
  const bars = level === 'high' ? 3 : level === 'medium' ? 2 : 1
  return (
    <p className={styles.confidence}>
      <span className={styles.meter} aria-hidden="true">
        {[1, 2, 3].map((n) => (
          <span key={n} className={`${styles.segment} ${n <= bars ? styles.segmentOn : ''}`} />
        ))}
      </span>
      {CONFIDENCE_TEXT[level]}
    </p>
  )
}

export const AiResponse = forwardRef<HTMLElement, { response: CopilotAnswer; historic: boolean }>(function AiResponse(
  { response, historic },
  ref,
) {
  const { answer } = response
  const values = new Map(answer.values.map((value) => [value.ref, value]))
  const notices: CopilotNotice[] = [...new Set([...response.notices, ...(response.code === 'SAFE_MODE' ? (['SAFE_MODE'] as const) : [])])]
  const canRate = response.code === 'OK' || response.code === 'SAFE_MODE'
  const hrefs = answer.actions.map((action) => ({ action, href: actionHref(action) })).filter((a) => a.href !== null)

  return (
    <article ref={ref} tabIndex={-1} className={styles.answer} aria-label="Resposta do Copilot">
      <p className={styles.answerLabel}>
        <span aria-hidden="true">✦</span> Copilot
        <span className={styles.answerMeta}> · Resposta baseada nos seus dados</span>
      </p>
      {historic ? <p className={styles.notice}>{historicNotice(response.createdAt)}</p> : null}
      <p className={styles.answerText}>
        <Segments segments={answer.segments} values={values} />
      </p>
      <BreakdownTable answer={answer} values={values} />
      {answer.explanation.length > 0 ? (
        <details className={styles.how}>
          <summary>Como cheguei a isso</summary>
          <div className={styles.howBody}>
            {answer.explanation.map((step, index) => (
              <p key={index}><Segments segments={step} values={values} /></p>
            ))}
          </div>
        </details>
      ) : null}
      {notices.length > 0 ? (
        <ul className={styles.notices} aria-label="Avisos">
          {notices.map((notice) => (
            <li key={notice} className={styles.notice}>{NOTICE_TEXT[notice]}</li>
          ))}
        </ul>
      ) : null}
      {response.confidence ? <Confidence level={response.confidence} /> : null}
      {answer.dataUsed.length > 0 ? (
        <div>
          <p className={styles.label}>Dados usados</p>
          <ul className={styles.badges} aria-label="Dados usados">
            {answer.dataUsed.map((item, index) => (
              <li key={index} className={`${styles.badge} ${item.partial ? styles.badgePartial : ''}`}>
                {item.label}{item.partial ? ' · parcial' : ''}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      {hrefs.length > 0 ? (
        <div className={styles.actions}>
          {hrefs.map(({ action, href }, index) => (
            <Button key={`${action.kind}-${index}`} href={href!} variant={index === 0 ? 'primary' : 'secondary'}>
              {action.label}
            </Button>
          ))}
        </div>
      ) : null}
      <div className={styles.tools}>
        <CopyButton text={plainText(answer)} />
        {canRate ? <Feedback requestId={response.requestId} /> : null}
      </div>
    </article>
  )
})
