'use client'

import Link from 'next/link'
import { Pencil, Plus } from 'lucide-react'
import { useState } from 'react'
import { GoalProjection } from '@/components/charts/GoalProjection'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { EstimateBadge, FactBadge } from '@/components/ui/ProjectedValue'
import { accountNames, useAccounts } from '@/features/transactions/hooks'
import { formatShortDate } from '@/lib/format/date'
import { bffCall } from '@/lib/bff-client'
import { goalPath, type Goal, type GoalMovement } from './api'
import { ContributionDrawer } from './ContributionDrawer'
import { CONFIDENCE_LABEL, longMonthYear, monthOfDate, monthYear, noForecastText, rangeText } from './format'
import { useGoal, useGoalContributions, useGoalProjection } from './hooks'
import { goalLoadMessage } from './messages'
import { ErrorPanel, GoalIcon, LoadingPanel, PageHeader, ProgressBar, StatusBadge } from './parts'
import { SimulationCard } from './SimulationCard'
import styles from './Goals.module.css'
import accountStyles from '@/features/accounts/Accounts.module.css'

/** D-GoalDetail (ADR-0037). Id que não é UUID nem chega ao BFF: é o mesmo "não encontrado" de outro Workspace. */
export function GoalDetailView({ id }: { id: string | null }) {
  if (!id) return <NotFound />
  return <GoalDetail id={id} />
}

function NotFound() {
  return (
    <div className={accountStyles.page}>
      <ErrorPanel title="Objetivo não encontrado" message={goalLoadMessage('GOAL_NOT_FOUND')} />
      <div><Button href="/goals" variant="secondary">Voltar para Objetivos</Button></div>
    </div>
  )
}

function GoalDetail({ id }: { id: string }) {
  const goal = useGoal(id)
  // Muda depois de um aporte ou de "Aplicar": o gráfico e o histórico são relidos do backend.
  const [version, setVersion] = useState(0)
  const [notice, setNotice] = useState<string | null>(null)
  const [drawerTrigger, setDrawerTrigger] = useState<HTMLElement | null | undefined>(undefined)
  const accounts = useAccounts()

  if (goal.status === 'loading') {
    return (
      <div className={accountStyles.page}>
        <LoadingPanel label="Carregando o objetivo…" rows={5} />
      </div>
    )
  }
  if (goal.status === 'error' || !goal.data) {
    if (goal.status === 'error' && goal.failure.code === 'GOAL_NOT_FOUND') return <NotFound />
    return (
      <div className={accountStyles.page}>
        <ErrorPanel title="Objetivo indisponível" message={goalLoadMessage('UNAVAILABLE')} onRetry={goal.retry} />
      </div>
    )
  }

  const data = goal.data
  const names = accountNames(accounts)
  const source = data.sourceAccountId ? names.get(data.sourceAccountId) : undefined
  const open = data.status === 'ACTIVE' || data.status === 'PAUSED'
  const canContribute = open && data.progress.destinationState === 'OK'
  // Relê o objetivo sem voltar ao esqueleto: o progresso é o saldo do destino, recalculado pelo backend.
  const refresh = async (message: string) => {
    setNotice(message)
    setVersion((v) => v + 1)
    const result = await bffCall<Goal>(goalPath(data.id))
    if (result.ok) goal.replace(result.data)
  }

  return (
    <div className={accountStyles.page}>
      <PageHeader
        current={data.name}
        title={data.name}
        subtitle={`Criado em ${monthOfDate(data.createdAt)}${source ? ` · aportes de ${source}` : ''}`}
        actions={
          <>
            {data.status !== 'ARCHIVED' ? (
              <Button href={`/goals/${data.id}/edit`} variant="secondary">
                <Pencil size={18} strokeWidth={1.75} aria-hidden="true" /> {data.status === 'COMPLETED' ? 'Gerenciar' : 'Editar'}
              </Button>
            ) : null}
            {canContribute ? (
              <Button onClick={(e) => setDrawerTrigger(e.currentTarget)} disabled={accounts.status !== 'success'}>
                <Plus size={18} strokeWidth={1.75} aria-hidden="true" /> Registrar aporte
              </Button>
            ) : null}
          </>
        }
      />

      <p className={accountStyles.live} role="status">{notice}</p>

      <div className={styles.detail}>
        <div className={styles.mainColumn}>
          <ProgressPanel goal={data} />
          <ProjectionSection goal={data} version={version} />
          <ContributionsSection goal={data} version={version} names={names} />
        </div>
        <div className={styles.sideColumn}>
          {open && !data.progress.reached ? (
            // Remonta quando o plano, o guardado ou o prazo mudam: as simulações do plano e do mínimo são relidas.
            <SimulationCard key={`${data.plannedMonthly.amount}:${data.progress.saved.amount}:${data.deadline}:${version}`} goal={data} onApplied={(updated, message) => {
              goal.replace(updated)
              setNotice(message)
              setVersion((v) => v + 1)
            }} />
          ) : null}
        </div>
      </div>

      {drawerTrigger !== undefined && accounts.status === 'success' ? (
        <ContributionDrawer
          goal={data}
          accounts={accounts.data}
          trigger={drawerTrigger}
          onClose={() => setDrawerTrigger(undefined)}
          onDone={(message) => {
            setDrawerTrigger(undefined)
            void refresh(message)
          }}
        />
      ) : null}
    </div>
  )
}

function ProgressPanel({ goal }: { goal: Goal }) {
  const { progress, forecast } = goal
  const range = rangeText(forecast)
  return (
    <section className={styles.section} aria-labelledby="goal-progress">
      <h2 id="goal-progress" className="sr-only">Progresso</h2>
      <div className={styles.goalTop}>
        <GoalIcon goal={goal} large />
        <p className={styles.amounts}>
          <span className={styles.saved}><Money value={progress.saved} /></span>
          <span className={styles.target}>de <Money value={goal.targetAmount} /></span>
        </p>
        <StatusBadge goal={goal} />
      </div>
      <ProgressBar percent={progress.percent} />
      <dl className={styles.facts}>
        <div><dt>Concluído</dt><dd>{progress.percent}%</dd></div>
        <div><dt>Faltam</dt><dd><Money value={progress.remaining} /></dd></div>
        <div><dt>Prazo</dt><dd>{goal.deadline ? monthYear(goal.deadline) : 'Sem prazo'}</dd></div>
        <div><dt>Aporte planejado</dt><dd><Money value={goal.plannedMonthly} />/mês</dd></div>
        <div>
          <dt>Previsão</dt>
          {forecast.completion ? (
            <dd>
              <span aria-hidden="true">{monthYear(forecast.completion)}</span>
              <span className="sr-only">{longMonthYear(forecast.completion)}, estimado</span>
              <EstimateBadge />
              {range || forecast.confidence ? (
                <span className={styles.factNote}>
                  {range ? `Faixa: ${range}` : ''}
                  {range && forecast.confidence ? ' · ' : ''}
                  {forecast.confidence ? `confiança ${CONFIDENCE_LABEL[forecast.confidence]}` : ''}
                </span>
              ) : null}
            </dd>
          ) : (
            <dd className={styles.factNote}>{forecast.status === 'REACHED' ? 'Alvo atingido' : noForecastText(forecast)}</dd>
          )}
        </div>
        {forecast.realPace ? (
          <div><dt>Ritmo real</dt><dd><Money value={forecast.realPace} />/mês<span className={styles.factNote}>mediana dos últimos meses</span></dd></div>
        ) : null}
      </dl>
      {forecast.freshness === 'RECALCULATING' ? <p className={styles.notice}>Atualizando a previsão com os dados mais recentes…</p> : null}
      {goal.status === 'PAUSED' ? (
        <p className={styles.notice}>Objetivo pausado: a previsão volta quando você retomar. O saldo continua sendo acompanhado.</p>
      ) : null}
      {goal.status === 'COMPLETED' && goal.completedAt ? (
        <p className={styles.notice}>
          Concluído em {formatShortDate(goal.completedAt.slice(0, 10))} com <Money value={goal.completedAmount} />.
        </p>
      ) : null}
      {progress.destinationState === 'ACCOUNT_ARCHIVED' ? (
        <p className={`${styles.notice} ${styles.warningNotice}`}>
          A conta do objetivo foi arquivada. <Link href={`/goals/${goal.id}/edit`}>Escolha outra conta</Link> para voltar a
          registrar aportes.
        </p>
      ) : null}
      {progress.destinationState === 'NO_RESERVE_ACCOUNT' ? (
        <p className={`${styles.notice} ${styles.warningNotice}`}>
          Você ainda não tem uma conta de reserva (poupança). <Link href="/accounts/new">Adicione uma conta</Link> para
          guardar a reserva e registrar aportes.
        </p>
      ) : null}
    </section>
  )
}

function ProjectionSection({ goal, version }: { goal: Goal; version: number }) {
  const projection = useGoalProjection(goal.id, version)
  return (
    <section className={styles.section} aria-labelledby="goal-projection">
      <h2 id="goal-projection" className={styles.sectionTitle}>Previsão</h2>
      {projection.status === 'loading' ? (
        <div role="status" aria-busy="true"><span className="sr-only">Carregando o gráfico…</span></div>
      ) : projection.status === 'error' || !projection.data ? (
        <p className={styles.sectionNote} role="alert">
          Não conseguimos carregar o gráfico agora.{' '}
          <button type="button" className={styles.textLink} onClick={projection.retry}>Tentar novamente</button>
        </p>
      ) : (
        <>
          <GoalProjection
            title={`Guardado e projeção de ${goal.name}`}
            actual={projection.data.actual.points}
            projected={projection.data.projected.points}
            target={projection.data.target}
            deadline={projection.data.deadline}
            actualLabel="Guardado"
            projectedLabel="Projeção pelo aporte planejado"
          />
          {projection.data.projected.points.length === 0 ? (
            <p className={styles.sectionNote}>{noForecastText(projection.data.forecast)}</p>
          ) : null}
        </>
      )}
    </section>
  )
}

const MOVEMENT_LABEL: Record<GoalMovement['kind'], string> = { CONTRIBUTION: 'Aporte', WITHDRAWAL: 'Resgate', OTHER: 'Movimento' }

function ContributionsSection({ goal, version, names }: { goal: Goal; version: number; names: Map<string, string> }) {
  const history = useGoalContributions(goal.id, version)
  return (
    <section className={styles.section} aria-labelledby="goal-contributions">
      <h2 id="goal-contributions" className={styles.sectionTitle}>Aportes</h2>
      {history.status === 'loading' ? (
        <div role="status" aria-busy="true"><span className="sr-only">Carregando os aportes…</span></div>
      ) : history.status === 'error' || !history.data ? (
        <p className={styles.sectionNote} role="alert">
          Não conseguimos carregar os aportes agora.{' '}
          <button type="button" className={styles.textLink} onClick={history.retry}>Tentar novamente</button>
        </p>
      ) : (
        <>
          <p className={styles.sectionNote}>
            Saldo inicial de <Money value={history.data.openingBalance.amount} /> em{' '}
            {formatShortDate(history.data.openingBalance.date)}, antes de acompanhar o objetivo.
          </p>
          {history.data.movements.length === 0 ? (
            <p className={styles.sectionNote}>Nenhum aporte desde a criação do objetivo.</p>
          ) : (
            <ul className={styles.rows}>
              {history.data.movements.map((m) => {
                const counterpart = m.counterpartAccountId ? names.get(m.counterpartAccountId) : undefined
                const sign = m.direction === 'IN' ? 'inflow' : 'outflow'
                return (
                  <li key={m.transactionId} className={styles.row}>
                    <span className={styles.rowDate}>{formatShortDate(m.occurredOn)}</span>
                    <span className={styles.rowText}>
                      <span className={styles.rowTitle}>{MOVEMENT_LABEL[m.kind]}</span>
                      {counterpart ? (
                        <span className={styles.rowMeta}>{m.direction === 'IN' ? 'De' : 'Para'} {counterpart}</span>
                      ) : null}
                    </span>
                    <span className={styles.rowValue}>
                      <Money value={m.amount} sign={sign} />
                      <FactBadge />
                    </span>
                  </li>
                )
              })}
            </ul>
          )}
        </>
      )}
    </section>
  )
}
