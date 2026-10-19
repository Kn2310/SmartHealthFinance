'use client'

import { ArrowRight, Info } from 'lucide-react'
import Link from 'next/link'
import { useEffect, useRef, useState } from 'react'
import { longMonthLabel } from '@/components/charts/geometry'
import { HorizontalBars } from '@/components/charts/HorizontalBars'
import { MonthBars } from '@/components/charts/MonthBars'
import { ScoreLine } from '@/components/charts/ScoreLine'
import { Money } from '@/components/ui/Money'
import { Skeleton } from '@/components/ui/Skeleton'
import type { InsightDetail, InsightFeedback, InsightItem } from '@/lib/api/types'
import { dismissInsight, markInsightSeen, sendInsightFeedback, useInsightDetail } from './api'
import { actionHref, confidence, dataSourcesText, dayLabel, figureValue, sourceName } from './format'
import styles from './Insights.module.css'

const CURRENCY = 'BRL'

/**
 * Detalhe do insight (`/insights/:id`, D-InsightDetail / M-InsightDetail): título = a frase; Resumo → Evidências →
 * Explicação (+ mês em andamento e fontes); coluna lateral com Ação recomendada, Confiança, feedback, dispensar e
 * Relacionados. Abrir marca como visto (POST explícito, ADR-0023 §3). Todo número vem do backend.
 */
export function InsightDetailView({ id }: { id: string | null }) {
  if (!id) return <NotFound />
  return <Detail id={id} />
}

function NotFound() {
  return (
    <div className={styles.page}>
      <section className={styles.panel} aria-labelledby="insight-missing-title">
        <h1 id="insight-missing-title" className={styles.panelTitle}>Insight não encontrado</h1>
        <p>Ele pode ter sido removido ou o endereço está incompleto.</p>
        <div className={styles.actions}>
          <Link href="/insights" className={styles.secondaryButton}>Ver insights</Link>
        </div>
      </section>
    </div>
  )
}

function Detail({ id }: { id: string }) {
  const detail = useInsightDetail(id)
  if (detail.status === 'loading') {
    return (
      <div className={styles.page}>
        <div className={styles.panel} role="status" aria-busy="true">
          <span className="sr-only">Carregando o insight…</span>
          <Skeleton width="lg" height="value" />
          <Skeleton width="md" />
        </div>
      </div>
    )
  }
  if (detail.status === 'error') {
    if (detail.failure.code === 'INSIGHT_NOT_FOUND') return <NotFound />
    return (
      <div className={styles.page}>
        <div role="alert" className={styles.panel}>
          <p className={styles.panelTitle}>Não conseguimos carregar este insight agora.</p>
          <p>Seus dados estão seguros. Tente de novo em instantes.</p>
          <div className={styles.actions}>
            <button type="button" className={styles.secondaryButton} onClick={detail.retry}>Tentar novamente</button>
          </div>
        </div>
      </div>
    )
  }
  if (!detail.data) return null
  return <Loaded detail={detail.data} onItem={(item) => detail.replace({ ...detail.data!, insight: item })} />
}

function Loaded({ detail, onItem }: { detail: InsightDetail; onItem: (item: InsightItem) => void }) {
  const { insight } = detail
  const seenSent = useRef(false)

  useEffect(() => {
    if (seenSent.current || insight.seen || insight.status !== 'ACTIVE') return
    seenSent.current = true
    void markInsightSeen(insight.id).then((result) => {
      if (result.ok) onItem(result.data)
    })
  }, [insight.id, insight.seen, insight.status, onItem])

  const important = insight.importance === 'IMPORTANT'
  const period = insight.period.month
    ? `${longMonthLabel(insight.period.month)}${insight.period.partial ? ' (em andamento)' : ''}`
    : null

  return (
    <div className={styles.page}>
      <nav aria-label="Trilha" className={styles.breadcrumb}>
        <Link href="/insights">Insights</Link>
        <span aria-hidden="true"> › </span>
        <span aria-current="page">{insight.typeLabel}</span>
      </nav>
      <header className={styles.section}>
        <h1 className={styles.pageTitle}>{insight.title}</h1>
        <div className={styles.tags}>
          <span className={styles.label}>✦ Insight</span>
          <span className={`${styles.badge} ${important ? styles.badgeImportant : ''}`}>
            {important ? 'Importante' : 'Informativo'}
          </span>
          <span className={styles.badge}>{insight.typeLabel}</span>
          {insight.status !== 'ACTIVE' ? <span className={styles.badge}>{statusLabel(insight.status)}</span> : null}
          <span className={styles.meta}>
            {[`Atualizado ${dayLabel(insight.updatedAt).toLowerCase()}`, period].filter(Boolean).join(' · ')}
          </span>
        </div>
      </header>

      <div className={styles.detailGrid}>
        <div className={styles.main}>
          <Summary detail={detail} />
          <Evidence detail={detail} />
          <Explanation detail={detail} />
        </div>
        <aside className={styles.aside} aria-label="Ações e contexto">
          <Recommended detail={detail} />
          <Confidence detail={detail} onItem={onItem} />
          {insight.status === 'ACTIVE' ? <Dismiss item={insight} onItem={onItem} /> : null}
          <Related detail={detail} />
        </aside>
      </div>
    </div>
  )
}

function statusLabel(status: string): string {
  return status === 'DISMISSED' ? 'Dispensado' : status === 'RESOLVED' ? 'Resolvido' : 'Expirado'
}

function Summary({ detail }: { detail: InsightDetail }) {
  return (
    <section className={styles.panel} aria-labelledby="insight-summary">
      <h2 id="insight-summary" className={styles.panelTitle}>Resumo</h2>
      <p>{detail.insight.support}</p>
      {detail.figures.length > 0 ? (
        <dl className={styles.figures}>
          {detail.figures.map((figure) => (
            <div key={figure.code}>
              <dt>{figure.label}</dt>
              <dd>{figureValue(figure, CURRENCY)}</dd>
            </div>
          ))}
        </dl>
      ) : null}
    </section>
  )
}

function Evidence({ detail }: { detail: InsightDetail }) {
  const { series, contributors } = detail
  const moneyContributors = contributors.filter((c) => c.kind === 'MERCHANT' || c.kind === 'CATEGORY')
  const otherContributors = contributors.filter((c) => c.kind === 'TRANSACTION' || c.kind === 'CHARGE')
  if (series.points.length === 0 && contributors.length === 0) return null
  return (
    <section className={styles.panel} aria-labelledby="insight-evidence">
      <h2 id="insight-evidence" className={styles.panelTitle}>Evidências</h2>
      {series.points.length > 0 ? <Series detail={detail} /> : null}
      {moneyContributors.length > 0 ? (
        <div className={styles.stack}>
          <h3 className={styles.sectionTitle}>
            {moneyContributors[0]?.kind === 'CATEGORY' ? 'Onde mais aumentou' : 'Onde foi o gasto'}
          </h3>
          <HorizontalBars
            label={moneyContributors[0]?.kind === 'CATEGORY' ? 'Categorias que mais aumentaram' : 'Onde foi o gasto'}
            items={moneyContributors.map((c, i) => ({
              key: `${c.key ?? 'none'}-${i}`,
              label: c.name,
              amount: c.amount,
              note: c.count !== null ? `${c.count} ${c.count === 1 ? 'compra' : 'compras'}` : undefined,
            }))}
          />
        </div>
      ) : null}
      {otherContributors.length > 0 ? (
        <ul className={styles.contributors} aria-label={otherContributors[0]?.kind === 'CHARGE' ? 'Cobranças' : 'Transação'}>
          {otherContributors.map((c, i) => (
            <li key={`${c.key ?? 'none'}-${i}`} className={styles.contributor}>
              <span className={styles.contributorName}>
                <span>{c.name}</span>
                {c.day ? <span className={styles.meta}>{dayLabel(`${c.day}T12:00:00`)}</span> : null}
              </span>
              <Money value={c.amount} />
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  )
}

function Series({ detail }: { detail: InsightDetail }) {
  const { series } = detail
  const title = `Evolução mensal: ${detail.insight.subjectLabel}`
  if (series.kind === 'MONEY') {
    return (
      <MonthBars title={title} series={[{ label: detail.insight.subjectLabel, tone: detail.insight.type === 'INCOME' ? 'income' : 'expense' }]}
        data={series.points.map((p) => ({ month: p.month, partial: p.partial, values: [{ amount: p.value, currency: CURRENCY }] }))}
        caption={series.points.some((p) => p.partial) ? '* Mês em andamento' : undefined} />
    )
  }
  if (series.kind === 'POINTS') {
    return (
      <ScoreLine title={title} tone="light"
        data={series.points.map((p) => ({ month: p.month, score: Number(p.value), estimated: false, partial: p.partial }))} />
    )
  }
  return (
    <table className={styles.facts}>
      <caption className="sr-only">{title}</caption>
      <tbody>
        {series.points.map((p) => (
          <tr key={p.month}>
            <th scope="row">{longMonthLabel(p.month)}{p.partial ? ' (em andamento)' : ''}</th>
            <td>{figureValue({ code: 'p', label: '', kind: series.kind, value: p.value, from: '', to: '' })}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function Explanation({ detail }: { detail: InsightDetail }) {
  const origins = dataSourcesText(detail.dataSources)
  return (
    <section className={styles.panel} aria-labelledby="insight-explanation">
      <h2 id="insight-explanation" className={styles.panelTitle}>Como comparamos</h2>
      <p>{detail.comparison}</p>
      {detail.limitations.map((l) => (
        <p key={l.code} className={styles.notice}>
          <Info size={18} aria-hidden="true" />
          <span>{l.text}</span>
        </p>
      ))}
      <ul className={styles.facts} aria-label="Fontes">
        {detail.sources.map((s, i) => (
          <li key={`${s.source}-${i}`}>
            {sourceName(s.source)} · {shortDate(s.from)} a {shortDate(s.to)}
          </li>
        ))}
        {origins ? <li>Lançamentos de origem {origins}</li> : null}
      </ul>
    </section>
  )
}

function shortDate(iso: string): string {
  const [year, month, day] = iso.split('-')
  return `${day}/${month}/${year}`
}

function Recommended({ detail }: { detail: InsightDetail }) {
  const { recommendation, actions } = detail
  return (
    <section className={styles.panel} aria-labelledby="insight-action">
      <h2 id="insight-action" className={styles.panelTitle}>Ação recomendada</h2>
      {recommendation ? (
        <div className={styles.stack}>
          <p className={styles.recommendationTitle}>{recommendation.title}</p>
          <p className={styles.muted}>{recommendation.text}</p>
        </div>
      ) : null}
      <div className={styles.stack}>
        {actions.map((action) => {
          const href = actionHref(action)
          const className = action.primary ? styles.button : styles.secondaryButton
          return href ? (
            <Link key={action.kind} href={href} className={className}>
              {action.label}
              {action.primary ? <ArrowRight size={18} aria-hidden="true" /> : null}
            </Link>
          ) : (
            <button key={action.kind} type="button" className={styles.secondaryButton} disabled>
              {action.label} · Em breve
            </button>
          )
        })}
      </div>
    </section>
  )
}

function Confidence({ detail, onItem }: { detail: InsightDetail; onItem: (item: InsightItem) => void }) {
  const { insight } = detail
  const { level, label } = confidence(insight.confidence)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(false)

  async function send(value: InsightFeedback) {
    setBusy(true)
    setError(false)
    const result = await sendInsightFeedback(insight.id, insight.feedback === value ? null : value)
    setBusy(false)
    if (result.ok) onItem(result.data)
    else setError(true)
  }

  return (
    <section className={styles.panel} aria-labelledby="insight-confidence">
      <h2 id="insight-confidence" className={styles.panelTitle}>Confiança</h2>
      <div className={styles.stack}>
        <span className={styles.confidence} aria-hidden="true">
          {[1, 2, 3].map((n) => (
            <span key={n} className={`${styles.segment} ${n <= level ? styles.segmentOn : ''}`} />
          ))}
        </span>
        <p>{label}</p>
        <p className={styles.muted}>Quanto mais meses importados, mais precisa fica a comparação.</p>
      </div>
      <div className={styles.stack} role="group" aria-labelledby="insight-feedback">
        <p id="insight-feedback" className={styles.muted}>Este insight foi útil?</p>
        <div className={styles.actions}>
          <button type="button" className={styles.secondaryButton} aria-pressed={insight.feedback === 'USEFUL'}
            disabled={busy} onClick={() => send('USEFUL')}>
            Útil
          </button>
          <button type="button" className={styles.secondaryButton} aria-pressed={insight.feedback === 'NOT_USEFUL'}
            disabled={busy} onClick={() => send('NOT_USEFUL')}>
            Não útil
          </button>
        </div>
        {error ? <p role="alert" className={styles.muted}>Não conseguimos registrar agora. Tente de novo.</p> : null}
      </div>
    </section>
  )
}

function Dismiss({ item, onItem }: { item: InsightItem; onItem: (item: InsightItem) => void }) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function confirm() {
    setBusy(true)
    const result = await dismissInsight(item.id)
    setBusy(false)
    if (result.ok) {
      setConfirming(false)
      onItem(result.data)
    }
    else {
      setError(result.code === 'INSIGHT_NOT_ACTIVE' ? 'Este insight já não está ativo.'
        : 'Não conseguimos dispensar agora. Tente de novo.')
    }
  }

  return (
    <section className={styles.panel} aria-labelledby="insight-dismiss">
      <h2 id="insight-dismiss" className="sr-only">Dispensar</h2>
      {confirming ? (
        <div className={styles.confirm} role="group" aria-labelledby="dismiss-text">
          <p id="dismiss-text">Dispensar este insight? Ele não volta a aparecer.</p>
          <div className={styles.actions}>
            <button type="button" className={styles.dangerButton} disabled={busy} onClick={confirm}>Dispensar</button>
            <button type="button" className={styles.secondaryButton} disabled={busy}
              onClick={() => setConfirming(false)}>
              Cancelar
            </button>
          </div>
        </div>
      ) : (
        <button type="button" className={styles.secondaryButton} onClick={() => setConfirming(true)}>
          Dispensar insight
        </button>
      )}
      {error ? <p role="alert" className={styles.muted}>{error}</p> : null}
    </section>
  )
}

function Related({ detail }: { detail: InsightDetail }) {
  const { related, dimension } = detail
  if (related.length === 0 && !dimension) return null
  return (
    <section className={styles.panel} aria-labelledby="insight-related">
      <h2 id="insight-related" className={styles.panelTitle}>Relacionado</h2>
      <ul className={styles.related}>
        {dimension ? (
          <li>
            <Link href="/home/health" className={styles.relatedLink}>
              <span>{dimension.label}{dimension.score !== null ? ` · ${dimension.score}` : ''} na saúde financeira</span>
            </Link>
          </li>
        ) : null}
        {related.map((r) => (
          <li key={r.id}>
            <Link href={`/insights/${r.id}`} className={styles.relatedLink}>
              <span>{r.title}</span>
              <span className={styles.meta}>{r.typeLabel} · {r.importance === 'IMPORTANT' ? 'Importante' : 'Informativo'}</span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}
