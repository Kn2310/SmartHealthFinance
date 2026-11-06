'use client'

import Link from 'next/link'
import { ArrowLeft, ChevronRight, CircleAlert, CircleCheck, Clock3, SlidersHorizontal, TriangleAlert, X } from 'lucide-react'
import { useEffect, useRef, type ReactNode } from 'react'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { EstimateBadge } from '@/components/ui/ProjectedValue'
import { Skeleton } from '@/components/ui/Skeleton'
import type { Goal } from './api'
import { longMonthYear, monthYear, noForecastText, statusBadge, TYPE_ICON } from './format'
import styles from './Goals.module.css'
import accountStyles from '@/features/accounts/Accounts.module.css'

/** Barra de progresso (fato). SVG com atributos, sem estilo inline (CSP); o percentual visível vem do backend. */
export function ProgressBar({ percent }: { percent: number }) {
  const width = Math.max(0, Math.min(100, percent))
  return (
    <svg className={styles.progress} viewBox="0 0 100 8" preserveAspectRatio="none" aria-hidden="true" focusable="false">
      <rect className={styles.progressTrack} x={0} y={0} width={100} height={8} rx={4} />
      {width > 0 ? <rect className={styles.progressFill} x={0} y={0} width={width} height={8} rx={4} /> : null}
    </svg>
  )
}

const BADGE_ICON = { clock: Clock3, alert: TriangleAlert, check: CircleCheck }

/** Status do objetivo/prazo com texto (nunca só cor); `fresh` = recém-criado ("Novo", D-GoalCreated). */
export function StatusBadge({ goal, fresh = false }: { goal: Goal; fresh?: boolean }) {
  const badge = fresh ? { label: 'Novo', short: 'Novo', tone: 'success' as const, icon: 'check' as const } : statusBadge(goal)
  if (!badge) return null
  const Icon = BADGE_ICON[badge.icon]
  return (
    <span className={`${styles.status} ${styles[badge.tone]}`}>
      <Icon size={14} strokeWidth={1.75} aria-hidden="true" />
      {badge.short === badge.label ? (
        badge.label
      ) : (
        <>
          <span className={styles.statusShort} aria-hidden="true">{badge.short}</span>
          <span className={styles.statusLong}>{badge.label}</span>
          <span className="sr-only">{badge.label}</span>
        </>
      )}
    </span>
  )
}

/** Selo "Simulado": Teal com borda pontilhada (01-foundations, Trust UX). */
export function SimulatedBadge() {
  return (
    <span className={styles.simulatedBadge}>
      <SlidersHorizontal size={14} strokeWidth={1.75} aria-hidden="true" />
      Simulado
    </span>
  )
}

/** "Previsão: ago/2027 ≈ Estimado" ou o motivo de não haver previsão (só fatos). */
export function ForecastLine({ goal, label = 'Previsão:' }: { goal: Goal; label?: string }) {
  const { forecast } = goal
  if (forecast.status === 'REACHED') return null
  if (!forecast.completion) return <span className={styles.noForecast}>{noForecastText(forecast)}</span>
  return (
    <span className={styles.forecastLine}>
      <span aria-hidden="true">{label}</span>
      <strong aria-hidden="true">{monthYear(forecast.completion)}</strong>
      <EstimateBadge />
      <span className="sr-only">Previsão estimada: {longMonthYear(forecast.completion)}</span>
    </span>
  )
}

export function GoalIcon({ goal, large = false }: { goal: Pick<Goal, 'type'>; large?: boolean }) {
  const Icon = TYPE_ICON[goal.type]
  return (
    <span className={`${styles.goalIcon} ${large ? styles.goalIconLarge : ''}`} aria-hidden="true">
      <Icon size={large ? 24 : 20} strokeWidth={1.75} />
    </span>
  )
}

export const deadlineText = (goal: Pick<Goal, 'deadline'>, short = false) =>
  goal.deadline ? `Prazo: ${monthYear(goal.deadline)}` : short ? 'Prazo: sem prazo' : 'Prazo: sem prazo definido'

/** Goal Card (02-components): ícone, nome, prazo, status, guardado/alvo, progresso, % e previsão (Estimado). */
export function GoalCard({ goal, fresh = false }: { goal: Goal; fresh?: boolean }) {
  return (
    <article className={styles.goalCard} aria-labelledby={`goal-${goal.id}`}>
      <div className={styles.goalTop}>
        <GoalIcon goal={goal} />
        <div className={styles.goalTitle}>
          <h3 id={`goal-${goal.id}`} className={styles.goalName}>
            <Link href={`/goals/${goal.id}`} className={styles.goalLink}>{goal.name}</Link>
          </h3>
          <span className={styles.goalMeta}>{deadlineText(goal)}</span>
        </div>
        <StatusBadge goal={goal} fresh={fresh} />
      </div>
      <p className={styles.amounts}>
        <span className={styles.saved}><Money value={goal.progress.saved} /></span>
        <span className={styles.target}>de <Money value={goal.targetAmount} /></span>
      </p>
      <ProgressBar percent={goal.progress.percent} />
      <div className={styles.goalFoot}>
        <span>{goal.progress.percent}% concluído</span>
        <ForecastLine goal={goal} />
      </div>
    </article>
  )
}

/** Cabeçalho das telas internas: breadcrumb "Objetivos › {página}", voltar e título. */
export function PageHeader({ current, title, subtitle, backHref = '/goals', actions }: {
  current: string
  title: string
  subtitle?: ReactNode
  backHref?: string
  actions?: ReactNode
}) {
  return (
    <header className={styles.header}>
      <div className={styles.headerText}>
        <nav aria-label="Você está em">
          <ol className={styles.breadcrumb}>
            <li><Link href="/goals">Objetivos</Link></li>
            <li aria-hidden="true"><ChevronRight size={14} strokeWidth={1.75} /></li>
            <li aria-current="page">{current}</li>
          </ol>
        </nav>
        <div className={styles.headerRow}>
          <Link href={backHref} className={styles.backIcon} aria-label="Voltar">
            <ArrowLeft size={20} strokeWidth={1.75} aria-hidden="true" />
          </Link>
          <div className={styles.headerText}>
            <h1 className={accountStyles.pageTitle}>{title}</h1>
            {subtitle ? <p className={styles.sectionNote}>{subtitle}</p> : null}
          </div>
        </div>
      </div>
      {actions ? <div className={styles.headerActions}>{actions}</div> : null}
    </header>
  )
}

export function LoadingPanel({ label, rows = 3 }: { label: string; rows?: number }) {
  return (
    <div className={styles.section} role="status" aria-busy="true">
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }, (_, i) => <Skeleton key={i} height="row" />)}
    </div>
  )
}

export function ErrorPanel({ title, message, onRetry }: { title: string; message: string; onRetry?: () => void }) {
  const id = `error-${title.replace(/\W+/g, '-').toLowerCase()}`
  return (
    <section className={styles.section} role="alert" aria-labelledby={id}>
      <h2 id={id} className={styles.sectionTitle}>
        <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" /> {title}
      </h2>
      <p className={styles.sectionNote}>{message}</p>
      {onRetry ? <div><Button variant="secondary" onClick={onRetry}>Tentar novamente</Button></div> : null}
    </section>
  )
}

/** Toast não bloqueante (05-states "Success"): anunciado por `role="status"`, fecha sozinho só por ação da pessoa. */
export function Toast({ message, action, onClose }: { message: string; action?: { href: string; label: string }; onClose: () => void }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div ref={ref} className={styles.toast} role="status">
      <CircleCheck size={20} strokeWidth={1.75} aria-hidden="true" />
      <p className={styles.toastText}>{message}</p>
      {action ? <Link href={action.href} className={styles.toastLink}>{action.label}</Link> : null}
      <button type="button" className={styles.toastClose} onClick={onClose} aria-label="Fechar aviso">
        <X size={18} strokeWidth={1.75} aria-hidden="true" />
      </button>
    </div>
  )
}
