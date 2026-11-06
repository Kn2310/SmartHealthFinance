'use client'

import { CalendarClock, CreditCard, Repeat } from 'lucide-react'
import Link from 'next/link'
import type { ReactNode } from 'react'
import { Card } from '@/components/ui/Card'
import { ProjectedValue } from '@/components/ui/ProjectedValue'
import { Skeleton } from '@/components/ui/Skeleton'
import { useForecastUpcoming } from '@/features/forecast/api'
import {
  estimateStatus,
  NOTE_INSUFFICIENT_HISTORY,
  NOTE_IN_PREPARATION,
} from '@/features/forecast/format'
import { StatusNote } from '@/features/forecast/parts'
import type { ForecastUpcomingEvent, Overview } from '@/lib/api/types'
import { formatShortDate } from '@/lib/format/date'
import { FactEvents, monthName, STATUS } from './CardBills'
import styles from './CardBills.module.css'

type Bills = Overview['cards']
type Accounts = Overview['accounts']

const SUBTITLE = 'Próximos 30 dias · faturas e recorrências'

function RecurringLink() {
  return (
    <Link href="/recurring" className={styles.link}>
      Gerenciar recorrências
    </Link>
  )
}

/**
 * "Próximos eventos" (D-Home, ADR-0033 §2): a agenda de `/forecast/upcoming`, fatos e estimados na ordem da API, cada um
 * com o seu selo. Sem snapshot (`NOT_BUILT`) ou com erro no Forecast, cai nos eventos de fatura do Overview (só fatos)
 * com a nota "Estimativas em preparação.". Sem cartão ativo e sem agenda, o bloco não aparece.
 */
export function UpcomingEvents({ bills, accounts }: { bills: Bills; accounts: Accounts }) {
  const forecast = useForecastUpcoming()

  if (forecast.status === 'loading') {
    if (!bills) return null
    return (
      <Card id="events" title="Próximos eventos" subtitle={SUBTITLE}>
        <div className={styles.loading} role="status" aria-busy="true">
          <span className="sr-only">Carregando os próximos eventos…</span>
          <Skeleton width="lg" />
          <Skeleton width="md" />
        </div>
      </Card>
    )
  }

  const data = forecast.status === 'success' ? forecast.data : null
  const status = data ? estimateStatus(data.state, data.freshness) : 'NOT_BUILT'

  // Fallback aprovado: sem snapshot ou com erro, só os fatos do Overview.
  if (!data || status === 'NOT_BUILT') {
    if (!bills) return null
    return (
      <Card id="events" title="Próximos eventos" subtitle="Próximos 30 dias · faturas dos cartões" action={<RecurringLink />}>
        <FactEvents bills={bills} />
        <p className={styles.note}>{NOTE_IN_PREPARATION}</p>
      </Card>
    )
  }

  if (data.events.length === 0 && !bills) return null

  return (
    <Card id="events" title="Próximos eventos" subtitle={SUBTITLE} action={<RecurringLink />}>
      {data.events.length > 0 ? (
        <ul className={styles.events}>
          {data.events.map((event, index) => (
            <ForecastEventItem key={`${event.kind}-${event.date}-${event.seriesId ?? event.channel.id}-${index}`}
              event={event} bills={bills} accounts={accounts} />
          ))}
        </ul>
      ) : (
        <p className={styles.empty}>Nenhum evento nos próximos 30 dias.</p>
      )}
      {status === 'RECALCULATING' ? (
        <div className={styles.footer}><StatusNote status={status} /></div>
      ) : status === 'INSUFFICIENT_HISTORY' ? (
        <p className={styles.note}>{NOTE_INSUFFICIENT_HISTORY}</p>
      ) : null}
    </Card>
  )
}

/** Mesmas tags de status dos fatos do Overview (Parcial vem do selo do valor). */
const INVOICE_TAG = {
  INVOICE_OPEN: STATUS.CLOSED,
  INVOICE_OVERDUE: STATUS.OVERDUE,
} as const

function ForecastEventItem({ event, bills, accounts }: {
  event: ForecastUpcomingEvent
  bills: Bills
  accounts: Accounts
}) {
  const invoice = event.kind.startsWith('INVOICE_')
  const matched = invoice
    ? bills?.upcomingEvents.find((e) => e.card.id === event.channel.id && e.date === event.date)
    : undefined
  const Icon = invoice ? (matched?.kind === 'INVOICE_CLOSING' ? CreditCard : CalendarClock) : Repeat
  const tag = event.kind === 'INVOICE_OPEN' || event.kind === 'INVOICE_OVERDUE' ? INVOICE_TAG[event.kind] : null

  let title: string
  let meta: ReactNode
  if (invoice) {
    const name = event.label ?? matched?.card.nickname ?? 'cartão'
    title = `${matched ? (matched.kind === 'INVOICE_CLOSING' ? 'Fechamento' : 'Vencimento') + ' da fatura' : 'Fatura'} ${name}`
    meta = [
      formatShortDate(event.date),
      matched ? `Cartão •••• ${matched.card.last4}` : 'Cartão',
      matched ? `Fatura de ${monthName(matched.dueMonth)}` : null,
    ].filter(Boolean).join(' · ')
  } else {
    title = event.label ?? (event.kind === 'RECURRING_INCOME' ? 'Receita recorrente' : 'Despesa recorrente')
    meta = `${formatShortDate(event.date)} · Recorrente · ${channelName(event, bills, accounts)}`
  }

  return (
    <li className={styles.event}>
      <span className={styles.tile} aria-hidden="true">
        <Icon size={18} strokeWidth={1.75} />
      </span>
      <span className={styles.text}>
        <span className={styles.title}>{title}</span>
        <span className={styles.meta}>{meta}</span>
      </span>
      <span className={styles.side}>
        <ProjectedValue value={event.value} range={event.value.basis === 'ESTIMATED'} className={styles.endAligned} />
        {tag ? (
          <span className={styles.tags}>
            <span className={`${styles.tag} ${tag.className}`}>{tag.label}</span>
          </span>
        ) : null}
        {event.closingEstimate ? (
          <span className={styles.closing}>
            <span>no fechamento</span>
            <ProjectedValue value={event.closingEstimate} badge={false} className={styles.endAligned} />
          </span>
        ) : null}
      </span>
    </li>
  )
}

function channelName(event: ForecastUpcomingEvent, bills: Bills, accounts: Accounts): string {
  if (event.channel.kind === 'ACCOUNT') {
    return accounts.find((a) => a.id === event.channel.id)?.name ?? 'conta'
  }
  const card = bills?.upcomingEvents.find((e) => e.card.id === event.channel.id)?.card
  return card ? `Cartão ${card.nickname}` : 'cartão'
}
