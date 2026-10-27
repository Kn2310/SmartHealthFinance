'use client'

import { ChevronRight, Pencil } from 'lucide-react'
import Link from 'next/link'
import { ScoreLine } from '@/components/charts/ScoreLine'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { magnitude } from '@/components/charts/geometry'
import { formatShortDate } from '@/lib/format/date'
import type { Card, InstallmentPlan, InvoiceSummary } from './api'
import {
  calculationNote,
  cycleText,
  invoiceFullName,
  invoiceStatus,
  monthName,
  planStatus,
  shortMonth,
} from './format'
import { useCard, useInstallmentPlans, useInvoices, useUtilization, useUtilizationHistory } from './hooks'
import { Badge, CardVisual, UtilizationRange } from './parts'
import { BackLink, ErrorPanel, LoadingPanel, NotFoundPanel, PartialNotice } from './states'
import styles from './Cards.module.css'

export type CardTab = 'overview' | 'invoices' | 'installments'

const TABS: { tab: CardTab; label: string }[] = [
  { tab: 'overview', label: 'Visão geral' },
  { tab: 'invoices', label: 'Faturas' },
  { tab: 'installments', label: 'Parcelamentos' },
]

/** Faturas recentes na Visão geral (D-CardDetail: "últimos 4 meses"). */
const RECENT_INVOICES = 4

/**
 * D-CardDetail (`/cards/:id`): abas Visão geral, Faturas e Parcelamentos. Limite, ciclo, utilização com composição e
 * evolução (ADR-0029 §2), faturas recentes e parcelamentos. A média das faturas e a "fonte" do cartão não aparecem
 * (não há dado no backend; ADR-0029 §8).
 */
export function CardDetailView({ id, tab }: { id: string | null; tab: CardTab }) {
  if (!id) return <CardNotFound />
  return <CardDetail id={id} tab={tab} />
}

export function CardNotFound() {
  return (
    <div className={styles.page}>
      <BackLink href="/cards">Cartões</BackLink>
      <NotFoundPanel
        title="Cartão não encontrado"
        text="Este cartão não existe ou não está disponível para você. Nenhum dado foi alterado."
        href="/cards"
        action="Ver meus cartões"
      />
    </div>
  )
}

function CardDetail({ id, tab }: { id: string; tab: CardTab }) {
  const card = useCard(id)
  if (card.status === 'loading') {
    return (
      <div className={styles.page}>
        <BackLink href="/cards">Cartões</BackLink>
        <LoadingPanel label="Carregando o cartão…" rows={4} />
      </div>
    )
  }
  if (card.status === 'error' || !card.data) {
    if (card.status === 'error' && card.failure.code === 'CARD_NOT_FOUND') return <CardNotFound />
    return (
      <div className={styles.page}>
        <BackLink href="/cards">Cartões</BackLink>
        <ErrorPanel
          title="Não foi possível carregar este cartão"
          code={card.status === 'error' ? card.failure.code : 'UNAVAILABLE'}
          onRetry={card.retry}
        />
      </div>
    )
  }
  const data = card.data
  const archived = data.status === 'ARCHIVED'

  return (
    <div className={styles.page}>
      <nav aria-label="Você está em" className={styles.breadcrumb}>
        <Link href="/cards">Cartões</Link>
        <span aria-hidden="true">›</span>
        <span aria-current="page">{data.nickname}</span>
      </nav>
      <header className={styles.header}>
        <div className={styles.headerText}>
          <h1 className={styles.title}>{data.nickname}</h1>
          <p className={styles.subtitle}>
            {[data.institutionName, `final ${data.last4}`, cycleText(data)].filter(Boolean).join(' · ')}
          </p>
          {archived ? <Badge tone="neutral">Arquivado</Badge> : null}
        </div>
        <div className={styles.headerActions}>
          <Button href={`/cards/${data.id}/edit`} variant="secondary">
            <Pencil size={16} strokeWidth={1.75} aria-hidden="true" />
            {archived ? 'Dados e reativação' : 'Editar cartão'}
          </Button>
          <Button href={`/cards/${data.id}/invoice`}>Ver fatura atual</Button>
        </div>
      </header>

      {archived ? (
        <p className={styles.info} role="note">
          Cartão arquivado: não recebe compras nem pagamentos novos. Faturas e parcelamentos continuam disponíveis.
        </p>
      ) : null}

      <nav aria-label="Seções do cartão" className={styles.tabs}>
        {TABS.map((item) => (
          <Link
            key={item.tab}
            href={item.tab === 'overview' ? `/cards/${data.id}` : `/cards/${data.id}?tab=${item.tab}`}
            className={styles.tab}
            aria-current={item.tab === tab ? 'page' : undefined}
          >
            {item.label}
          </Link>
        ))}
      </nav>

      {tab === 'overview' ? <Overview card={data} /> : null}
      {tab === 'invoices' ? <InvoicesTab card={data} /> : null}
      {tab === 'installments' ? <InstallmentsTab card={data} /> : null}
    </div>
  )
}

function Overview({ card }: { card: Card }) {
  return (
    <>
      <div className={`${styles.grid} ${styles.gridWide}`}>
        <UtilizationPanel card={card} />
        <section className={styles.panel} aria-label="Dados do cartão">
          <CardVisual card={card} />
          <dl className={styles.facts}>
            <dt>Limite</dt>
            <dd>
              <Money value={card.limit} />
            </dd>
            <dt>Fechamento</dt>
            <dd>Dia {String(card.closingDay).padStart(2, '0')}</dd>
            <dt>Vencimento</dt>
            <dd>Dia {String(card.dueDay).padStart(2, '0')}</dd>
            <dt>Acompanhado desde</dt>
            <dd>{invoiceFullName(card.trackingStartDueMonth)}</dd>
          </dl>
        </section>
      </div>
      <div className={styles.grid}>
        <InvoicesPanel card={card} limit={RECENT_INVOICES} />
        <InstallmentsPanel card={card} />
      </div>
    </>
  )
}

function UtilizationPanel({ card }: { card: Card }) {
  const utilization = useUtilization(card.id)
  const history = useUtilizationHistory(card.id)

  if (utilization.status === 'loading') return <LoadingPanel label="Carregando a utilização…" />
  if (utilization.status === 'error' || !utilization.data) {
    return (
      <ErrorPanel
        title="Não foi possível carregar a utilização"
        code={utilization.status === 'error' ? utilization.failure.code : 'UNAVAILABLE'}
        onRetry={utilization.retry}
      />
    )
  }
  const data = utilization.data
  const points = history.status === 'success' && history.data ? history.data.points : []
  // "era X% em {mês}": o ponto do fim do mês anterior, como no D12 (ADR-0028 §3). O percentual vem do backend.
  const previous = points.length >= 2 ? points[points.length - 2] : undefined
  const { previousOutstanding, currentInvoice, futureInstallments } = data.composition

  return (
    <section className={styles.panel} aria-labelledby="utilization-title">
      <div className={styles.blockHead}>
        <h2 id="utilization-title" className={styles.sectionTitle}>
          Utilização
        </h2>
      </div>
      <p>
        <span className={styles.impactValue}>{data.utilizationPercent}%</span>{' '}
        {previous ? (
          <span className={styles.muted}>
            era {previous.utilizationPercent}% no fim de {monthName(previous.date.slice(0, 7))}
          </span>
        ) : null}
      </p>
      <UtilizationRange utilization={data} label={`Utilização de ${data.utilizationPercent}% do limite`} />
      <div className={styles.legend}>
        <span className={styles.legendItem}>
          <span className={styles.swatch} aria-hidden="true" /> Fatura atual · <Money value={currentInvoice} />
        </span>
        {magnitude(previousOutstanding) > 0 ? (
          <span className={styles.legendItem}>
            <span className={styles.swatch} aria-hidden="true" /> Faturas anteriores em aberto ·{' '}
            <Money value={previousOutstanding} />
          </span>
        ) : null}
        <span className={styles.legendItem}>
          <span className={`${styles.swatch} ${styles.swatchFuture}`} aria-hidden="true" /> Parcelas futuras ·{' '}
          <Money value={futureInstallments} />
        </span>
      </div>
      <p className={styles.muted}>Sem as parcelas futuras, a utilização seria de {data.utilizationWithoutFuturePercent}%.</p>

      <h3 className={styles.rowTitle}>Evolução da utilização</h3>
      {history.status === 'loading' ? <p className={styles.caption}>Carregando a evolução…</p> : null}
      {history.status === 'error' ? (
        <PartialNotice onRetry={history.retry}>Não conseguimos carregar a evolução. A utilização de hoje está acima.</PartialNotice>
      ) : null}
      {points.length >= 2 ? (
        <ScoreLine
          title="Evolução da utilização"
          valueLabel="Utilização"
          unit="%"
          data={points.map((point) => ({
            month: point.date.slice(0, 7),
            score: point.utilizationPercent,
            estimated: false,
            partial: point.current,
          }))}
          caption="Fim de cada mês e hoje, com o limite atual do cartão."
        />
      ) : history.status === 'success' ? (
        <p className={styles.caption}>A evolução aparece depois do primeiro mês acompanhado.</p>
      ) : null}
      <p className={styles.caption}>{calculationNote(data.calculationVersion)}</p>
    </section>
  )
}

function InvoiceRow({ card, invoice }: { card: Card; invoice: InvoiceSummary }) {
  const status = invoiceStatus(invoice.status)
  return (
    <li className={styles.row}>
      <span className={styles.rowMain}>
        <Link href={`/cards/${card.id}/invoices/${invoice.dueMonth}`} className={styles.rowTitle}>
          {invoiceFullName(invoice.dueMonth)}
        </Link>
        <span className={styles.caption}>
          Vence em {formatShortDate(invoice.dueDate)}
          {invoice.partial ? ' · parcial, pode mudar até o fechamento' : ''}
        </span>
      </span>
      <span className={styles.rowSide}>
        <span className={styles.rowValue}>
          <Money value={invoice.amount} />
        </span>
        <Badge tone={status.tone}>{status.label}</Badge>
      </span>
    </li>
  )
}

function InvoicesPanel({ card, limit }: { card: Card; limit?: number }) {
  const invoices = useInvoices(card.id)
  return (
    <section className={styles.panel} aria-labelledby="invoices-title">
      <div className={styles.blockHead}>
        <h2 id="invoices-title" className={styles.sectionTitle}>
          Faturas
        </h2>
        <Link href={`/cards/${card.id}/invoices`} className={styles.link}>
          Próximas faturas
          <ChevronRight size={16} strokeWidth={1.75} aria-hidden="true" />
        </Link>
      </div>
      {invoices.status === 'loading' ? <p className={styles.caption}>Carregando faturas…</p> : null}
      {invoices.status === 'error' ? (
        <PartialNotice onRetry={invoices.retry}>Não conseguimos carregar as faturas agora.</PartialNotice>
      ) : null}
      {invoices.status === 'success' && invoices.data ? (
        invoices.data.items.length === 0 ? (
          <p className={styles.caption}>Nenhuma fatura com lançamentos ainda.</p>
        ) : (
          <ul className={styles.list}>
            {(limit ? invoices.data.items.slice(0, limit) : invoices.data.items).map((invoice) => (
              <InvoiceRow key={invoice.dueMonth} card={card} invoice={invoice} />
            ))}
          </ul>
        )
      ) : null}
    </section>
  )
}

function PlanRow({ card, plan }: { card: Card; plan: InstallmentPlan }) {
  const status = planStatus(plan.status)
  return (
    <li className={styles.row}>
      <span className={styles.rowMain}>
        <Link href={`/cards/${card.id}/installments/${plan.transactionId}`} className={styles.rowTitle}>
          {plan.description} · {plan.count}× de <Money value={plan.installmentAmount} />
        </Link>
        <span className={styles.caption}>
          {plan.status === 'ACTIVE'
            ? `${plan.currentNumber ? `Parcela ${plan.currentNumber} de ${plan.count} na fatura atual · ` : ''}termina na fatura de ${shortMonth(plan.lastDueMonth)}`
            : `Última parcela na fatura de ${shortMonth(plan.lastDueMonth)}`}
        </span>
      </span>
      <span className={styles.rowSide}>
        <Badge tone={status.tone}>{status.label}</Badge>
      </span>
    </li>
  )
}

function InstallmentsPanel({ card }: { card: Card }) {
  const plans = useInstallmentPlans(card.id, 'ALL')
  return (
    <section className={styles.panel} aria-labelledby="plans-title">
      <h2 id="plans-title" className={styles.sectionTitle}>
        Parcelamentos
      </h2>
      {plans.status === 'loading' ? <p className={styles.caption}>Carregando parcelamentos…</p> : null}
      {plans.status === 'error' ? (
        <PartialNotice onRetry={plans.retry}>Não conseguimos carregar os parcelamentos agora.</PartialNotice>
      ) : null}
      {plans.status === 'success' && plans.data ? (
        plans.data.items.length === 0 ? (
          <p className={styles.caption}>Nenhuma compra parcelada neste cartão.</p>
        ) : (
          <ul className={styles.list}>
            {plans.data.items.map((plan) => (
              <PlanRow key={plan.transactionId} card={card} plan={plan} />
            ))}
          </ul>
        )
      ) : null}
    </section>
  )
}

function InvoicesTab({ card }: { card: Card }) {
  return <InvoicesPanel card={card} />
}

function InstallmentsTab({ card }: { card: Card }) {
  return <InstallmentsPanel card={card} />
}

