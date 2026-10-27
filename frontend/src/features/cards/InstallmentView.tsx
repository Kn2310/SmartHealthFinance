'use client'

import { ChevronRight, Sparkles } from 'lucide-react'
import Link from 'next/link'
import { MonthBars } from '@/components/charts/MonthBars'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { magnitude } from '@/components/charts/geometry'
import { formatLongDate } from '@/lib/format/date'
import type { Card, InstallmentPlan } from './api'
import { installmentStatusLabel, invoiceFullName, invoiceName, planStatus, shortMonth } from './format'
import { useCard, useInstallmentPlan, useUtilization } from './hooks'
import { Badge } from './parts'
import { BackLink, ErrorPanel, LoadingPanel, NotFoundPanel, PartialNotice } from './states'
import { CardNotFound } from './CardDetailView'
import styles from './Cards.module.css'

/**
 * D-Installment / M-Installment: andamento em blocos (pagas, atual, futuras, canceladas e antes do acompanhamento),
 * dados da compra, impacto e limite reservado. Percentuais e valores vêm do backend (ADR-0029 §5); "% da renda" não
 * aparece. O parcelamento é considerado em Credit e Debt (ADR-0027).
 */
export function InstallmentView({ cardId, transactionId }: { cardId: string | null; transactionId: string | null }) {
  if (!cardId) return <CardNotFound />
  if (!transactionId) return <PlanNotFound cardId={cardId} />
  return <Installment cardId={cardId} transactionId={transactionId} />
}

function PlanNotFound({ cardId }: { cardId: string }) {
  return (
    <div className={styles.page}>
      <BackLink href={`/cards/${cardId}?tab=installments`}>Parcelamentos</BackLink>
      <NotFoundPanel
        title="Parcelamento não encontrado"
        text="Esta compra não existe neste cartão ou não está disponível para você."
        href={`/cards/${cardId}?tab=installments`}
        action="Ver parcelamentos do cartão"
      />
    </div>
  )
}

function Installment({ cardId, transactionId }: { cardId: string; transactionId: string }) {
  const card = useCard(cardId)
  const plan = useInstallmentPlan(cardId, transactionId)

  if (plan.status === 'loading') {
    return (
      <div className={styles.page}>
        <BackLink href={`/cards/${cardId}?tab=installments`}>Parcelamentos</BackLink>
        <LoadingPanel label="Carregando o parcelamento…" rows={4} />
      </div>
    )
  }
  if (plan.status === 'error' || !plan.data) {
    const code = plan.status === 'error' ? plan.failure.code : 'UNAVAILABLE'
    if (code === 'CARD_NOT_FOUND') return <CardNotFound />
    if (code === 'TRANSACTION_NOT_FOUND') return <PlanNotFound cardId={cardId} />
    return (
      <div className={styles.page}>
        <BackLink href={`/cards/${cardId}?tab=installments`}>Parcelamentos</BackLink>
        <ErrorPanel title="Não foi possível carregar o parcelamento" code={code} onRetry={plan.retry} />
      </div>
    )
  }
  return <Loaded cardId={cardId} card={card.status === 'success' ? card.data : null} plan={plan.data} />
}

function Loaded({ cardId, card, plan }: { cardId: string; card: Card | null; plan: InstallmentPlan }) {
  const status = planStatus(plan.status)
  const current = plan.installments.find((item) => item.status === 'CURRENT')
  const beforeTracking = plan.installments.filter((item) => item.status === 'BEFORE_TRACKING').length

  return (
    <div className={styles.page}>
      <nav aria-label="Você está em" className={styles.breadcrumb}>
        <Link href="/cards">Cartões</Link>
        <span aria-hidden="true">›</span>
        <Link href={`/cards/${cardId}`}>{card?.nickname ?? 'Cartão'}</Link>
        <span aria-hidden="true">›</span>
        <span aria-current="page">Parcelamento</span>
      </nav>
      <header className={styles.header}>
        <div className={styles.headerText}>
          <h1 className={styles.title}>
            {plan.description} · {plan.count}× de <Money value={plan.installmentAmount} />
          </h1>
          <p className={styles.subtitle}>
            Comprado em {formatLongDate(plan.purchasedOn)}
            {card ? ` · ${card.nickname}` : ''}
          </p>
          <Badge tone={status.tone}>{status.label}</Badge>
        </div>
        <div className={styles.headerActions}>
          <span className={styles.caption} aria-disabled="true">
            Perguntar ao Copilot · Em breve
          </span>
          <Button href={current ? `/cards/${cardId}/invoices/${current.dueMonth}` : `/cards/${cardId}/invoice`} variant="secondary">
            Ver fatura
          </Button>
        </div>
      </header>
      {card === null ? <PartialNotice>Não conseguimos carregar os dados do cartão. O parcelamento continua abaixo.</PartialNotice> : null}

      <section className={styles.panel} aria-labelledby="progress-title">
        <div className={styles.blockHead}>
          <h2 id="progress-title" className={styles.sectionTitle}>
            Andamento
          </h2>
          <div className={styles.legend} aria-hidden="true">
            <span className={styles.legendItem}><span className={styles.swatch} /> Paga</span>
            <span className={styles.legendItem}><span className={`${styles.swatch} ${styles.swatchCurrent}`} /> Nesta fatura</span>
            <span className={styles.legendItem}><span className={`${styles.swatch} ${styles.swatchFuture}`} /> Futura</span>
          </div>
        </div>
        <ol className={styles.blocks} aria-label="Parcelas">
          {plan.installments.map((item) => (
            <li key={item.number} className={`${styles.block} ${styles[`block-${item.status}`] ?? ''}`}>
              <span className={styles.blockBox}>
                <span className="sr-only">Parcela </span>
                {item.number}
              </span>
              <span className={styles.blockMeta}>{shortMonth(item.dueMonth)}</span>
              <span className={styles.blockMeta}>{installmentStatusLabel(item.status)}</span>
              {magnitude(item.cancelledAmount) > 0 && item.status !== 'CANCELLED' ? (
                <span className={styles.blockMeta}>
                  <Money value={item.cancelledAmount} /> estornado
                </span>
              ) : null}
            </li>
          ))}
        </ol>
        <dl className={styles.facts}>
          <div>
            <dt>Valor total</dt>
            <dd>
              <Money value={plan.total} />
            </dd>
          </div>
          <div>
            <dt>Já pago</dt>
            <dd>
              <Money value={plan.paidAmount} /> · {plan.paidCount} {plan.paidCount === 1 ? 'parcela' : 'parcelas'}
            </dd>
          </div>
          {plan.currentInvoiceShare && plan.currentNumber ? (
            <>
              <div>
                <dt>Nesta fatura</dt>
                <dd>
                  <Money value={plan.currentInvoiceShare.installment} /> · parcela {plan.currentNumber}
                </dd>
              </div>
            </>
          ) : null}
          <div>
            <dt>Em faturas futuras</dt>
            <dd>
              <Money value={plan.remainingAfterCurrent} />
            </dd>
          </div>
          <div>
            <dt>Última parcela</dt>
            <dd>{invoiceFullName(plan.lastDueMonth)}</dd>
          </div>
        </dl>
        {beforeTracking > 0 ? (
          <p className={styles.caption}>
            {beforeTracking === 1 ? '1 parcela caiu' : `${beforeTracking} parcelas caíram`} antes do acompanhamento do
            cartão no app.
          </p>
        ) : null}
      </section>

      <section className={styles.page} aria-labelledby="impact-title">
        <div>
          <h2 id="impact-title" className={styles.sectionTitle}>
            Impacto financeiro
          </h2>
          <p className={styles.caption}>Como este parcelamento afeta sua vida financeira hoje e nos próximos meses.</p>
        </div>
        <div className={styles.impact}>
          <div className={styles.metric}>
            {plan.currentInvoiceShare ? (
              <>
                <span className={styles.impactValue}>{plan.currentInvoiceShare.percent}%</span>
                <span className={styles.rowTitle}>da fatura atual</span>
                <span className={styles.muted}>
                  <Money value={plan.currentInvoiceShare.installment} /> de{' '}
                  <Money value={plan.currentInvoiceShare.invoiceAmount} /> da{' '}
                  {invoiceName(plan.currentInvoiceShare.dueMonth).toLowerCase()}.
                </span>
              </>
            ) : (
              <>
                <span className={styles.rowTitle}>Fatura atual</span>
                <span className={styles.muted}>Nenhuma parcela deste parcelamento na fatura aberta.</span>
              </>
            )}
          </div>
          <UtilizationImpact cardId={cardId} card={card} />
        </div>
      </section>

      <div className={`${styles.grid} ${styles.gridWide}`}>
        {plan.reservedLimit.length > 0 ? (
          <section className={styles.panel} aria-labelledby="reserved-title">
            <div className={styles.blockHead}>
              <h2 id="reserved-title" className={styles.rowTitle}>
                Limite reservado por este parcelamento
              </h2>
              <Badge tone="success">Confirmado</Badge>
            </div>
            <MonthBars
              title="Limite reservado por este parcelamento"
              series={[{ label: 'Limite reservado', tone: 'expense' }]}
              data={plan.reservedLimit.map((point) => ({ month: point.dueMonth, partial: false, values: [point.amount] }))}
            />
            <p className={styles.caption}>A cada fatura paga, a parcela dela volta a ficar disponível no limite.</p>
          </section>
        ) : null}
        <aside className={styles.insight} aria-labelledby="health-title">
          <span className={styles.insightLabel}>
            <Sparkles size={14} strokeWidth={1.75} aria-hidden="true" /> Na sua saúde financeira
          </span>
          <p id="health-title" className={styles.muted}>
            Este parcelamento é considerado em <strong>Credit</strong>, pela utilização do limite, e em{' '}
            <strong>Debt</strong>, como compromisso futuro.
          </p>
          <Link href="/home/health" className={styles.link}>
            Ver saúde financeira
            <ChevronRight size={16} strokeWidth={1.75} aria-hidden="true" />
          </Link>
        </aside>
      </div>
    </div>
  )
}

/** "Utilização sem parcelas futuras" do cartão (ADR-0026 §6), com o percentual de hoje para comparar. */
function UtilizationImpact({ cardId, card }: { cardId: string; card: Card | null }) {
  const utilization = useUtilization(cardId)
  if (utilization.status === 'loading') {
    return (
      <div className={styles.metric} role="status">
        <span className={styles.caption}>Carregando a utilização…</span>
      </div>
    )
  }
  if (utilization.status === 'error' || !utilization.data) {
    return (
      <div className={styles.metric}>
        <PartialNotice onRetry={utilization.retry}>Não conseguimos carregar a utilização do cartão.</PartialNotice>
      </div>
    )
  }
  const data = utilization.data
  return (
    <div className={styles.metric}>
      <span className={styles.impactValue}>{data.utilizationWithoutFuturePercent}%</span>
      <span className={styles.rowTitle}>utilização sem parcelas futuras</span>
      <span className={styles.muted}>
        Hoje {card ? `o ${card.nickname}` : 'o cartão'} está em {data.utilizationPercent}%, incluindo{' '}
        <Money value={data.composition.futureInstallments} /> de parcelas futuras reservadas no limite.
      </span>
    </div>
  )
}
