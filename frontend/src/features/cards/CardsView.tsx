'use client'

import { ChevronRight, CreditCard, Layers, Plus, Sparkles } from 'lucide-react'
import Link from 'next/link'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { useInsightList } from '@/features/insights/api'
import { useCards } from '@/features/transactions/hooks'
import { formatShortDate } from '@/lib/format/date'
import type { CardSummary } from './api'
import { calculationNote, cardLabel, cycleText, invoiceName, invoiceStatus, monthName, plural, shortMonth } from './format'
import { useCardsSummary, useInstallmentPlans } from './hooks'
import { Badge, CardVisual, UtilizationRange } from './parts'
import { ErrorPanel, LoadingPanel, PartialNotice } from './states'
import styles from './Cards.module.css'

/**
 * D-Cards / M-Cards (`/cards`): limite total, utilizado com parcelas e faturas abertas; um bloco por cartão ativo
 * com a fatura atual, a utilização (trecho tracejado = parcelas futuras) e o compromisso da próxima fatura; o insight
 * de Cartões quando houver; parcelamentos ativos. Tudo calculado no backend (ADR-0026 §3); a dívida não entra no
 * saldo total (ADR-0025 §4).
 */
export function CardsView() {
  const summary = useCardsSummary()
  const allCards = useCards()
  const archived = allCards.status === 'success' ? allCards.data.filter((card) => card.status === 'ARCHIVED') : []

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div className={styles.headerText}>
          <h1 className={styles.title}>Cartões</h1>
          <p className={styles.subtitle}>Limites, faturas e parcelamentos em um só lugar.</p>
        </div>
        <Button href="/cards/new?from=cards" variant="secondary">
          <Plus size={18} strokeWidth={1.75} aria-hidden="true" />
          Adicionar cartão
        </Button>
      </header>

      {summary.status === 'loading' ? <LoadingPanel label="Carregando seus cartões…" rows={4} /> : null}
      {summary.status === 'error' ? (
        <ErrorPanel title="Não foi possível carregar seus cartões" code={summary.failure.code} onRetry={summary.retry} />
      ) : null}
      {summary.status === 'success' && summary.data ? (
        summary.data.cards.length === 0 ? (
          <FirstTime hasArchived={archived.length > 0} />
        ) : (
          <>
            <section className={styles.metrics} aria-label="Resumo dos cartões ativos">
              <div className={styles.metric}>
                <span className={styles.metricLabel}>Limite total</span>
                <span className={styles.metricValue}>
                  <Money value={summary.data.totalLimit} />
                </span>
                <span className={styles.caption}>{plural(summary.data.cards.length, 'cartão ativo', 'cartões ativos')}</span>
              </div>
              <div className={styles.metric}>
                <span className={styles.metricLabel}>Limite utilizado</span>
                <span className={styles.metricValue}>
                  <Money value={summary.data.utilized} />
                </span>
                <span className={styles.caption}>Inclui parcelas futuras</span>
              </div>
              <div className={styles.metric}>
                <span className={styles.metricLabel}>Faturas abertas</span>
                <span className={styles.metricValue}>
                  <Money value={summary.data.openInvoices} />
                </span>
                <span className={styles.caption}>
                  {summary.data.nextDueDate
                    ? `Próximo vencimento: ${formatShortDate(summary.data.nextDueDate)}`
                    : 'Nenhuma fatura em aberto'}
                </span>
              </div>
            </section>
            <p className={styles.caption}>
              A dívida dos cartões não entra no saldo total das contas. {calculationNote(summary.data.calculationVersion)}.
            </p>

            <CardsInsight />

            <section className={styles.page} aria-labelledby="my-cards">
              <h2 id="my-cards" className={styles.sectionTitle}>
                Meus cartões
              </h2>
              {summary.data.cards.map((item) => (
                <CardBlock key={item.card.id} item={item} asOf={summary.data!.asOf} />
              ))}
            </section>

            <section className={styles.panel} aria-labelledby="installments-title">
              <h2 id="installments-title" className={styles.sectionTitle}>
                Parcelamentos
              </h2>
              {summary.data.cards.map((item) => (
                <CardInstallments key={item.card.id} item={item} />
              ))}
            </section>
          </>
        )
      ) : null}

      {archived.length > 0 ? (
        <section className={styles.panel} aria-labelledby="archived-title">
          <h2 id="archived-title" className={styles.sectionTitle}>
            Arquivados
          </h2>
          <p className={styles.muted}>Cartões arquivados não recebem lançamentos novos. O histórico continua guardado.</p>
          <ul className={styles.list}>
            {archived.map((card) => (
              <li key={card.id} className={styles.row}>
                <span className={styles.rowMain}>
                  <Link href={`/cards/${card.id}`} className={styles.rowTitle}>
                    {cardLabel(card)}
                  </Link>
                </span>
                <Badge tone="neutral">Arquivado</Badge>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  )
}

function FirstTime({ hasArchived }: { hasArchived: boolean }) {
  return (
    <section className={`${styles.state} ${styles.empty}`} aria-labelledby="cards-empty">
      <span className={styles.stateIcon} aria-hidden="true">
        <CreditCard size={28} strokeWidth={1.75} />
      </span>
      <h2 id="cards-empty" className={styles.sectionTitle}>
        {hasArchived ? 'Nenhum cartão ativo' : 'Acompanhe seus cartões aqui'}
      </h2>
      <p className={styles.muted}>
        Cadastre um cartão com apelido, os 4 últimos dígitos, limite e datas. Depois lance compras ou importe a fatura
        para ver faturas, parcelas e utilização. Nunca pedimos o número completo nem o CVV.
      </p>
      <Button href="/cards/new?from=cards">
        <Plus size={18} strokeWidth={1.75} aria-hidden="true" />
        Adicionar cartão
      </Button>
    </section>
  )
}

/** O insight de Cartões mais relevante (ADR-0028), quando houver; sem ele, nada aparece. */
function CardsInsight() {
  const insights = useInsightList('CARDS')
  const top = insights.status === 'success' ? insights.data?.items[0] : undefined
  if (!top) return null
  return (
    <aside className={styles.insight} aria-labelledby="cards-insight">
      <span className={styles.insightLabel}>
        <Sparkles size={14} strokeWidth={1.75} aria-hidden="true" /> Insight
      </span>
      <p id="cards-insight" className={styles.insightTitle}>
        {top.title}
      </p>
      <p className={styles.muted}>{top.support}</p>
      <Link href={`/insights/${top.id}`} className={styles.link}>
        Entender
        <ChevronRight size={16} strokeWidth={1.75} aria-hidden="true" />
      </Link>
    </aside>
  )
}

function CardBlock({ item, asOf }: { item: CardSummary; asOf: string }) {
  const { card, currentInvoice, utilization } = item
  const status = invoiceStatus(currentInvoice.status)
  const titleId = `card-${card.id}`
  return (
    <article className={styles.cardBlock} aria-labelledby={titleId}>
      <CardVisual card={card} compact />
      <div className={styles.blockColumn}>
        <div className={styles.blockHead}>
          <h3 id={titleId} className={styles.muted}>
            {cardLabel(card)} · {invoiceName(currentInvoice.dueMonth, asOf.slice(0, 4))}
          </h3>
          <Badge tone={status.tone}>{status.label}</Badge>
        </div>
        <p className={styles.amount}>
          <Money value={currentInvoice.amount} />
        </p>
        <p className={styles.caption}>
          {currentInvoice.partial ? 'Valor até agora, pode mudar até o fechamento. ' : ''}
          Vence em {formatShortDate(currentInvoice.dueDate)} · fecha em {formatShortDate(currentInvoice.closingDate)}
        </p>
        <div className={styles.actions}>
          <Button href={`/cards/${card.id}/invoice`}>Ver fatura</Button>
          <Button href={`/cards/${card.id}`} variant="secondary">
            Detalhes do cartão
          </Button>
        </div>
      </div>
      <div className={styles.blockColumn}>
        <span className={styles.muted}>Utilização do limite</span>
        <UtilizationRange utilization={utilization} label={`Utilização de ${utilization.utilizationPercent}% do limite`} />
        <p className={styles.note}>
          <Layers size={16} strokeWidth={1.75} aria-hidden="true" />
          <span>
            Próxima fatura: <Money value={item.nextInvoiceCommitted} /> já comprometidos em parcelas · {cycleText(card)}
          </span>
        </p>
      </div>
    </article>
  )
}

/** Parcelamentos ativos de um cartão (valores do backend; nada é somado aqui). */
function CardInstallments({ item }: { item: CardSummary }) {
  const plans = useInstallmentPlans(item.card.id, 'ACTIVE')
  return (
    <div className={styles.blockColumn}>
      <div className={styles.blockHead}>
        <h3 className={styles.rowTitle}>{cardLabel(item.card)}</h3>
        <Link href={`/cards/${item.card.id}/invoices`} className={styles.link}>
          Próximas faturas
          <ChevronRight size={16} strokeWidth={1.75} aria-hidden="true" />
        </Link>
      </div>
      {plans.status === 'loading' ? <p className={styles.caption}>Carregando parcelamentos…</p> : null}
      {plans.status === 'error' ? (
        <PartialNotice onRetry={plans.retry}>Não conseguimos carregar os parcelamentos deste cartão.</PartialNotice>
      ) : null}
      {plans.status === 'success' && plans.data ? (
        plans.data.items.length === 0 ? (
          <p className={styles.caption}>Nenhum parcelamento em andamento.</p>
        ) : (
          <ul className={styles.list}>
            {plans.data.items.map((plan) => (
              <li key={plan.transactionId} className={styles.row}>
                <span className={styles.rowMain}>
                  <Link href={`/cards/${item.card.id}/installments/${plan.transactionId}`} className={styles.rowTitle}>
                    {plan.description} · {plan.count}× de <Money value={plan.installmentAmount} />
                  </Link>
                  <span className={styles.caption}>
                    <Money value={plan.remainingAfterCurrent} /> em parcelas futuras · última na fatura de{' '}
                    {monthName(plan.lastDueMonth)} ({shortMonth(plan.lastDueMonth)})
                  </span>
                </span>
              </li>
            ))}
          </ul>
        )
      ) : null}
    </div>
  )
}
