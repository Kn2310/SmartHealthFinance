'use client'

import { ArrowRight, ChevronDown, CircleAlert, Info, Minus, Plus } from 'lucide-react'
import Link from 'next/link'
import { useId, useState } from 'react'
import { monthLabel } from '@/components/charts/geometry'
import { ScoreLine } from '@/components/charts/ScoreLine'
import { ScoreRing } from '@/components/charts/ScoreRing'
import { Skeleton } from '@/components/ui/Skeleton'
import type { Health, HealthDimension, HealthEvolution } from '@/lib/api/types'
import { useRelatedInsights } from '@/features/insights/api'
import { useHealth, useHealthEvolution } from './api'
import {
  CONFIDENCE_LABEL,
  DIMENSION_SHORT,
  basisText,
  evidencePeriod,
  evidenceSource,
  evidenceValue,
  monthTitle,
  nextStepLink,
  sourcesText,
  updatedAgo,
  variationShort,
} from './format'
import { MethodTrigger } from './MethodDrawer'
import styles from './Health.module.css'

/**
 * Saúde financeira (`/home/health`, D-Health / M-Health). Resumo → Fontes → Dimensões → Fatores → Próximos passos
 * (progressive disclosure, 07 §6). Tudo vem do snapshot do backend (ADR-0019); o frontend só formata.
 */
export function HealthView() {
  const health = useHealth()
  const evolution = useHealthEvolution(undefined, 6)

  return (
    <div className={styles.page}>
      <nav aria-label="Trilha" className={styles.breadcrumb}>
        <Link href="/home">Início</Link>
        <span aria-hidden="true"> › </span>
        <span aria-current="page">Saúde financeira</span>
      </nav>
      <header className={styles.pageHeader}>
        <div>
          <h1 className={styles.pageTitle}>Saúde financeira</h1>
          {health.status === 'success' && health.data ? <Subtitle health={health.data} /> : null}
        </div>
        <MethodTrigger className={styles.secondaryButton} />
      </header>

      {health.status === 'loading' ? (
        <PageSkeleton />
      ) : health.status === 'error' ? (
        <div role="alert" className={styles.panel}>
          <p className={styles.panelTitle}>Não conseguimos carregar sua saúde financeira agora.</p>
          <p>Seus dados estão seguros. Tente de novo em instantes.</p>
          <button type="button" className={styles.secondaryButton} onClick={health.retry}>
            Tentar novamente
          </button>
        </div>
      ) : health.data ? (
        <Content health={health.data} evolution={evolution.status === 'success' ? evolution.data : null} />
      ) : null}
    </div>
  )
}

function Subtitle({ health }: { health: Health }) {
  const basis = basisText(health)
  const updated = updatedAgo(health.freshness.computedAt)
  return (
    <p className={styles.pageSubtitle}>
      {[monthTitle(health.period.month), basis ? `calculado com dados de ${basis.replace('–', ' a ')}` : null,
        updated ? `atualizado ${updated}` : null].filter(Boolean).join(' · ')}
    </p>
  )
}

function PageSkeleton() {
  return (
    <div className={styles.panel} role="status" aria-busy="true">
      <span className="sr-only">Carregando sua saúde financeira…</span>
      <Skeleton width="sm" height="value" />
      <Skeleton width="lg" />
      <Skeleton width="md" />
    </div>
  )
}

function Content({ health, evolution }: { health: Health; evolution: HealthEvolution | null }) {
  if (health.state === 'NO_DATA') {
    return (
      <section className={styles.panel} aria-labelledby="health-empty-title">
        <h2 id="health-empty-title" className={styles.panelTitle}>
          {health.freshness.state === 'NOT_BUILT' ? 'Estamos calculando seu score…' : 'Seu score ainda não tem dados'}
        </h2>
        <p>
          O Financial Health aparece depois do primeiro mês fechado com receitas ou despesas. Importe um extrato ou
          lance suas movimentações para começar.
        </p>
        <div className={styles.actions}>
          <Link href="/import" className={styles.secondaryButton}>
            Importar extrato
          </Link>
          <Link href="/transactions/new" className={styles.secondaryButton}>
            Lançar movimentação
          </Link>
        </div>
      </section>
    )
  }

  return (
    <>
      <div className={styles.summaryGrid}>
        <Summary health={health} evolution={evolution} />
        <Sources health={health} />
      </div>
      <Dimensions health={health} />
      {health.factors.length > 0 ? <Factors health={health} /> : null}
      {health.nextSteps.length > 0 ? <NextSteps health={health} /> : null}
    </>
  )
}

function Summary({ health, evolution }: { health: Health; evolution: HealthEvolution | null }) {
  const variation = variationShort(health.variation, health.period.month)
  const confidence = health.confidence ? CONFIDENCE_LABEL[health.confidence] : null
  const accounts = health.context?.activeAccounts ?? 0
  return (
    <section className={styles.panel} aria-labelledby="health-summary-title">
      <h2 id="health-summary-title" className="sr-only">
        Resumo
      </h2>
      <div className={styles.summary}>
        <ScoreRing score={health.score} estimated={health.estimated} size="lg" />
        <div className={styles.summaryCopy}>
          {health.state === 'READY' ? (
            <>
              <p className={styles.summaryScore}>
                {health.estimated ? '≈ ' : ''}
                {health.score} de 100
              </p>
              {variation ? <p className={styles.variation}>{variation}</p> : null}
              <p>{health.summary}</p>
            </>
          ) : (
            <>
              <p className={styles.summaryScore}>Ainda sem score</p>
              <p>
                {health.coverage?.available ?? 0} de 6 dimensões têm nota. O score aparece quando o fluxo de caixa e
                mais uma dimensão tiverem dados.
              </p>
            </>
          )}
          {confidence ? (
            <p className={styles.muted}>
              Confiança {confidence} · {health.period.monthsUsed}{' '}
              {health.period.monthsUsed === 1 ? 'mês fechado' : 'meses fechados'} de {accounts}{' '}
              {accounts === 1 ? 'conta' : 'contas'}
              {health.coverage ? ` · ${health.coverage.available} de ${health.coverage.total} dimensões (${health.coverage.weightPercent}% do peso)` : ''}
            </p>
          ) : null}
        </div>
      </div>
      {evolution && evolution.months.some((m) => m.score !== null) ? (
        <ScoreLine
          title={`Evolução do score · ${basisRange(evolution)}`}
          data={evolution.months.map((m) => ({ month: m.month, score: m.score, estimated: m.estimated, partial: m.partial }))}
          caption="Meses estimados ou em andamento aparecem tracejados."
        />
      ) : null}
    </section>
  )
}

function basisRange(evolution: HealthEvolution): string {
  const first = evolution.months[0]?.month
  const last = evolution.months.at(-1)?.month
  return first && last ? `${monthLabel(first)}–${monthLabel(last)} ${last.slice(0, 4)}` : ''
}

function Sources({ health }: { health: Health }) {
  const sources = sourcesText(health.context?.sources ?? [])
  const basis = basisText(health)
  const updated = updatedAgo(health.freshness.computedAt)
  const notices: { key: string; warning?: boolean; text: string }[] = []
  if (health.freshness.state === 'RECALCULATING') {
    notices.push({ key: 'recalculating', text: 'Atualizando seu score: lançamentos recentes podem levar até um minuto para entrar.' })
  }
  if (health.period.partial) {
    notices.push({ key: 'partial', text: `${monthTitle(health.period.month).split(' de ')[0]} ainda está em andamento.` })
  }
  if (health.estimated) {
    notices.push({ key: 'estimated', warning: true, text: 'Score estimado: há menos de 3 meses fechados de dados.' })
  }
  return (
    <section className={styles.panel} aria-labelledby="health-sources-title">
      <h2 id="health-sources-title" className={styles.panelTitle}>
        Fontes
      </h2>
      <dl className={styles.facts}>
        <div>
          <dt>Período</dt>
          <dd>{basis ?? '—'}</dd>
        </div>
        <div>
          <dt>Fontes</dt>
          <dd>{sources ?? '—'}</dd>
        </div>
        <div>
          <dt>Atualizado</dt>
          <dd>{updated ?? '—'}</dd>
        </div>
      </dl>
      {notices.length > 0 ? (
        <div role="status" className={styles.notices}>
          {notices.map((n) => (
            <p key={n.key} className={`${styles.notice} ${n.warning ? styles.noticeWarning : ''}`}>
              {n.warning ? <CircleAlert size={18} aria-hidden="true" /> : <Info size={18} aria-hidden="true" />}
              <span>{n.text}</span>
            </p>
          ))}
        </div>
      ) : null}
    </section>
  )
}

function Dimensions({ health }: { health: Health }) {
  return (
    <section className={styles.section} aria-labelledby="health-dimensions-title">
      <h2 id="health-dimensions-title" className={styles.sectionTitle}>
        Dimensões
      </h2>
      <p className={styles.muted}>Cada dimensão vai de 0 a 100 e mostra o que mais influenciou a nota.</p>
      <ul className={styles.dimensions}>
        {health.dimensions.map((d) => (
          <li key={d.dimension}>
            <DimensionCard dimension={d} currency={health.currency} />
          </li>
        ))}
      </ul>
    </section>
  )
}

function DimensionCard({ dimension: d, currency }: { dimension: HealthDimension; currency: string }) {
  const id = useId()
  const [open, setOpen] = useState(false)
  return (
    <article className={styles.dimension} aria-labelledby={`${id}-name`}>
      <p className={styles.eyebrow}>{DIMENSION_SHORT[d.dimension]}</p>
      <div className={styles.dimensionHead}>
        <h3 id={`${id}-name`} className={styles.dimensionName}>
          {d.label}
        </h3>
        {d.status === 'OK' ? (
          <p className={styles.dimensionScore}>
            {d.score}
            <span className={styles.muted}>/100</span>
          </p>
        ) : (
          <span className={styles.badge}>{d.status === 'NOT_APPLICABLE' ? 'Não se aplica' : 'Dados insuficientes'}</span>
        )}
      </div>
      {d.status === 'OK' && d.score !== null ? (
        <svg className={styles.bar} viewBox="0 0 100 8" preserveAspectRatio="none" aria-hidden="true" focusable="false">
          <rect className={styles.barTrack} x={0} y={0} width={100} height={8} rx={4} />
          <rect className={styles.barValue} x={0} y={0} width={d.score} height={8} rx={4} />
        </svg>
      ) : null}
      <p>{d.explanation}</p>
      {d.attention ? (
        <p className={styles.attention}>
          <CircleAlert size={16} aria-hidden="true" />
          Ponto de atenção
        </p>
      ) : null}
      {d.evidence.length > 0 ? (
        <>
          <button
            type="button"
            className={styles.linkButton}
            aria-expanded={open}
            aria-controls={`${id}-details`}
            onClick={() => setOpen((v) => !v)}
          >
            Detalhes
            <ChevronDown size={16} aria-hidden="true" className={open ? styles.chevronOpen : undefined} />
          </button>
          <dl id={`${id}-details`} className={styles.evidence} hidden={!open}>
            {d.evidence.map((e) => (
              <div key={e.code} className={styles.evidenceRow}>
                <dt>{e.label}</dt>
                <dd>
                  <strong>{evidenceValue(e, currency)}</strong>
                  <span className={styles.muted}>
                    {' '}
                    · {evidencePeriod(e)} · {evidenceSource(e.source)}
                  </span>
                </dd>
              </div>
            ))}
          </dl>
        </>
      ) : null}
    </article>
  )
}

function Factors({ health }: { health: Health }) {
  const sustaining = health.factors.filter((f) => f.direction === 'SUSTAINING')
  const weighing = health.factors.filter((f) => f.direction === 'WEIGHING')
  return (
    <section className={styles.panel} aria-labelledby="health-factors-title">
      <h2 id="health-factors-title" className={styles.panelTitle}>
        Principais fatores
      </h2>
      <p className={styles.muted}>O que mais influenciou seu resultado</p>
      <div className={styles.factors}>
        <FactorList title="Sustentando o resultado" icon="plus" items={sustaining.map((f) => f.text)} />
        <FactorList title="Pesando no resultado" icon="minus" items={weighing.map((f) => f.text)} />
      </div>
    </section>
  )
}

/** Ícones neutros + / −, sem cor de julgamento (layout D-Health, 07 §3). */
function FactorList({ title, icon, items }: { title: string; icon: 'plus' | 'minus'; items: string[] }) {
  const Icon = icon === 'plus' ? Plus : Minus
  return (
    <div>
      <h3 className={styles.factorTitle}>{title}</h3>
      {items.length === 0 ? (
        <p className={styles.muted}>Nenhum fator neste mês.</p>
      ) : (
        <ul className={styles.factorList}>
          {items.map((text) => (
            <li key={text} className={styles.factor}>
              <Icon size={16} aria-hidden="true" className={styles.factorIcon} />
              <span>{text}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function NextSteps({ health }: { health: Health }) {
  // "Alimentação → insight" (D-Health, adendo ao ADR-0020): o passo da categoria leva à alta dela no mês, se houver.
  const hasCategoryStep = health.nextSteps.some((step) => step.kind === 'REVIEW_CATEGORY')
  const related = useRelatedInsights(hasCategoryStep ? { dimension: 'SPENDING' } : null)
  const spikes = related.status === 'success' && related.data ? related.data.items : []
  return (
    <section className={styles.panel} aria-labelledby="health-steps-title">
      <h2 id="health-steps-title" className={styles.panelTitle}>
        Próximos passos
      </h2>
      <p className={styles.muted}>Sugestões com base nos seus dados — você decide</p>
      <ul className={styles.steps}>
        {health.nextSteps.map((step) => {
          const link = nextStepLink(step, spikes)
          return (
            <li key={step.kind} className={styles.step}>
              <p className={styles.stepTitle}>{step.text}</p>
              <p>{step.evidence}</p>
              {link.href ? (
                <Link href={link.href} className={styles.stepLink}>
                  {link.label}
                  <ArrowRight size={16} aria-hidden="true" />
                </Link>
              ) : (
                <span className={styles.stepSoon} aria-disabled="true">{link.label} · Em breve</span>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
