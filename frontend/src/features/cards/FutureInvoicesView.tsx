'use client'

import { Info } from 'lucide-react'
import Link from 'next/link'
import { EstimateBars } from '@/components/charts/EstimateBars'
import { Money } from '@/components/ui/Money'
import { FactBadge, ProjectedValue } from '@/components/ui/ProjectedValue'
import { useForecastInvoices } from '@/features/forecast/api'
import { estimateStatus, evidenceMonths } from '@/features/forecast/format'
import { ConfidenceNotes, StatusNote } from '@/features/forecast/parts'
import type { ForecastInvoicesResponse } from '@/lib/api/types'
import type { FutureInvoices } from './api'
import { calculationNote, plural, shortMonth } from './format'
import { estimatedPartOf, futureRows } from './forecast'
import { useCard, useFutureInvoices } from './hooks'
import { Badge } from './parts'
import { BackLink, ErrorPanel, LoadingPanel, PartialNotice } from './states'
import { CardNotFound } from './CardDetailView'
import styles from './Cards.module.css'

/**
 * D-FutureInvoices (`/cards/:id/invoices`): por fatura, o comprometido em parcelas (fato, Confirmado) e as novas compras
 * pelo seu padrão (estimadas, tracejadas) lado a lado (ADR-0033 §4, adendo ao ADR-0029 §4). Sem estimativa
 * (`PARTIAL_CARD_HISTORY`, sem snapshot, histórico curto ou erro no Forecast), só o comprometido, com o aviso
 * "Novas compras ainda não aparecem aqui." (ADR-0025 §10). Nada é somado: os valores vêm da API.
 */
export function FutureInvoicesView({ cardId }: { cardId: string | null }) {
  if (!cardId) return <CardNotFound />
  return <FutureInvoices cardId={cardId} />
}

function FutureInvoices({ cardId }: { cardId: string }) {
  const card = useCard(cardId)
  const future = useFutureInvoices(cardId)
  const forecast = useForecastInvoices(cardId)

  if (future.status === 'error' && future.failure.code === 'CARD_NOT_FOUND') return <CardNotFound />
  const nickname = card.status === 'success' ? card.data?.nickname : undefined
  const loading = future.status === 'loading' || forecast.status === 'loading'

  return (
    <div className={styles.page}>
      <nav aria-label="Você está em" className={styles.breadcrumb}>
        <Link href="/cards">Cartões</Link>
        <span aria-hidden="true">›</span>
        <Link href={`/cards/${cardId}`}>{nickname ?? 'Cartão'}</Link>
        <span aria-hidden="true">›</span>
        <span aria-current="page">Próximas faturas</span>
      </nav>
      <header className={styles.header}>
        <div className={styles.headerText}>
          <h1 className={styles.title}>Próximas faturas</h1>
          <p className={styles.subtitle}>{nickname ? `${nickname} · ` : ''}comprometido e novas compras estimadas</p>
        </div>
      </header>
      {card.status === 'error' ? <PartialNotice>Não conseguimos carregar o nome do cartão.</PartialNotice> : null}

      {loading ? <LoadingPanel label="Carregando as próximas faturas…" rows={4} /> : null}
      {future.status === 'error' ? (
        <>
          <BackLink href={`/cards/${cardId}`}>Voltar ao cartão</BackLink>
          <ErrorPanel title="Não foi possível carregar as próximas faturas" code={future.failure.code} onRetry={future.retry} />
        </>
      ) : null}
      {future.status === 'success' && future.data && forecast.status !== 'loading' ? (
        <Loaded cardId={cardId} future={future.data} forecast={forecast.status === 'success' ? forecast.data : null} />
      ) : null}
    </div>
  )
}

function Loaded({ cardId, future, forecast }: {
  cardId: string
  future: FutureInvoices
  forecast: ForecastInvoicesResponse | null
}) {
  const rows = futureRows(future, forecast)
  const hasEstimate = rows.some((row) => row.estimate !== null)
  const status = forecast ? estimateStatus(forecast.state, forecast.freshness) : null

  if (rows.length === 0) {
    return (
      <section className={`${styles.state} ${styles.empty}`} aria-labelledby="future-empty">
        <h2 id="future-empty" className={styles.sectionTitle}>
          Nenhuma parcela comprometida
        </h2>
        <p className={styles.muted}>
          Este cartão não tem compras parceladas com parcelas em faturas futuras. Novas compras aparecem na fatura atual.
        </p>
        {status && status !== 'READY' ? <StatusNote status={status} /> : null}
      </section>
    )
  }

  const chartTitle = hasEstimate ? 'Comprometido e novas compras estimadas por fatura' : 'Valor já comprometido por fatura'
  return (
    <>
      <section className={styles.panel} aria-labelledby="future-chart">
        <h2 id="future-chart" className={styles.sectionTitle}>
          {chartTitle}
        </h2>
        <EstimateBars
          title={chartTitle}
          factLabel={hasEstimate ? 'Já comprometido (parcelas)' : 'Parcelas contratadas'}
          estimatedLabel="Novas compras (estimado)"
          totalLabel={hasEstimate ? 'Total (≈)' : 'Total'}
          data={rows.map((row) => ({
            month: row.dueMonth,
            fact: row.committed,
            estimated: row.estimate ? row.estimate.estimatedPart : null,
            total: row.total,
          }))}
        />
        {hasEstimate ? (
          <p className={styles.info} role="note">
            <Info size={16} strokeWidth={1.75} aria-hidden="true" />
            <span>
              O trecho tracejado é estimado pelo seu padrão de compras; o sólido são parcelas já contratadas
              (confirmadas).
            </span>
          </p>
        ) : (
          <p className={styles.info} role="note">
            <Info size={16} strokeWidth={1.75} aria-hidden="true" />
            <span>
              <strong>Novas compras ainda não aparecem aqui.</strong> Os valores acima são parcelas já contratadas
              (confirmadas).
            </span>
          </p>
        )}
        {status && status !== 'READY' ? <StatusNote status={status} /> : null}
      </section>

      <section className={styles.panel} aria-labelledby="future-detail">
        <div>
          <h2 id="future-detail" className={styles.sectionTitle}>
            Detalhe por mês
          </h2>
          <p className={styles.caption}>
            Total comprometido: <Money value={future.totalCommitted} /> em{' '}
            {plural(future.months.length, 'fatura', 'faturas')}
          </p>
        </div>
        <ul className={styles.list}>
          {rows.map((row) => (
            <li key={row.dueMonth} className={styles.row}>
              <span className={styles.rowMain}>
                <Link href={`/cards/${cardId}/invoices/${row.dueMonth}`} className={styles.rowTitle}>
                  {shortMonth(row.dueMonth)}
                </Link>
                <span className={styles.caption}>
                  {row.installments.length === 0
                    ? 'Sem parcelas'
                    : row.installments.map((part) => `${part.description} · parcela ${part.number}/${part.count}`).join(' · ')}
                </span>
                {row.estimate ? (
                  <span className={styles.forecastNotes}>
                    <ConfidenceNotes value={row.estimate} months={evidenceMonths(row.estimate)} />
                  </span>
                ) : null}
              </span>
              {row.estimate ? (
                <span className={styles.split}>
                  <span className={styles.splitLine}>
                    <span className={styles.splitLabel}>Já comprometido</span>
                    <Money value={row.committed} />
                    <FactBadge />
                  </span>
                  <span className={styles.splitLine}>
                    <span className={styles.splitLabel}>Novas compras</span>
                    <ProjectedValue value={estimatedPartOf(row.estimate)} range={false} />
                  </span>
                  <span className={styles.splitLine}>
                    <span className={styles.splitLabel}>Total</span>
                    <ProjectedValue value={row.estimate} badge={false} />
                  </span>
                </span>
              ) : (
                <span className={styles.rowSide}>
                  <span className={styles.rowValue}>
                    <Money value={row.committed} />
                  </span>
                  {row.installments.length > 0 ? <Badge tone="success">Confirmado</Badge> : null}
                </span>
              )}
            </li>
          ))}
        </ul>
        <p className={styles.caption}>{calculationNote(future.calculationVersion)}</p>
      </section>
    </>
  )
}
