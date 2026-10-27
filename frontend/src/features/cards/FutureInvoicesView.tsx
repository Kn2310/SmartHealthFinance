'use client'

import { Info } from 'lucide-react'
import Link from 'next/link'
import { MonthBars } from '@/components/charts/MonthBars'
import { Money } from '@/components/ui/Money'
import { calculationNote, plural, shortMonth } from './format'
import { useCard, useFutureInvoices } from './hooks'
import { Badge } from './parts'
import { BackLink, ErrorPanel, LoadingPanel, PartialNotice } from './states'
import { CardNotFound } from './CardDetailView'
import styles from './Cards.module.css'

/**
 * D-FutureInvoices (`/cards/:id/invoices`): só o valor já comprometido em parcelas por fatura, como fato. Novas compras
 * não aparecem e não há estimativa (ADR-0025 §10; a "estimativa pelo seu padrão" é do M9, ADR-0029 §4).
 */
export function FutureInvoicesView({ cardId }: { cardId: string | null }) {
  if (!cardId) return <CardNotFound />
  return <FutureInvoices cardId={cardId} />
}

function FutureInvoices({ cardId }: { cardId: string }) {
  const card = useCard(cardId)
  const future = useFutureInvoices(cardId)

  if (future.status === 'error' && future.failure.code === 'CARD_NOT_FOUND') return <CardNotFound />
  const nickname = card.status === 'success' ? card.data?.nickname : undefined

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
          <p className={styles.subtitle}>{nickname ? `${nickname} · ` : ''}compromissos já contratados</p>
        </div>
      </header>
      {card.status === 'error' ? <PartialNotice>Não conseguimos carregar o nome do cartão.</PartialNotice> : null}

      {future.status === 'loading' ? <LoadingPanel label="Carregando as próximas faturas…" rows={4} /> : null}
      {future.status === 'error' ? (
        <>
          <BackLink href={`/cards/${cardId}`}>Voltar ao cartão</BackLink>
          <ErrorPanel title="Não foi possível carregar as próximas faturas" code={future.failure.code} onRetry={future.retry} />
        </>
      ) : null}
      {future.status === 'success' && future.data ? (
        future.data.months.length === 0 ? (
          <section className={`${styles.state} ${styles.empty}`} aria-labelledby="future-empty">
            <h2 id="future-empty" className={styles.sectionTitle}>
              Nenhuma parcela comprometida
            </h2>
            <p className={styles.muted}>
              Este cartão não tem compras parceladas com parcelas em faturas futuras. Novas compras aparecem na fatura
              atual.
            </p>
          </section>
        ) : (
          <>
            <section className={styles.panel} aria-labelledby="future-chart">
              <h2 id="future-chart" className={styles.sectionTitle}>
                Valor já comprometido por fatura
              </h2>
              <MonthBars
                title="Valor já comprometido por fatura"
                series={[{ label: 'Parcelas contratadas', tone: 'expense' }]}
                data={future.data.months.map((month) => ({ month: month.dueMonth, partial: false, values: [month.committed] }))}
              />
              <p className={styles.info} role="note">
                <Info size={16} strokeWidth={1.75} aria-hidden="true" />
                <span>
                  <strong>Novas compras ainda não aparecem aqui.</strong> Os valores acima são parcelas já contratadas
                  (confirmadas).
                </span>
              </p>
            </section>

            <section className={styles.panel} aria-labelledby="future-detail">
              <div>
                <h2 id="future-detail" className={styles.sectionTitle}>
                  Detalhe por mês
                </h2>
                <p className={styles.caption}>
                  Total comprometido: <Money value={future.data.totalCommitted} /> em{' '}
                  {plural(future.data.months.length, 'fatura', 'faturas')}
                </p>
              </div>
              <ul className={styles.list}>
                {future.data.months.map((month) => (
                  <li key={month.dueMonth} className={styles.row}>
                    <span className={styles.rowMain}>
                      <Link href={`/cards/${cardId}/invoices/${month.dueMonth}`} className={styles.rowTitle}>
                        {shortMonth(month.dueMonth)}
                      </Link>
                      <span className={styles.caption}>
                        {month.installments.length === 0
                          ? 'Sem parcelas'
                          : month.installments.map((part) => `${part.description} · parcela ${part.number}/${part.count}`).join(' · ')}
                      </span>
                    </span>
                    <span className={styles.rowSide}>
                      <span className={styles.rowValue}>
                        <Money value={month.committed} />
                      </span>
                      {month.installments.length > 0 ? <Badge tone="success">Confirmado</Badge> : null}
                    </span>
                  </li>
                ))}
              </ul>
              <p className={styles.caption}>{calculationNote(future.data.calculationVersion)}</p>
            </section>
          </>
        )
      ) : null}
    </div>
  )
}
