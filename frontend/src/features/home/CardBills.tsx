import { CalendarClock, CreditCard } from 'lucide-react'
import type { Overview } from '@/lib/api/types'
import { Card } from '@/components/ui/Card'
import { Money } from '@/components/ui/Money'
import { longMonthLabel } from '@/components/charts/geometry'
import { formatShortDate } from '@/lib/format/date'
import { plural } from './labels'
import styles from './CardBills.module.css'

type Bills = NonNullable<Overview['cards']>
type UpcomingEvent = Bills['upcomingEvents'][number]

const STATUS: Record<UpcomingEvent['status'], { label: string; className: string }> = {
  PARTIAL: { label: 'Parcial', className: styles.tagPartial! },
  CLOSED: { label: 'Em aberto', className: styles.tagClosed! },
  OVERDUE: { label: 'Vencida', className: styles.tagOverdue! },
}

const monthName = (dueMonth: string) => longMonthLabel(dueMonth).split(' de ')[0]

/**
 * "Faturas a pagar" (ADR-0006 §18, ADR-0025 §4): a dívida dos cartões ativos fica fora do saldo total, sem "saldo
 * líquido". Valores prontos do backend (razão de Cards); a UI só formata.
 */
export function InvoicesToPay({ bills }: { bills: Bills }) {
  const { nextDue } = bills
  const overdue = nextDue !== null && nextDue.date < bills.asOf
  return (
    <Card id="bills" title="Faturas a pagar" subtitle={`Fora do saldo total · ${plural(bills.activeCards, 'cartão', 'cartões')}`}>
      <p className={styles.value}>
        <Money value={bills.openInvoices} />
      </p>
      {nextDue ? (
        <p className={styles.caption}>
          {overdue ? 'Vencida desde' : 'Próximo vencimento:'} {formatShortDate(nextDue.date)} ·{' '}
          <Money value={nextDue.amount} />
        </p>
      ) : (
        <p className={styles.caption}>Nenhuma fatura em aberto.</p>
      )}
      <p className={styles.note}>Inclui a fatura atual, que pode mudar até o fechamento.</p>
    </Card>
  )
}

/**
 * "Próximos eventos" (D-Home) só com fatos: fechamento e vencimento das faturas nos próximos 30 dias e as vencidas
 * com saldo. Estimativas (recorrentes, valor previsto) ficam para o Forecast (M9).
 */
export function UpcomingEvents({ bills }: { bills: Bills }) {
  return (
    <Card id="events" title="Próximos eventos" subtitle="Próximos 30 dias · faturas dos cartões">
      {bills.upcomingEvents.length > 0 ? (
        <ul className={styles.events}>
          {bills.upcomingEvents.map((event) => (
            <EventItem key={`${event.card.id}-${event.kind}-${event.dueMonth}`} event={event} />
          ))}
        </ul>
      ) : (
        <p className={styles.empty}>Nenhum fechamento ou vencimento de fatura nos próximos 30 dias.</p>
      )}
    </Card>
  )
}

function EventItem({ event }: { event: UpcomingEvent }) {
  const closing = event.kind === 'INVOICE_CLOSING'
  const status = STATUS[event.status]
  const Icon = closing ? CreditCard : CalendarClock
  return (
    <li className={styles.event}>
      <span className={styles.tile} aria-hidden="true">
        <Icon size={18} strokeWidth={1.75} />
      </span>
      <span className={styles.text}>
        <span className={styles.title}>
          {closing ? 'Fechamento' : 'Vencimento'} da fatura {event.card.nickname}
        </span>
        <span className={styles.meta}>
          {formatShortDate(event.date)} · Cartão •••• {event.card.last4} · Fatura de {monthName(event.dueMonth)}
        </span>
      </span>
      <span className={styles.side}>
        <span className={styles.amount}>
          <Money value={event.amount} />
        </span>
        {status ? <span className={`${styles.tag} ${status.className}`}>{status.label}</span> : null}
      </span>
    </li>
  )
}
