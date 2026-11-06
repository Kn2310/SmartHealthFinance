'use client'

import { CircleAlert, X } from 'lucide-react'
import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { Button } from '@/components/ui/Button'
import type { Account } from '@/features/accounts/api'
import { newIdempotencyKey } from '@/features/transactions/api'
import { businessToday } from '@/lib/format/business-date'
import { formatMoney } from '@/lib/format/money'
import { parseAmountInput } from '@/lib/format/amount-input'
import { addContribution, type Goal } from './api'
import { CONTRIBUTION_FIELD_ORDER, fieldErrors, goalFailureMessage, goalFieldMessage, type ContributionField } from './messages'
import drawer from '@/features/forecast/Recurring.module.css'
import accountStyles from '@/features/accounts/Accounts.module.css'

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'

type Errors = Partial<Record<ContributionField | 'form', string>>

/**
 * "Registrar aporte" (ADR-0035 §2; ADR-0037): drawer à direita (bottom sheet no mobile), diálogo modal nativo com foco
 * preso, Esc e fundo fecham e o foco volta ao gatilho (padrão do drawer de recorrências). O aporte é uma TRANSFER para
 * o destino do objetivo, criada pelo backend; uma `Idempotency-Key` por intenção evita aporte em dobro.
 */
export function ContributionDrawer({ goal, accounts, onClose, onDone, trigger }: {
  goal: Goal
  accounts: Account[]
  onClose: () => void
  onDone: (message: string) => void
  trigger: HTMLElement | null
}) {
  const id = useId()
  const dialog = useRef<HTMLDialogElement>(null)
  const reserve = goal.type === 'EMERGENCY_RESERVE'
  const active = accounts.filter((a) => a.status === 'ACTIVE')
  const reserveAccounts = active.filter((a) => a.type === 'SAVINGS')
  const sources = active.filter((a) => (reserve ? a.type !== 'SAVINGS' : a.id !== goal.destination.accountId))
  const [source, setSource] = useState(
    goal.sourceAccountId && sources.some((a) => a.id === goal.sourceAccountId) ? goal.sourceAccountId : '',
  )
  const [destination, setDestination] = useState(reserveAccounts.length === 1 ? reserveAccounts[0]!.id : '')
  const [amount, setAmount] = useState('')
  const [date, setDate] = useState(() => businessToday())
  const [errors, setErrors] = useState<Errors>({})
  const [busy, setBusy] = useState(false)
  const key = useRef(newIdempotencyKey())
  /** Corpo enviado com a chave atual: reenviar o mesmo corpo reusa a chave; um corpo diferente é outra intenção. */
  const sent = useRef<string | null>(null)
  // Enquanto envia, Esc, fundo e Fechar não fecham: desmontar perderia a chave e um reenvio poderia duplicar o aporte.
  const close = () => {
    if (!busy) onClose()
  }

  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (!element.open) {
      if (typeof element.showModal === 'function') element.showModal()
      else element.setAttribute('open', '')
    }
    element.querySelector<HTMLElement>('[data-autofocus]')?.focus()
    return () => {
      if (element.open) {
        if (typeof element.close === 'function') element.close()
        else element.removeAttribute('open')
      }
      trigger?.focus()
    }
  }, [trigger])

  const onKeyDown = (event: KeyboardEvent<HTMLDialogElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      close()
      return
    }
    if (event.key !== 'Tab') return
    const items = [...(dialog.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [])]
    if (items.length === 0) return
    const first = items[0]!
    const last = items.at(-1)!
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  const focusFirst = (next: Errors) => {
    const field = CONTRIBUTION_FIELD_ORDER.find((f) => next[f])
    if (field) document.getElementById(`${id}-${field}`)?.focus()
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    const next: Errors = {}
    if (!source) next.sourceAccountId = goalFieldMessage('sourceAccountId', 'REQUIRED')
    if (reserve && !destination) next.destinationAccountId = goalFieldMessage('destinationAccountId', 'NOT_RESERVE_ACCOUNT')
    const parsed = parseAmountInput(amount)
    if (!parsed.ok) next.amount = goalFieldMessage('amount', parsed.error)
    if (!date) next.occurredOn = goalFieldMessage('occurredOn', 'REQUIRED')
    if (Object.keys(next).length > 0 || !parsed.ok) {
      setErrors(next)
      focusFirst(next)
      return
    }
    setBusy(true)
    setErrors({})
    const body = {
      sourceAccountId: source,
      destinationAccountId: reserve ? destination : null,
      amount: { amount: parsed.value, currency: 'BRL' },
      occurredOn: date,
    }
    const signature = JSON.stringify(body)
    if (sent.current !== null && sent.current !== signature) key.current = newIdempotencyKey()
    sent.current = signature
    const result = await addContribution(goal.id, body, key.current)
    setBusy(false)
    if (result.ok) {
      onDone(`Aporte de ${formatMoney(body.amount)} registrado. O progresso já considera este valor.`)
      return
    }
    // Falha de rede: "tentar de novo" reenvia a mesma intenção (mesma chave, sem duplicar). Recusa do pedido: nova chave.
    if (result.code !== 'UNAVAILABLE') {
      key.current = newIdempotencyKey()
      sent.current = null
    }
    const byField = fieldErrors(result, CONTRIBUTION_FIELD_ORDER)
    if (result.code === 'ACCOUNT_NOT_FOUND' || result.code === 'ACCOUNT_ARCHIVED') {
      byField.sourceAccountId ??= goalFieldMessage('sourceAccountId', result.code)
    }
    if (Object.keys(byField).length > 0) {
      setErrors(byField)
      focusFirst(byField)
    } else {
      setErrors({ form: goalFailureMessage(result.code) })
    }
  }

  const error = (field: ContributionField) =>
    errors[field] ? (
      <p id={`${id}-${field}-error`} className={accountStyles.fieldError}>
        <CircleAlert size={16} strokeWidth={1.75} aria-hidden="true" />
        <span>{errors[field]}</span>
      </p>
    ) : null
  const described = (field: ContributionField, hint = false) =>
    [hint ? `${id}-${field}-hint` : null, errors[field] ? `${id}-${field}-error` : null].filter(Boolean).join(' ') || undefined

  return (
    <dialog
      ref={dialog}
      className={drawer.drawer}
      aria-labelledby={`${id}-title`}
      aria-modal="true"
      onKeyDown={onKeyDown}
      onCancel={(event) => {
        event.preventDefault()
        close()
      }}
      onClick={(event) => {
        if (event.target === dialog.current) close()
      }}
    >
      <div className={drawer.drawerPanel}>
        <div className={drawer.drawerHeader}>
          <h2 id={`${id}-title`} className={drawer.drawerTitle}>Registrar aporte</h2>
          <button type="button" className={drawer.iconButton} onClick={close} disabled={busy} aria-label="Fechar">
            <X size={20} aria-hidden="true" />
          </button>
        </div>
        <form className={drawer.drawerBody} onSubmit={submit} noValidate aria-busy={busy}>
          <p className={drawer.muted}>
            {reserve
              ? 'O aporte é uma transferência de uma conta sua para uma conta de reserva (poupança).'
              : 'O aporte é uma transferência de uma conta sua para a conta do objetivo.'}{' '}
            Ele aparece nas transações e no saldo das duas contas.
          </p>
          <div className={accountStyles.field}>
            <label htmlFor={`${id}-sourceAccountId`} className={accountStyles.label}>Conta de origem</label>
            <select id={`${id}-sourceAccountId`} className={accountStyles.select} value={source} data-autofocus
              onChange={(e) => setSource(e.target.value)}
              aria-invalid={errors.sourceAccountId ? true : undefined} aria-describedby={described('sourceAccountId')}>
              <option value="">Escolha a conta</option>
              {sources.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
            {error('sourceAccountId')}
          </div>
          {reserve ? (
            <div className={accountStyles.field}>
              <label htmlFor={`${id}-destinationAccountId`} className={accountStyles.label}>Conta da reserva</label>
              <select id={`${id}-destinationAccountId`} className={accountStyles.select} value={destination}
                onChange={(e) => setDestination(e.target.value)}
                aria-invalid={errors.destinationAccountId ? true : undefined} aria-describedby={described('destinationAccountId')}>
                <option value="">Escolha a conta de reserva</option>
                {reserveAccounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
              </select>
              {error('destinationAccountId')}
            </div>
          ) : null}
          <div className={accountStyles.field}>
            <label htmlFor={`${id}-amount`} className={accountStyles.label}>Valor</label>
            <input id={`${id}-amount`} className={accountStyles.input} inputMode="decimal" autoComplete="off" placeholder="0,00"
              value={amount} onChange={(e) => setAmount(e.target.value)}
              aria-invalid={errors.amount ? true : undefined} aria-describedby={described('amount', true)} />
            <p id={`${id}-amount-hint`} className={accountStyles.fieldHint}>Em reais, por exemplo 700,00.</p>
            {error('amount')}
          </div>
          <div className={accountStyles.field}>
            <label htmlFor={`${id}-occurredOn`} className={accountStyles.label}>Data</label>
            <input id={`${id}-occurredOn`} className={accountStyles.input} type="date" value={date} max={businessToday()}
              onChange={(e) => setDate(e.target.value)}
              aria-invalid={errors.occurredOn ? true : undefined} aria-describedby={described('occurredOn')} />
            {error('occurredOn')}
          </div>
          {errors.form ? (
            <p role="alert" className={accountStyles.error}>
              <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
              <span>{errors.form}</span>
            </p>
          ) : null}
          <div className={drawer.drawerActions}>
            <Button type="submit" disabled={busy}>{busy ? 'Registrando…' : 'Registrar aporte'}</Button>
            <Button variant="secondary" onClick={onClose} disabled={busy}>Cancelar</Button>
          </div>
        </form>
      </div>
    </dialog>
  )
}
