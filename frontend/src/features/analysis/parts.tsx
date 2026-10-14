import { ArrowDownRight, ArrowUpRight, CircleAlert, Info, Minus, RefreshCw } from 'lucide-react'
import type { ReactNode } from 'react'
import type { AnalysisMeta, AnalysisMetric, AnalysisVariation, MoneyDto } from '@/lib/api/types'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { Skeleton } from '@/components/ui/Skeleton'
import type { SignDisplay } from '@/lib/format/money'
import { variationText, type VariationBase } from './format'
import styles from './Analysis.module.css'

/**
 * Peças comuns às abas. `tone` diz se subir é bom (receita, resultado) ou ponto de atenção (despesa): a cor só
 * reforça o texto, que sempre diz a direção.
 */
export function Variation({ variation, meta, base, tone = 'neutral' }: {
  variation: AnalysisVariation | null
  meta: AnalysisMeta
  base: VariationBase
  tone?: 'spending' | 'earning' | 'neutral'
}) {
  const text = variationText(variation, meta, base)
  if (!text || !variation) {
    return <span className={styles.variation}>Sem base para comparar</span>
  }
  const up = variation.trend === 'UP' || variation.trend === 'NEW'
  const down = variation.trend === 'DOWN' || variation.trend === 'GONE'
  const attention = (tone === 'spending' && up) || (tone === 'earning' && down)
  const positive = (tone === 'spending' && down) || (tone === 'earning' && up)
  const Icon = up ? ArrowUpRight : down ? ArrowDownRight : Minus
  const className = [styles.variation, attention ? styles.variationAttention : '', positive ? styles.variationPositive : '']
    .filter(Boolean).join(' ')
  return (
    <span className={className}>
      <Icon size={14} strokeWidth={2} aria-hidden="true" />
      {text}
    </span>
  )
}

/** Cartão de métrica: valor e as duas comparações (mês anterior e média). */
export function MetricCard({ label, metric, meta, tone, sign = 'auto', hint }: {
  label: string
  metric: AnalysisMetric | null
  meta: AnalysisMeta
  tone: 'spending' | 'earning' | 'neutral'
  sign?: SignDisplay
  hint?: ReactNode
}) {
  return (
    <div className={styles.metric}>
      <span className={styles.metricLabel}>{label}</span>
      <span className={styles.metricValue}>
        <Money value={metric?.amount ?? null} sign={sign} />
      </span>
      {metric ? (
        <>
          <Variation variation={metric.vsPrevious} meta={meta} base="previous" tone={tone} />
          <Variation variation={metric.vsAverage} meta={meta} base="average" tone={tone} />
        </>
      ) : null}
      {hint ? <span className={styles.metricHint}>{hint}</span> : null}
    </div>
  )
}

export function MoneyText({ value, sign }: { value: MoneyDto | null; sign?: SignDisplay }) {
  return <Money value={value} sign={sign} />
}

/** Avisos de freshness (ADR-0014 §6) e de base de comparação curta (ADR-0015 §4). */
export function AnalysisNotices({ meta }: { meta: AnalysisMeta }) {
  const notices: { key: string; warning?: boolean; text: string }[] = []
  if (meta.freshness.state === 'UPDATING') {
    notices.push({ key: 'updating', text: 'Atualizando a análise: lançamentos feitos agora há pouco podem levar alguns segundos para aparecer.' })
  }
  if (meta.freshness.state === 'NOT_BUILT') {
    notices.push({ key: 'building', text: 'Estamos preparando sua análise. Os números aparecem em instantes.' })
  }
  if (meta.state !== 'NO_DATA' && meta.comparison.average.state === 'LIMITED') {
    notices.push({
      key: 'limited',
      warning: true,
      text: `A média usa só ${meta.comparison.average.monthsUsed === 1 ? '1 mês' : `${meta.comparison.average.monthsUsed} meses`} de histórico; compare com cuidado.`,
    })
  }
  if (meta.state !== 'NO_DATA' && meta.comparison.average.state === 'INSUFFICIENT_DATA' && !meta.comparison.previous) {
    notices.push({ key: 'insufficient', warning: true, text: 'Ainda não há meses anteriores para comparar.' })
  }
  if (notices.length === 0) return null
  return (
    <div className={styles.list} role="status">
      {notices.map((notice) => (
        <p key={notice.key} className={`${styles.notice} ${notice.warning ? styles.noticeWarning : ''}`}>
          {notice.warning ? <CircleAlert size={18} aria-hidden="true" /> : <Info size={18} aria-hidden="true" />}
          <span>{notice.text}</span>
        </p>
      ))}
    </div>
  )
}

export function AnalysisSkeleton() {
  return (
    <div className={styles.metrics} role="status" aria-busy="true">
      <span className="sr-only">Carregando a análise…</span>
      {[0, 1, 2].map((i) => (
        <div key={i} className={styles.metric}>
          <Skeleton width="sm" height="caption" />
          <Skeleton width="md" height="value" />
          <Skeleton width="lg" height="caption" />
        </div>
      ))}
    </div>
  )
}

export function AnalysisError({ onRetry, invalid }: { onRetry: () => void; invalid?: boolean }) {
  return (
    <section className={`${styles.metric} ${styles.empty}`} role="alert" aria-labelledby="analysis-error-title">
      <h2 id="analysis-error-title" className={styles.metricLabel}>
        {invalid ? 'Esse mês não pode ser analisado' : 'Não conseguimos carregar a análise'}
      </h2>
      <p className={styles.muted}>
        {invalid ? 'Escolha um mês até o atual.' : 'Seus dados estão seguros. Tente de novo em instantes.'}
      </p>
      {invalid ? null : (
        <Button variant="secondary" onClick={onRetry}>
          <RefreshCw size={18} aria-hidden="true" />
          Tentar novamente
        </Button>
      )}
    </section>
  )
}

/** Sem nenhuma movimentação no fluxo de caixa: o próximo passo é lançar ou importar. */
export function AnalysisEmpty() {
  return (
    <section className={`${styles.metric} ${styles.empty}`} aria-labelledby="analysis-empty-title">
      <h2 id="analysis-empty-title" className={styles.metricLabel}>
        Ainda não há o que analisar
      </h2>
      <p className={styles.muted}>
        Registre receitas e despesas ou importe um extrato. A análise usa as transações efetivadas das contas incluídas no
        total.
      </p>
      <div className={styles.toolbar}>
        <Button href="/transactions/new">Nova transação</Button>
        <Button href="/import" variant="secondary">
          Importar extrato
        </Button>
      </div>
    </section>
  )
}

export function QuietPeriod() {
  return <p className={styles.notice}>Nenhuma movimentação neste mês até agora. Os valores zerados são reais.</p>
}
