'use client'

import { ChevronRight, Info, Layers, Sparkles } from 'lucide-react'
import Link from 'next/link'
import { useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { magnitude } from '@/components/charts/geometry'
import { useInsightDetail, useInsightList } from '@/features/insights/api'
import { confidence, figureValue } from '@/features/insights/format'
import { categoryNames, useCategories } from '@/features/transactions/hooks'
import { formatLongDate, formatShortDate } from '@/lib/format/date'
import type { Card, Invoice, InvoiceItem } from './api'
import {
  calculationNote,
  invoiceName,
  invoiceStatus,
  monthName,
  TRACKING_START_NOTE,
} from './format'
import { useCard, useFutureInvoices, useInvoice, useInvoiceItems } from './hooks'
import { Badge } from './parts'
import { BackLink, ErrorPanel, LoadingPanel, NotFoundPanel, PartialNotice } from './states'
import { CardNotFound } from './CardDetailView'
import styles from './Cards.module.css'

const PAGE_SIZE = 20

/**
 * D-Invoice / M-Invoice: a fatura de um mês de vencimento (ou a atual, com `dueMonth` nulo). Valor, estado, datas,
 * pago e em aberto vêm do backend (ADR-0025 §5/§7); o mínimo só quando informado no arquivo. A explicação ✦ reaproveita
 * o insight D11 desta fatura, sem IA (ADR-0029 §3); estimativa e "por categoria" não aparecem (ADR-0029 §4 e §6).
 */
export function InvoiceView({ cardId, dueMonth }: { cardId: string | null; dueMonth: string | null }) {
  if (!cardId) return <CardNotFound />
  return <InvoiceScreen cardId={cardId} dueMonth={dueMonth} />
}

function InvoiceScreen({ cardId, dueMonth }: { cardId: string; dueMonth: string | null }) {
  const card = useCard(cardId)
  const invoice = useInvoice(cardId, dueMonth)

  if (card.status === 'error' && card.failure.code === 'CARD_NOT_FOUND') return <CardNotFound />
  if (invoice.status === 'loading' || card.status === 'loading') {
    return (
      <div className={styles.page}>
        <BackLink href={`/cards/${cardId}`}>Cartão</BackLink>
        <LoadingPanel label="Carregando a fatura…" rows={5} />
      </div>
    )
  }
  if (invoice.status === 'error' || !invoice.data) {
    const code = invoice.status === 'error' ? invoice.failure.code : 'UNAVAILABLE'
    if (code === 'CARD_NOT_FOUND') return <CardNotFound />
    if (code === 'INVOICE_NOT_FOUND' || code === 'VALIDATION_FAILED') {
      return (
        <div className={styles.page}>
          <BackLink href={`/cards/${cardId}`}>Cartão</BackLink>
          <NotFoundPanel
            title="Fatura não encontrada"
            text="Não há fatura com lançamentos neste mês para este cartão. Veja a fatura atual ou as faturas do cartão."
            href={`/cards/${cardId}?tab=invoices`}
            action="Ver faturas do cartão"
          />
        </div>
      )
    }
    return (
      <div className={styles.page}>
        <BackLink href={`/cards/${cardId}`}>Cartão</BackLink>
        <ErrorPanel title="Não foi possível carregar a fatura" code={code} onRetry={invoice.retry} />
      </div>
    )
  }

  return <Loaded card={card.status === 'success' ? card.data : null} cardId={cardId} invoice={invoice.data} />
}

function Loaded({ card, cardId, invoice }: { card: Card | null; cardId: string; invoice: Invoice }) {
  const summary = invoice.invoice
  const year = invoice.asOf.slice(0, 4)
  const status = invoiceStatus(summary.status)
  const name = invoiceName(summary.dueMonth, year)
  const canPay = card?.status === 'ACTIVE' && summary.status !== 'FUTURE' && magnitude(summary.remaining) > 0

  return (
    <div className={styles.page}>
      <nav aria-label="Você está em" className={styles.breadcrumb}>
        <Link href="/cards">Cartões</Link>
        <span aria-hidden="true">›</span>
        <Link href={`/cards/${cardId}`}>{card?.nickname ?? 'Cartão'}</Link>
        <span aria-hidden="true">›</span>
        <span aria-current="page">{name}</span>
      </nav>
      <header className={styles.header}>
        <div className={styles.headerText}>
          <h1 className={styles.title}>{name}</h1>
          <p className={styles.subtitle}>{card ? `${card.nickname} · final ${card.last4}` : 'Cartão'}</p>
        </div>
        <div className={styles.headerActions}>
          <Badge tone={status.tone}>{status.label}</Badge>
          <Button href={`/cards/${cardId}/invoices`} variant="secondary">
            Próximas faturas
          </Button>
        </div>
      </header>

      {card === null ? <PartialNotice>Não conseguimos carregar os dados do cartão. A fatura continua abaixo.</PartialNotice> : null}
      {card && card.trackingStartDueMonth === summary.dueMonth ? (
        <p className={styles.info} role="note">
          <Info size={16} strokeWidth={1.75} aria-hidden="true" />
          <span>{TRACKING_START_NOTE}</span>
        </p>
      ) : null}

      <div className={`${styles.grid} ${styles.gridWide}`}>
        <section className={styles.panel} aria-labelledby="invoice-amount">
          <span id="invoice-amount" className={styles.metricLabel}>
            {summary.partial ? 'Valor até agora' : 'Valor da fatura'}
          </span>
          <p className={styles.amount}>
            <Money value={summary.amount} />
          </p>
          {summary.partial ? (
            <p className={styles.caption}>
              Parcial até {formatShortDate(invoice.asOf)}: pode mudar até o fechamento em{' '}
              {formatShortDate(summary.closingDate)}.
            </p>
          ) : null}
          <dl className={styles.facts}>
            <div>
              <dt>Vencimento</dt>
              <dd>{formatLongDate(summary.dueDate)}</dd>
            </div>
            {summary.effectiveDueDate !== summary.dueDate ? (
              <>
                <div>
                  <dt>Vencimento efetivo</dt>
                  <dd>{formatLongDate(summary.effectiveDueDate)}</dd>
                </div>
              </>
            ) : null}
            <div>
              <dt>Fechamento</dt>
              <dd>{formatShortDate(summary.closingDate)}</dd>
            </div>
            <div>
              <dt>Pagamento mínimo</dt>
              <dd>{summary.minimumPayment ? <Money value={summary.minimumPayment} /> : '—'}</dd>
            </div>
            <div>
              <dt>Compras</dt>
              <dd>
                <Money value={summary.charges} />
              </dd>
            </div>
            <div>
              <dt>Créditos e estornos</dt>
              <dd>
                <Money value={summary.credits} />
              </dd>
            </div>
            <div>
              <dt>Pago</dt>
              <dd>
                <Money value={summary.paid} />
              </dd>
            </div>
            <div>
              <dt>Em aberto</dt>
              <dd>
                <Money value={summary.remaining} />
              </dd>
            </div>
          </dl>
          <p className={styles.caption}>
            {summary.minimumPayment ? 'Pagamento mínimo informado no arquivo da fatura. ' : 'Pagamento mínimo: só quando informado no arquivo da fatura. '}
            {summary.datesSource === 'IMPORTED' ? 'Datas informadas no arquivo da fatura.' : 'Datas calculadas pelo ciclo do cartão.'}{' '}
            {summary.effectiveDueDate !== summary.dueDate ? 'Vencimento no fim de semana passa para o dia útil seguinte. ' : ''}
            {calculationNote(invoice.calculationVersion)}.
          </p>
          {canPay ? (
            <div className={styles.actions}>
              <Button href={`/transactions/new?type=CARD_PAYMENT&cardId=${cardId}`}>Registrar pagamento</Button>
            </div>
          ) : null}
        </section>

        <div className={styles.page}>
          <InvoiceExplanation cardId={cardId} dueMonth={summary.dueMonth} />
          {summary.status === 'OPEN' ? <NextInvoice cardId={cardId} year={year} /> : null}
        </div>
      </div>

      <InvoiceItems cardId={cardId} dueMonth={summary.dueMonth} itemCount={invoice.itemCount} />
    </div>
  )
}

/**
 * ✦ Explicação (07-design-decisions §13) a partir do insight D11 desta fatura (ADR-0028 §2): os fatores já somam a
 * diferença no backend. Sem D11 para a fatura, o bloco não aparece (ADR-0029 §3).
 */
function InvoiceExplanation({ cardId, dueMonth }: { cardId: string; dueMonth: string }) {
  const insights = useInsightList('CARDS')
  const match =
    insights.status === 'success'
      ? insights.data?.items.find(
          (item) =>
            item.detector === 'INVOICE_ABOVE_PATTERN' &&
            item.primaryAction.kind === 'VIEW_INVOICE' &&
            item.primaryAction.params.cardId === cardId &&
            item.primaryAction.params.dueMonth === dueMonth,
        )
      : undefined
  if (!match) return null
  return <ExplanationDetail insightId={match.id} title={match.title} />
}

function ExplanationDetail({ insightId, title }: { insightId: string; title: string }) {
  const detail = useInsightDetail(insightId)
  if (detail.status !== 'success' || !detail.data) return null
  const { figures, contributors, insight } = detail.data
  const factors = contributors.filter((c) => c.kind === 'FACTOR')
  const level = confidence(insight.confidence)
  return (
    <section className={styles.panel} aria-labelledby="invoice-explanation">
      <span className={styles.insightLabel}>
        <Sparkles size={14} strokeWidth={1.75} aria-hidden="true" /> Explicação
      </span>
      <h2 id="invoice-explanation" className={styles.rowTitle}>
        {title}
      </h2>
      {figures.length > 0 ? (
        <dl className={styles.facts}>
          {figures.map((figure) => (
            <div key={figure.code}>
              <dt>{figure.label}</dt>
              <dd>{figureValue(figure)}</dd>
            </div>
          ))}
        </dl>
      ) : null}
      {factors.length > 0 ? (
        <ul className={styles.list} aria-label="Fatores da diferença">
          {factors.map((factor, i) => (
            <li key={`${factor.key ?? 'none'}-${i}`} className={styles.row}>
              <span className={styles.rowMain}>{factor.name}</span>
              <span className={styles.rowValue}>
                <Money value={factor.amount} sign="always" />
              </span>
            </li>
          ))}
        </ul>
      ) : null}
      <p className={styles.caption}>
        {level.label}. Comparação com as suas faturas anteriores, sem IA.
      </p>
      <div className={styles.actions}>
        <Button href={`/insights/${insightId}`} variant="secondary">
          Ver insight
        </Button>
        <span className={styles.caption} aria-disabled="true">
          Perguntar ao Copilot · Em breve
        </span>
      </div>
    </section>
  )
}

/** Próxima fatura: só o que já está comprometido em parcelas, nunca estimativa (ADR-0025 §10, ADR-0029 §4). */
function NextInvoice({ cardId, year }: { cardId: string; year: string }) {
  const future = useFutureInvoices(cardId)
  const next = future.status === 'success' ? future.data?.months[0] : undefined
  if (future.status === 'error') {
    return <PartialNotice onRetry={future.retry}>Não conseguimos carregar a próxima fatura.</PartialNotice>
  }
  if (!next) return null
  return (
    <section className={styles.panel} aria-labelledby="next-invoice">
      <h2 id="next-invoice" className={styles.caption}>
        Próxima fatura · {monthName(next.dueMonth, year)}
      </h2>
      <dl className={styles.facts}>
        <div>
          <dt>Já comprometido (parcelas)</dt>
          <dd>
            <Money value={next.committed} />
          </dd>
        </div>
      </dl>
      <p className={styles.caption}>Novas compras ainda não aparecem aqui.</p>
      <Link href={`/cards/${cardId}/invoices`} className={styles.link}>
        Ver próximas faturas
        <ChevronRight size={16} strokeWidth={1.75} aria-hidden="true" />
      </Link>
    </section>
  )
}

function InvoiceItems({ cardId, dueMonth, itemCount }: { cardId: string; dueMonth: string; itemCount: number }) {
  const [page, setPage] = useState(0)
  const items = useInvoiceItems(cardId, dueMonth, page, PAGE_SIZE)
  const categories = useCategories()
  const names = categoryNames(categories)

  return (
    <section className={styles.panel} aria-labelledby="invoice-items">
      <h2 id="invoice-items" className={styles.sectionTitle}>
        Lançamentos da fatura
      </h2>
      {items.status === 'loading' ? <LoadingPanel label="Carregando os lançamentos…" rows={3} /> : null}
      {items.status === 'error' ? (
        <PartialNotice onRetry={items.retry}>Não conseguimos carregar os lançamentos. O resumo da fatura continua acima.</PartialNotice>
      ) : null}
      {items.status === 'success' && items.data ? (
        items.data.items.length === 0 ? (
          <p className={styles.caption}>
            {itemCount === 0 ? 'Nenhum lançamento nesta fatura ainda. Compras no cartão aparecem aqui.' : 'Nada nesta página.'}
          </p>
        ) : (
          <>
            <ul className={styles.list}>
              {items.data.items.map((item) => (
                <ItemRow key={`${item.kind}-${item.transactionId}-${item.installment?.number ?? 0}`} item={item} cardId={cardId} categories={names} />
              ))}
            </ul>
            <div className={styles.pager}>
              <span className={styles.caption}>
                Mostrando {items.data.items.length} de {items.data.totalItems} lançamentos
              </span>
              <div className={styles.actions}>
                <Button variant="secondary" onClick={() => setPage((p) => p - 1)} disabled={page === 0}>
                  Anteriores
                </Button>
                <Button
                  variant="secondary"
                  onClick={() => setPage((p) => p + 1)}
                  disabled={(page + 1) * PAGE_SIZE >= items.data.totalItems}
                >
                  Próximos
                </Button>
              </div>
            </div>
          </>
        )
      ) : null}
    </section>
  )
}

function ItemRow({ item, cardId, categories }: { item: InvoiceItem; cardId: string; categories: Map<string, string> }) {
  const credit = item.kind === 'CREDIT'
  const installment = item.installment && item.installment.count > 1 ? item.installment : null
  const title = item.merchantName ?? item.description
  const category = item.categoryCode ? (categories.get(item.categoryCode) ?? null) : null
  const href = installment ? `/cards/${cardId}/installments/${item.transactionId}` : `/transactions/${item.transactionId}`
  return (
    <li className={`${styles.row} ${installment ? styles.highlight : ''}`}>
      {installment ? <Layers size={18} strokeWidth={1.75} aria-hidden="true" /> : null}
      <span className={styles.rowMain}>
        <Link href={href} className={styles.rowTitle}>
          {title}
        </Link>
        <span className={styles.caption}>
          {[
            installment ? `Parcela ${installment.number} de ${installment.count} · compra em ${formatShortDate(item.occurredOn)}` : formatShortDate(item.occurredOn),
            credit ? 'Crédito ou estorno' : category,
            item.source === 'IMPORT' ? 'Importado' : 'Manual',
          ]
            .filter(Boolean)
            .join(' · ')}
        </span>
        {magnitude(item.cancelledAmount) > 0 ? (
          <span className={styles.caption}>
            <Money value={item.cancelledAmount} /> cancelado por estorno
          </span>
        ) : null}
      </span>
      <span className={styles.rowValue}>
        <Money value={item.amount} sign={credit ? 'inflow' : 'outflow'} />
      </span>
    </li>
  )
}

