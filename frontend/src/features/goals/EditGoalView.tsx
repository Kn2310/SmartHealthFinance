'use client'

import { useRouter } from 'next/navigation'
import { CircleAlert } from 'lucide-react'
import { useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { useAccounts } from '@/features/transactions/hooks'
import { changeGoalStatus, updateGoal, type Goal, type GoalAction } from './api'
import { amountToInput, GoalForm } from './GoalForm'
import { useGoal, useGoals } from './hooks'
import { goalFailureMessage, goalLoadMessage } from './messages'
import { ErrorPanel, LoadingPanel, PageHeader } from './parts'
import styles from './Goals.module.css'
import accountStyles from '@/features/accounts/Accounts.module.css'

/** Edição do objetivo + Pausar/Retomar/Concluir/Arquivar com confirmação (ADR-0037; ações do ADR-0035 §1). */
export function EditGoalView({ id }: { id: string | null }) {
  if (!id) {
    return (
      <div className={accountStyles.page}>
        <ErrorPanel title="Objetivo não encontrado" message={goalLoadMessage('GOAL_NOT_FOUND')} />
        <div><Button href="/goals" variant="secondary">Voltar para Objetivos</Button></div>
      </div>
    )
  }
  return <EditGoal id={id} />
}

function EditGoal({ id }: { id: string }) {
  const router = useRouter()
  const goal = useGoal(id)
  const goals = useGoals()
  const accounts = useAccounts()

  if (goal.status === 'loading' || goals.status === 'loading' || accounts.status === 'loading') {
    return <div className={accountStyles.page}><LoadingPanel label="Carregando o objetivo…" rows={5} /></div>
  }
  if (goal.status === 'error' || !goal.data) {
    const code = goal.status === 'error' ? goal.failure.code : 'UNAVAILABLE'
    return (
      <div className={accountStyles.page}>
        <ErrorPanel title="Objetivo indisponível" message={goalLoadMessage(code)}
          onRetry={code === 'GOAL_NOT_FOUND' ? undefined : goal.retry} />
      </div>
    )
  }
  const data = goal.data
  const editable = data.status === 'ACTIVE' || data.status === 'PAUSED'
  const used = new Set(
    (goals.status === 'success' ? goals.data?.goals ?? [] : [])
      .filter((g) => g.id !== data.id)
      .flatMap((g) => (g.destination.accountId ? [g.destination.accountId] : [])),
  )

  return (
    <div className={accountStyles.page}>
      <PageHeader current="Editar" title={`Editar ${data.name}`} backHref={`/goals/${data.id}`} />
      {editable ? (
        <section className={styles.section} aria-label="Dados do objetivo">
          <GoalForm
            mode="edit"
            initial={{
              type: data.type,
              name: data.name,
              targetAmount: amountToInput(data.targetAmount.amount),
              deadline: data.deadline ?? '',
              plannedMonthly: amountToInput(data.plannedMonthly.amount),
              destinationAccountId: data.destination.accountId ?? '',
              sourceAccountId: data.sourceAccountId ?? '',
            }}
            accounts={accounts.status === 'success' ? accounts.data : []}
            usedDestinations={used}
            submitLabel="Salvar alterações"
            submittingLabel="Salvando…"
            cancelHref={`/goals/${data.id}`}
            onSubmit={async (fields) => {
              const result = await updateGoal(data.id, {
                name: fields.name,
                targetAmount: { amount: fields.targetAmount, currency: 'BRL' },
                plannedMonthly: { amount: fields.plannedMonthly, currency: 'BRL' },
                deadline: fields.deadline,
                sourceAccountId: fields.sourceAccountId,
                destinationAccountId: fields.destinationAccountId,
              })
              if (!result.ok) return result
              router.push(`/goals/${data.id}`)
              return null
            }}
          />
        </section>
      ) : (
        <p className={styles.notice}>Objetivos concluídos ou arquivados não podem ser editados.</p>
      )}
      <StatusSection goal={data} onDone={(action) => router.push(action === 'archive' ? '/goals' : `/goals/${data.id}`)} />
    </div>
  )
}

const CONFIRM: Record<GoalAction, { button: string; title: string; text: (goal: Goal) => React.ReactNode }> = {
  pause: {
    button: 'Pausar objetivo',
    title: 'Pausar este objetivo?',
    text: () => 'A previsão fica suspensa até você retomar. O saldo da conta continua sendo acompanhado.',
  },
  resume: {
    button: 'Retomar objetivo',
    title: 'Retomar este objetivo?',
    text: () => 'A previsão volta a considerar o aporte planejado.',
  },
  complete: {
    button: 'Concluir objetivo',
    title: 'Concluir este objetivo?',
    text: (goal) => (
      <>O progresso fica registrado em <Money value={goal.progress.saved} /> e o objetivo não pode mais ser editado. O dinheiro continua na conta.</>
    ),
  },
  archive: {
    button: 'Arquivar objetivo',
    title: 'Arquivar este objetivo?',
    text: () => 'Ele sai da lista e não pode ser retomado. Nenhum saldo ou transação é apagado.',
  },
}

function StatusSection({ goal, onDone }: { goal: Goal; onDone: (action: GoalAction) => void }) {
  const [pending, setPending] = useState<GoalAction | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const actions: GoalAction[] =
    goal.status === 'ACTIVE' ? ['pause', 'complete', 'archive']
      : goal.status === 'PAUSED' ? ['resume', 'complete', 'archive']
        : goal.status === 'COMPLETED' ? ['archive'] : []
  if (actions.length === 0) return null

  async function confirm() {
    if (!pending || busy) return
    setBusy(true)
    setError(null)
    const result = await changeGoalStatus(goal.id, pending)
    if (result.ok) {
      onDone(pending)
      return
    }
    setBusy(false)
    setError(goalFailureMessage(result.code))
  }

  return (
    <section className={styles.section} aria-labelledby="goal-status">
      <h2 id="goal-status" className={styles.sectionTitle}>Status do objetivo</h2>
      {pending ? (
        <div className={styles.confirm} role="group" aria-labelledby="goal-status-confirm">
          <p id="goal-status-confirm"><strong>{CONFIRM[pending].title}</strong> {CONFIRM[pending].text(goal)}</p>
          {error ? (
            <p className={accountStyles.fieldError} role="alert">
              <CircleAlert size={16} strokeWidth={1.75} aria-hidden="true" /><span>{error}</span>
            </p>
          ) : null}
          <div className={styles.confirmActions}>
            <Button onClick={confirm} disabled={busy}>{busy ? 'Salvando…' : 'Confirmar'}</Button>
            <Button variant="secondary" onClick={() => { setPending(null); setError(null) }} disabled={busy}>Cancelar</Button>
          </div>
        </div>
      ) : (
        <div className={styles.statusActions}>
          {actions.map((action) => (
            <Button key={action} variant="secondary" onClick={() => setPending(action)}>{CONFIRM[action].button}</Button>
          ))}
        </div>
      )}
    </section>
  )
}
