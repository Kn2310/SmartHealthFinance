'use client'

import { CircleAlert } from 'lucide-react'
import { useEffect, useId, useMemo, useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { formatMoney } from '@/lib/format/money'
import { parseAmountInput } from '@/lib/format/amount-input'
import { applyGoalPlan, type Goal, type SimulationScenario } from './api'
import { contributionsText, longMonthYear, monthYear, scenarioDeadlineText } from './format'
import { useSimulation } from './hooks'
import { goalFailureMessage, goalFieldMessage } from './messages'
import { SimulatedBadge } from './parts'
import styles from './Goals.module.css'
import accountStyles from '@/features/accounts/Accounts.module.css'

type Choice = 'plan' | 'required' | 'custom'

/** "700.0000" e "700.00" são o mesmo valor: compara só o texto decimal normalizado (sem aritmética). */
const sameAmount = (a: string, b: string) => {
  const norm = (v: string) => (v.includes('.') ? v.replace(/0+$/, '').replace(/\.$/, '') : v)
  return norm(a) === norm(b)
}

const brl = (amount: string) => ({ amount, currency: 'BRL' })

/**
 * Simulation Card do D-GoalDetail (02-components; ADR-0037): opções = o plano atual, o mínimo para cumprir o prazo
 * (calculado pelo backend) e "Outro valor". Tudo é Simulado e nada é gravado (ADR-0034 §5); "Aplicar" troca só o
 * aporte planejado, depois de uma confirmação explícita.
 */
export function SimulationCard({ goal, onApplied }: { goal: Goal; onApplied: (goal: Goal, message: string) => void }) {
  const id = useId()
  const hasPlan = !sameAmount(goal.plannedMonthly.amount, '0')
  const [custom, setCustom] = useState<string | null>(null)
  const [customInput, setCustomInput] = useState('')
  const [customError, setCustomError] = useState<string | null>(null)
  const [choice, setChoice] = useState<Choice | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [applyError, setApplyError] = useState<string | null>(null)

  // "Simular" da lista leva a #simular, mas o card só existe depois de o objetivo carregar: rola até ele ao montar.
  useEffect(() => {
    if (window.location.hash === '#simular') document.getElementById('simular')?.scrollIntoView?.({ block: 'start' })
  }, [])

  // Plano atual (+ "Outro valor") numa chamada; o mínimo para o prazo em outra, para que um prazo vencido (400) só
  // esconda aquele chip.
  const scenarios = useMemo(() => {
    const list: { monthlyAmount?: { amount: string; currency: string } }[] = []
    if (hasPlan) list.push({})
    if (custom) list.push({ monthlyAmount: brl(custom) })
    return list.length > 0 ? list : null
  }, [hasPlan, custom])
  const main = useSimulation(goal.id, scenarios)
  const deadlineScenarios = useMemo(() => (goal.deadline ? [{ deadline: goal.deadline }] : null), [goal.deadline])
  const minimum = useSimulation(goal.id, deadlineScenarios)

  const planScenario = main.status === 'success' && hasPlan ? main.data?.scenarios[0] : undefined
  const customScenario = main.status === 'success' && custom ? main.data?.scenarios[hasPlan ? 1 : 0] : undefined
  const required = minimum.status === 'success' ? minimum.data?.scenarios[0] : undefined
  // Só compara depois de o plano ter sido simulado: antes disso o chip do mínimo apareceria e sumiria.
  const planSettled = !hasPlan || main.status !== 'loading'
  const requiredDiffers = planSettled && required
    && !sameAmount(required.monthlyAmount.amount, planScenario?.monthlyAmount.amount ?? goal.plannedMonthly.amount)

  const selected: Choice | null = choice
    ?? (requiredDiffers && goal.forecast.status === 'LATE' ? 'required' : hasPlan ? 'plan' : requiredDiffers ? 'required' : null)
  const scenario: SimulationScenario | undefined =
    selected === 'plan' ? planScenario : selected === 'required' ? required : selected === 'custom' ? customScenario : undefined
  const loading = main.status === 'loading' || (goal.deadline !== null && minimum.status === 'loading')
  const canApply = scenario && !sameAmount(scenario.monthlyAmount.amount, goal.plannedMonthly.amount)

  function pick(next: Choice) {
    setChoice(next)
    setConfirming(false)
    setApplyError(null)
  }

  function simulateCustom(event: FormEvent) {
    event.preventDefault()
    const parsed = parseAmountInput(customInput)
    if (!parsed.ok) {
      setCustomError(goalFieldMessage('amount', parsed.error))
      document.getElementById(`${id}-custom`)?.focus()
      return
    }
    setCustomError(null)
    setCustom(parsed.value)
    pick('custom')
  }

  async function apply() {
    if (!scenario || busy) return
    setBusy(true)
    setApplyError(null)
    const result = await applyGoalPlan(goal.id, scenario.monthlyAmount, goal.deadline)
    setBusy(false)
    if (!result.ok) {
      setApplyError(goalFailureMessage(result.code))
      return
    }
    setConfirming(false)
    setChoice(null)
    setCustom(null)
    setCustomInput('')
    onApplied(result.data, `Plano atualizado: ${formatMoney(scenario.monthlyAmount)} por mês. A previsão já considera o novo valor.`)
  }

  const chip = (value: Choice, amount: string, note?: string) => (
    <button key={value} type="button" className={styles.chip} aria-pressed={selected === value} onClick={() => pick(value)}>
      <Money value={brl(amount)} />/mês
      {note ? <>{' '}<span className={styles.chipNote}>{note}</span></> : null}
    </button>
  )

  return (
    <section id="simular" className={styles.simulation} aria-labelledby={`${id}-title`}>
      <div className={styles.sectionHead}>
        <h2 id={`${id}-title`} className={styles.sectionTitle}>Simular aporte</h2>
        <SimulatedBadge />
      </div>
      <p className={styles.sectionNote}>Escolha um valor mensal e veja quando você alcança o objetivo.</p>

      <div className={styles.chips} role="group" aria-label="Valores para simular">
        {hasPlan ? chip('plan', goal.plannedMonthly.amount, 'plano atual') : null}
        {required && requiredDiffers ? chip('required', required.monthlyAmount.amount, 'mínimo para o prazo') : null}
        {custom ? chip('custom', custom, 'outro valor') : null}
      </div>

      <form className={styles.customRow} onSubmit={simulateCustom} noValidate>
        <div className={accountStyles.field}>
          <label htmlFor={`${id}-custom`} className={accountStyles.label}>Outro valor por mês</label>
          <input id={`${id}-custom`} className={accountStyles.input} inputMode="decimal" autoComplete="off" placeholder="0,00"
            value={customInput} onChange={(e) => setCustomInput(e.target.value)}
            aria-invalid={customError ? true : undefined}
            aria-describedby={customError ? `${id}-custom-error` : undefined} />
          {customError ? (
            <p id={`${id}-custom-error`} className={accountStyles.fieldError}>
              <CircleAlert size={16} strokeWidth={1.75} aria-hidden="true" /><span>{customError}</span>
            </p>
          ) : null}
        </div>
        <Button type="submit" variant="secondary">Simular</Button>
      </form>

      <div aria-live="polite">
        {loading && !scenario ? (
          <p className={styles.updating} role="status">Simulando…</p>
        ) : main.status === 'error' && !scenario ? (
          <p className={styles.sectionNote} role="alert">
            Não conseguimos simular agora.{' '}
            <button type="button" className={styles.textLink} onClick={main.retry}>Tentar novamente</button>
          </p>
        ) : scenario ? (
          <Result scenario={scenario} />
        ) : (
          <p className={styles.sectionNote}>Digite um valor mensal para simular.</p>
        )}
      </div>

      <p className={styles.assumption}>
        Simulação não altera seu objetivo. Considera aportes mensais iguais, sem rendimento.
      </p>

      {scenario && canApply && !scenario.tooFar ? (
        confirming ? (
          <div className={styles.confirm} role="group" aria-labelledby={`${id}-confirm`}>
            <p id={`${id}-confirm`}>
              Trocar o aporte planejado de <Money value={goal.plannedMonthly} /> para <Money value={scenario.monthlyAmount} /> por
              mês? {goal.deadline ? `O prazo continua ${monthYear(goal.deadline)}. ` : ''}Muda só o plano do objetivo: nenhum
              saldo ou transação é alterado.
            </p>
            {applyError ? (
              <p className={accountStyles.fieldError} role="alert">
                <CircleAlert size={16} strokeWidth={1.75} aria-hidden="true" /><span>{applyError}</span>
              </p>
            ) : null}
            <div className={styles.confirmActions}>
              <Button onClick={apply} disabled={busy}>{busy ? 'Aplicando…' : 'Confirmar'}</Button>
              <Button variant="secondary" onClick={() => setConfirming(false)} disabled={busy}>Cancelar</Button>
            </div>
          </div>
        ) : (
          <Button onClick={() => setConfirming(true)} aria-label={`Aplicar ${formatMoney(scenario.monthlyAmount)}/mês ao objetivo`}>
            Aplicar <Money value={scenario.monthlyAmount} />/mês ao objetivo
          </Button>
        )
      ) : null}
    </section>
  )
}

function Result({ scenario }: { scenario: SimulationScenario }) {
  if (scenario.tooFar || !scenario.completion) {
    return (
      <div className={styles.result}>
        <p className={styles.resultLead}>
          Com <Money value={scenario.monthlyAmount} /> por mês, a conclusão passa de 50 anos. Experimente um valor maior.
        </p>
      </div>
    )
  }
  const deadline = scenarioDeadlineText(scenario)
  return (
    <div className={styles.result}>
      <p className={styles.resultLead}>Com <Money value={scenario.monthlyAmount} /> por mês, você alcança em</p>
      <p className={styles.resultMonth}>
        <span aria-hidden="true">{monthYear(scenario.completion)}</span>
        <span className="sr-only">{longMonthYear(scenario.completion)}, simulado</span>
      </p>
      <p className={styles.resultMeta}>
        {deadline ? <span className={deadline.within ? styles.within : styles.outside}>{deadline.text}</span> : null}
        <span>
          {contributionsText(scenario.contributions)} a partir de {monthYear(scenario.firstContribution)}
        </span>
        {scenario.lastContribution && !sameAmount(scenario.lastContribution.amount.amount, scenario.monthlyAmount.amount) ? (
          <span>último de <Money value={scenario.lastContribution.amount} /></span>
        ) : null}
        {scenario.incomeShare !== null ? (
          <span>
            <span aria-hidden="true">≈ {scenario.incomeShare}% da renda média</span>
            <span className="sr-only">aproximadamente {scenario.incomeShare}% da renda média</span>
          </span>
        ) : null}
      </p>
    </div>
  )
}
