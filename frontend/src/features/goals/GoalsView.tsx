'use client'

import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { ChevronRight, Plus, SlidersHorizontal, Target } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { FactBadge } from '@/components/ui/ProjectedValue'
import { accountNames, useAccounts } from '@/features/transactions/hooks'
import { formatShortDate } from '@/lib/format/date'
import type { Goal, GoalList, GoalStatus } from './api'
import { monthName, monthYear, TYPE_ICON } from './format'
import { useGoals, useRecentContributions, useSimulation } from './hooks'
import { goalLoadMessage } from './messages'
import { ErrorPanel, GoalCard, LoadingPanel, Toast } from './parts'
import styles from './Goals.module.css'
import accountStyles from '@/features/accounts/Accounts.module.css'

export type GoalsFilter = 'OPEN' | Extract<GoalStatus, 'COMPLETED' | 'ARCHIVED'>

const FILTERS: { value: GoalsFilter; label: string; href: string }[] = [
  { value: 'OPEN', label: 'Em andamento', href: '/goals' },
  { value: 'COMPLETED', label: 'Concluídos', href: '/goals?status=COMPLETED' },
  { value: 'ARCHIVED', label: 'Arquivados', href: '/goals?status=ARCHIVED' },
]

/**
 * D-Goals / M-Goals (ADR-0037): métricas, sugestão de simulação, Goal Cards e aportes recentes. `created` (id do
 * objetivo recém-criado) e `firstMonth` (mês do primeiro aporte, da prévia Simulada) só montam o toast do
 * D-GoalCreated; nenhum valor trafega na URL.
 */
export function GoalsView({ filter = 'OPEN', created = null, firstMonth = null }: {
  filter?: GoalsFilter
  created?: string | null
  firstMonth?: string | null
}) {
  const router = useRouter()
  const goals = useGoals(filter === 'OPEN' ? null : filter)
  const [toastOpen, setToastOpen] = useState(created !== null)
  const closeToast = () => {
    setToastOpen(false)
    router.replace(filter === 'OPEN' ? '/goals' : `/goals?status=${filter}`)
  }

  return (
    <div className={accountStyles.page}>
      <header className={styles.header}>
        <div className={styles.headerText}>
          <p className={styles.eyebrow}>Objetivos</p>
          <h1 className={accountStyles.pageTitle}>Objetivos</h1>
          <p className={accountStyles.pageSubtitle}>Estou no caminho certo?</p>
        </div>
        <Button href="/goals/new">
          <Plus size={18} strokeWidth={1.75} aria-hidden="true" /> Criar objetivo
        </Button>
      </header>

      {goals.status === 'loading' ? <LoadingPanel label="Carregando seus objetivos…" rows={4} /> : null}
      {goals.status === 'error' ? (
        <ErrorPanel title="Objetivos indisponíveis" message={goalLoadMessage(goals.failure.code)} onRetry={goals.retry} />
      ) : null}
      {goals.status === 'success' && goals.data ? (
        <GoalsContent list={goals.data} filter={filter} created={created} />
      ) : null}

      {toastOpen && created ? (
        <Toast
          message={firstMonth
            ? `Objetivo criado. O primeiro aporte está previsto para ${monthName(firstMonth)}.`
            : 'Objetivo criado.'}
          action={{ href: `/goals/${created}`, label: 'Ver objetivo' }}
          onClose={closeToast}
        />
      ) : null}
    </div>
  )
}

function GoalsContent({ list, filter, created }: { list: GoalList; filter: GoalsFilter; created: string | null }) {
  const { summary } = list
  // O recém-criado vem primeiro (D-GoalCreated); o resto mantém a ordem do backend (mais recentes primeiro).
  const goals = useMemo(() => {
    const fresh = list.goals.filter((g) => g.id === created)
    return [...fresh, ...list.goals.filter((g) => g.id !== created)]
  }, [list.goals, created])
  const hasOpen = summary.activeCount > 0 || (filter === 'OPEN' && list.goals.length > 0)

  return (
    <>
      {hasOpen || filter !== 'OPEN' ? <Metrics list={list} /> : null}
      {filter === 'OPEN' ? <Suggestion goals={list.goals} /> : null}

      <section className={accountStyles.section} aria-labelledby="my-goals">
        <div className={styles.sectionHead}>
          <h2 id="my-goals" className={styles.plainTitle}>Meus objetivos</h2>
          <nav aria-label="Filtrar objetivos">
            <ul className={styles.chips}>
              {FILTERS.map((item) => (
                <li key={item.value}>
                  <Link href={item.href} className={styles.textLink} aria-current={item.value === filter ? 'page' : undefined}>
                    {item.label}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>
        </div>
        {goals.length === 0 ? (
          <Empty filter={filter} />
        ) : (
          <ul className={styles.list}>
            {goals.map((goal) => (
              <li key={goal.id}><GoalCard goal={goal} fresh={goal.id === created} /></li>
            ))}
          </ul>
        )}
      </section>

      {filter === 'OPEN' && list.goals.length > 0 ? <RecentContributionsSection goals={list.goals} /> : null}
    </>
  )
}

function Metrics({ list }: { list: GoalList }) {
  const { summary } = list
  const active = summary.activeCount
  return (
    <dl className={styles.metrics} aria-label="Resumo dos objetivos">
      <div className={styles.metric}>
        <dt>Guardado em objetivos</dt>
        <dd className={styles.metricValue}><Money value={summary.savedTotal} /></dd>
        <dd className={styles.metricNote}>{active} {active === 1 ? 'objetivo ativo' : 'objetivos ativos'}</dd>
      </div>
      <div className={styles.metric}>
        <dt>Aportes planejados por mês</dt>
        <dd className={styles.metricValue}><Money value={summary.plannedMonthlyTotal} /></dd>
        {summary.plannedIncomeShare !== null ? (
          <dd className={styles.metricNote}>
            <span aria-hidden="true">≈ {summary.plannedIncomeShare}% da sua renda média</span>
            <span className="sr-only">aproximadamente {summary.plannedIncomeShare}% da sua renda média</span>
          </dd>
        ) : null}
      </div>
      <div className={styles.metric}>
        <dt>No ritmo atual</dt>
        {summary.forecastCount > 0 ? (
          <>
            <dd className={styles.metricValue}>{summary.onTrackCount} de {summary.forecastCount}</dd>
            <dd className={styles.metricNote}>objetivos dentro do prazo ou sem prazo</dd>
          </>
        ) : (
          <>
            <dd className={styles.metricValue}><span aria-hidden="true">—</span><span className="sr-only">sem previsão</span></dd>
            <dd className={styles.metricNote}>
              {summary.forecastFreshness === 'RECALCULATING'
                ? 'Atualizando a previsão…'
                : 'A previsão aparece quando houver 3 meses de histórico.'}
            </dd>
          </>
        )}
      </div>
    </dl>
  )
}

/**
 * Sugestão de simulação do D-Goals (sem ✦: é determinística, ADR-0034): o primeiro objetivo ativo que, pela previsão,
 * passa do prazo, com o aporte mínimo para cumpri-lo calculado pelo backend (Simulado). Sem objetivo atrasado, nada.
 */
function Suggestion({ goals }: { goals: Goal[] }) {
  const late = goals.find((g) => g.status === 'ACTIVE' && g.forecast.status === 'LATE' && g.deadline)
  const simulation = useSimulation(late?.id ?? null, late?.deadline ? [{ deadline: late.deadline }] : null)
  if (!late || simulation.status !== 'success' || !simulation.data) return null
  const scenario = simulation.data.scenarios[0]
  if (!scenario?.completion || scenario.tooFar || !scenario.deadline?.within) return null
  return (
    <aside className={styles.suggestion} aria-labelledby="goal-suggestion">
      <SlidersHorizontal size={18} strokeWidth={1.75} aria-hidden="true" />
      <p className={styles.suggestionText}>
        <span id="goal-suggestion" className={styles.suggestionLabel}>Simulação</span>
        <span className={styles.suggestionBody}>
          Com <Money value={scenario.monthlyAmount} />/mês, {late.name} fica pronto em {monthYear(scenario.completion)} — dentro do prazo.
        </span>
      </p>
      <Link href={`/goals/${late.id}#simular`} className={styles.textLink}>
        Simular <ChevronRight size={16} strokeWidth={1.75} aria-hidden="true" />
        <span className="sr-only"> {late.name}</span>
      </Link>
    </aside>
  )
}

function Empty({ filter }: { filter: GoalsFilter }) {
  if (filter !== 'OPEN') {
    return (
      <div className={styles.empty}>
        <p className={styles.emptyText}>
          {filter === 'COMPLETED' ? 'Nenhum objetivo concluído ainda.' : 'Nenhum objetivo arquivado.'}
        </p>
      </div>
    )
  }
  return (
    <div className={styles.empty}>
      <span className={accountStyles.stateIcon} aria-hidden="true"><Target size={24} strokeWidth={1.75} /></span>
      <h3 className={styles.emptyTitle}>Crie seu primeiro objetivo</h3>
      <p className={styles.emptyText}>
        Uma reserva de emergência, uma viagem ou uma compra: defina o valor e o prazo, e mostramos se o plano fecha. O
        progresso vem do saldo da conta do objetivo.
      </p>
      <Button href="/goals/new">Criar objetivo</Button>
    </div>
  )
}

function RecentContributionsSection({ goals }: { goals: Goal[] }) {
  const recent = useRecentContributions()
  const accounts = useAccounts()
  const names = accountNames(accounts)
  const byId = new Map(goals.map((g) => [g.id, g]))

  return (
    <section className={styles.section} aria-labelledby="recent-contributions">
      <h2 id="recent-contributions" className={styles.sectionTitle}>Aportes recentes</h2>
      {recent.status === 'loading' ? (
        <div role="status" aria-busy="true"><span className="sr-only">Carregando aportes…</span></div>
      ) : recent.status === 'error' ? (
        <p className={styles.sectionNote} role="alert">
          Não conseguimos carregar os aportes agora.{' '}
          <button type="button" className={styles.textLink} onClick={recent.retry}>Tentar novamente</button>
        </p>
      ) : !recent.data || recent.data.contributions.length === 0 ? (
        <p className={styles.sectionNote}>
          Nenhum aporte ainda. Um aporte é uma transferência para a conta do objetivo; registre pelo próprio objetivo.
        </p>
      ) : (
        <ul className={styles.rows}>
          {recent.data.contributions.map((item) => {
            const goal = byId.get(item.goalId)
            const Icon = goal ? TYPE_ICON[goal.type] : Target
            const source = item.sourceAccountId ? names.get(item.sourceAccountId) : undefined
            return (
              <li key={item.transactionId} className={styles.row}>
                <span className={styles.rowIcon} aria-hidden="true"><Icon size={16} strokeWidth={1.75} /></span>
                <span className={styles.rowText}>
                  <span className={styles.rowTitle}>{goal?.name ?? 'Objetivo'}</span>
                  <span className={styles.rowMeta}>
                    {formatShortDate(item.occurredOn)}{source ? ` · de ${source}` : ''}
                  </span>
                </span>
                <span className={styles.rowValue}>
                  <Money value={item.amount} />
                  <FactBadge />
                </span>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
