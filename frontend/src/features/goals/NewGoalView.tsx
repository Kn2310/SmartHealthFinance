'use client'

import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { HeartPulse, Lightbulb } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Money } from '@/components/ui/Money'
import { useAccounts } from '@/features/transactions/hooks'
import { isUuid } from '@/lib/uuid'
import { useKeyed } from '@/lib/use-keyed'
import { parseAmountInput } from '@/lib/format/amount-input'
import { createGoal, previewGoal, type GoalPreview, type GoalType, type PreviewBody } from './api'
import { amountToInput, EMPTY_GOAL, GoalForm, parsePlanned, type GoalFormValues } from './GoalForm'
import { longMonthYear, monthYear, scenarioDeadlineText } from './format'
import { useGoals, useInsightForGoal } from './hooks'
import { ErrorPanel, LoadingPanel, PageHeader, SimulatedBadge } from './parts'
import styles from './Goals.module.css'
import accountStyles from '@/features/accounts/Accounts.module.css'

export type GoalOriginParam =
  | { kind: 'INSIGHT'; insightId: string }
  | { kind: 'HEALTH' }
  | { kind: 'MANUAL' }

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/
const NAME_MAX = 60

/**
 * D-CreateGoal (ADR-0037). A origem vem da URL só como tipo e id (`?origin=INSIGHT&insightId=` ou `?origin=HEALTH`); a
 * sugestão do insight (aporte, alvo, prazo) é lida do backend, nunca da URL. A prévia é Simulada e atualiza enquanto a
 * pessoa edita; "Já guardado" é o saldo do destino (fato).
 */
export function NewGoalView({ type = null, origin = { kind: 'MANUAL' } }: { type?: GoalType | null; origin?: GoalOriginParam }) {
  const accounts = useAccounts()
  const goals = useGoals()
  const insight = useInsightForGoal(origin.kind === 'INSIGHT' ? origin.insightId : null)
  const insightReady = origin.kind !== 'INSIGHT' || insight.status !== 'loading'

  if (accounts.status === 'loading' || goals.status === 'loading' || !insightReady) {
    return (
      <div className={accountStyles.page}>
        <Header />
        <LoadingPanel label="Preparando o formulário…" rows={5} />
      </div>
    )
  }
  // O insight de origem falhou por instabilidade (não por não existir): não cria em silêncio sem o vínculo.
  const insightFailed = origin.kind === 'INSIGHT' && insight.status === 'error' && insight.failure.code !== 'INSIGHT_NOT_FOUND'
  if (accounts.status === 'error' || goals.status === 'error' || insightFailed) {
    return (
      <div className={accountStyles.page}>
        <Header />
        <ErrorPanel
          title="Formulário indisponível"
          message="Não conseguimos preparar o formulário agora. Seus dados estão seguros."
          onRetry={accounts.status === 'error' ? accounts.retry : goals.status === 'error' ? goals.retry : insight.retry}
        />
      </div>
    )
  }

  // Sugestão do insight (CREATE_SAVINGS_GOAL, ADR-0036 §4); sem ela (insight dispensado/indisponível), criação manual.
  const detail = origin.kind === 'INSIGHT' && insight.status === 'success' ? insight.data : null
  const action = detail?.actions.find((a) => a.kind === 'CREATE_SAVINGS_GOAL' && a.available)
  const open = goals.data?.goals ?? []
  const reserveExists = open.some((g) => g.type === 'EMERGENCY_RESERVE')
  const resolvedOrigin: GoalOriginParam = action && detail ? origin : origin.kind === 'HEALTH' ? origin : { kind: 'MANUAL' }

  let initial: GoalFormValues = { ...EMPTY_GOAL }
  if (action && detail) {
    const p = action.params
    initial = {
      ...initial,
      type: 'CUSTOM',
      name: `Guardar a diferença em ${detail.insight.subjectLabel.toLocaleLowerCase('pt-BR')}`.slice(0, NAME_MAX),
      targetAmount: p.suggestedTarget ? amountToInput(p.suggestedTarget) : '',
      plannedMonthly: p.suggestedMonthly ? amountToInput(p.suggestedMonthly) : '',
      deadline: p.suggestedDeadline && MONTH.test(p.suggestedDeadline) ? p.suggestedDeadline : '',
    }
  } else if ((type === 'EMERGENCY_RESERVE' || origin.kind === 'HEALTH') && !reserveExists) {
    initial = { ...initial, type: 'EMERGENCY_RESERVE', name: 'Reserva de emergência' }
  } else if (type && type !== 'EMERGENCY_RESERVE') {
    initial = { ...initial, type }
  }

  return (
    <CreateGoal
      initial={initial}
      origin={resolvedOrigin}
      originLink={detail ? { href: `/insights/${detail.insight.id}`, label: detail.insight.subjectLabel } : null}
      accounts={accounts.data ?? []}
      usedDestinations={new Set(open.flatMap((g) => (g.destination.accountId ? [g.destination.accountId] : [])))}
      reserveExists={reserveExists}
    />
  )
}

function Header() {
  return (
    <PageHeader current="Criar objetivo" title="Criar objetivo" subtitle="Defina o valor e o prazo — mostramos se o plano fecha." />
  )
}

/**
 * Corpo da prévia a partir do que já está bem formado; `null` sem tipo. Sem aporte válido ainda, pede com zero: o
 * "Já guardado" e o alvo sugerido da reserva aparecem antes de a pessoa digitar o plano.
 */
function previewBody(values: GoalFormValues): PreviewBody | null {
  if (!values.type) return null
  const planned = parsePlanned(values.plannedMonthly)
  const target = parseAmountInput(values.targetAmount)
  return {
    type: values.type,
    targetAmount: target.ok ? { amount: target.value, currency: 'BRL' } : null,
    plannedMonthly: { amount: planned.ok ? planned.value : '0.00', currency: 'BRL' },
    deadline: MONTH.test(values.deadline) ? values.deadline : null,
    destinationAccountId: values.type !== 'EMERGENCY_RESERVE' && isUuid(values.destinationAccountId) ? values.destinationAccountId : null,
  }
}

/** Espera a pessoa parar de digitar antes de pedir a prévia (uma chamada por pausa, não por tecla). */
function useDebounced<T>(value: T, delay = 400): T {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])
  return settled
}

function CreateGoal({ initial, origin, originLink, accounts, usedDestinations, reserveExists }: {
  initial: GoalFormValues
  origin: GoalOriginParam
  originLink: { href: string; label: string } | null
  accounts: Parameters<typeof GoalForm>[0]['accounts']
  usedDestinations: Set<string>
  reserveExists: boolean
}) {
  const router = useRouter()
  const [values, setValues] = useState(initial)
  const onValuesChange = useCallback((next: GoalFormValues) => setValues(next), [])
  const body = useMemo(() => previewBody(values), [values])
  const key = useDebounced(body ? JSON.stringify(body) : null)
  const { value: preview, previous } = useKeyed<GoalPreview | null>(`preview:${key}`, async () => {
    if (!key) return { status: 'success', data: null }
    const result = await previewGoal(JSON.parse(key) as PreviewBody)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })
  // Enquanto carrega, a anterior fica visível; se a nova falha, nada antigo é mostrado como se valesse para o formulário.
  const shown = preview.status === 'success' ? preview.data
    : preview.status === 'loading' && previous?.status === 'success' ? previous.data : null
  const failed = preview.status === 'error'
  const updating = preview.status === 'loading' && key !== null

  const banner =
    origin.kind === 'INSIGHT' && originLink ? (
      <p className={styles.origin}>
        <Lightbulb size={16} strokeWidth={1.75} aria-hidden="true" />
        <span>Criado a partir do insight <Link href={originLink.href}>{originLink.label}</Link></span>
      </p>
    ) : origin.kind === 'HEALTH' ? (
      <p className={styles.origin}>
        <HeartPulse size={16} strokeWidth={1.75} aria-hidden="true" />
        <span>Criado a partir da sua <Link href="/home/health">Saúde financeira</Link></span>
      </p>
    ) : null

  return (
    <div className={accountStyles.page}>
      <Header />
      <div className={styles.createLayout}>
        <section className={styles.section} aria-label="Dados do objetivo">
          <GoalForm
            mode="create"
            initial={initial}
            accounts={accounts}
            usedDestinations={usedDestinations}
            reserveExists={reserveExists}
            alreadySaved={shown ? shown.alreadySaved : null}
            suggestedTarget={values.type === 'EMERGENCY_RESERVE' ? (shown?.suggestedTarget ?? null) : null}
            header={banner}
            onValuesChange={onValuesChange}
            submitLabel="Criar objetivo"
            submittingLabel="Criando…"
            cancelHref="/goals"
            onSubmit={async (fields) => {
              const result = await createGoal({
                name: fields.name,
                type: fields.type,
                targetAmount: { amount: fields.targetAmount, currency: 'BRL' },
                plannedMonthly: { amount: fields.plannedMonthly, currency: 'BRL' },
                deadline: fields.deadline,
                sourceAccountId: fields.sourceAccountId,
                destinationAccountId: fields.destinationAccountId,
                origin: origin.kind === 'INSIGHT' ? { kind: 'INSIGHT', insightId: origin.insightId } : { kind: origin.kind },
              })
              if (!result.ok) return result
              const start = preview.status === 'success' ? preview.data?.simulation?.start : undefined
              router.push(`/goals?created=${result.data.id}${start && MONTH.test(start) ? `&first=${start}` : ''}`)
              return null
            }}
          />
        </section>
        <PreviewPanel preview={shown} updating={updating} failed={failed} />
      </div>
    </div>
  )
}

/** Prévia Simulada do D-CreateGoal: mês, prazo, aportes necessários, primeiro aporte e peso na renda. */
function PreviewPanel({ preview, updating, failed }: { preview: GoalPreview | null; updating: boolean; failed: boolean }) {
  const scenario = preview?.simulation?.scenarios[0]
  const deadline = scenario ? scenarioDeadlineText(scenario) : null
  return (
    <aside className={`${styles.simulation} ${styles.preview}`} aria-labelledby="goal-preview" aria-live="polite">
      <div className={styles.sectionHead}>
        <h2 id="goal-preview" className={styles.sectionTitle}>Previsão</h2>
        <SimulatedBadge />
      </div>
      {failed ? (
        <p className={styles.sectionNote} role="alert">Não conseguimos atualizar a previsão agora. Revise os campos ou tente de novo em instantes.</p>
      ) : !scenario ? (
        <p className={styles.sectionNote}>Preencha o valor alvo e o aporte mensal para ver quando você chega lá.</p>
      ) : scenario.tooFar || !scenario.completion ? (
        <p className={styles.sectionNote}>
          Com <Money value={scenario.monthlyAmount} /> por mês, a conclusão passa de 50 anos. Experimente um aporte maior.
        </p>
      ) : (
        <>
          <p className={styles.resultLead}>Com <Money value={scenario.monthlyAmount} /> por mês, você alcança em</p>
          <p className={styles.resultMonth}>
            <span aria-hidden="true">{monthYear(scenario.completion)}</span>
            <span className="sr-only">{longMonthYear(scenario.completion)}, simulado</span>
          </p>
          {deadline ? <p className={deadline.within ? styles.within : styles.outside}>{deadline.text}</p> : null}
          <dl className={styles.stats}>
            <div><dt>Aportes necessários</dt><dd>{scenario.contributions}</dd></div>
            <div><dt>Primeiro aporte</dt><dd>{monthYear(scenario.firstContribution)}</dd></div>
            {scenario.incomeShare !== null ? (
              <div>
                <dt>Peso na renda média</dt>
                <dd><span aria-hidden="true">≈ {scenario.incomeShare}%</span><span className="sr-only">aproximadamente {scenario.incomeShare}%</span></dd>
              </div>
            ) : null}
            {scenario.requiredMonthlyForDeadline && deadline && !deadline.within ? (
              <div><dt>Para cumprir o prazo</dt><dd><Money value={scenario.requiredMonthlyForDeadline} />/mês</dd></div>
            ) : null}
          </dl>
        </>
      )}
      <p className={styles.assumption}>
        Previsão com aportes mensais iguais, sem rendimento. Atualiza enquanto você edita.
        {updating ? <span className={styles.updating}> Atualizando…</span> : null}
      </p>
    </aside>
  )
}
