'use client'

import { ArrowRight } from 'lucide-react'
import Link from 'next/link'
import { ScoreRing } from '@/components/charts/ScoreRing'
import { Skeleton } from '@/components/ui/Skeleton'
import type { Health } from '@/lib/api/types'
import { useHealth } from './api'
import { DIMENSION_SHORT, basisText, updatedAgo, variationHeadline } from './format'
import { MethodTrigger } from './MethodDrawer'
import styles from './Health.module.css'

/**
 * "Como estou?" na Home (D-Home / M-Home): o único bloco Navy (07 §1), sempre visível e sempre do mês corrente —
 * não segue o seletor de período da Visão geral. "Score nunca sozinho" (07 §2): ring, variação, frase, 6 dimensões,
 * base/atualização e "Como calculamos?". Só exibe o snapshot do backend.
 */
export function HealthCard() {
  const health = useHealth()
  return (
    <section className={styles.hero} aria-labelledby="health-card-title">
      <div className={styles.heroTop}>
        <h2 id="health-card-title" className={styles.eyebrowDark}>
          Financial Health
        </h2>
        <MethodTrigger className={styles.heroLink} />
      </div>
      {health.status === 'loading' ? (
        <HeroSkeleton />
      ) : health.status === 'error' ? (
        <div role="alert" className={styles.heroMessage}>
          <p className={styles.heroHeadline}>Não conseguimos carregar seu score agora.</p>
          <p className={styles.heroText}>Seus dados estão seguros. Tente de novo em instantes.</p>
          <button type="button" className={styles.heroButton} onClick={health.retry}>
            Tentar novamente
          </button>
        </div>
      ) : health.data ? (
        <HeroContent health={health.data} />
      ) : null}
    </section>
  )
}

function HeroSkeleton() {
  return (
    <div className={styles.heroBody} role="status" aria-busy="true">
      <span className="sr-only">Carregando seu score…</span>
      <Skeleton width="sm" height="value" tone="dark" />
      <Skeleton width="lg" height="caption" tone="dark" />
      <Skeleton width="md" height="caption" tone="dark" />
    </div>
  )
}

function HeroContent({ health }: { health: Health }) {
  if (health.state === 'NO_DATA') {
    return (
      <div className={styles.heroMessage}>
        <p className={styles.heroHeadline}>
          {health.freshness.state === 'NOT_BUILT' ? 'Estamos calculando seu score…' : 'Seu score aparece em breve'}
        </p>
        <p className={styles.heroText}>
          O Financial Health compara você com você mesmo(a). Ele aparece depois do primeiro mês fechado com receitas
          ou despesas lançadas.
        </p>
      </div>
    )
  }

  const ready = health.state === 'READY'
  const headline = ready
    ? (variationHeadline(health.variation, health.period.month) ?? 'Seu primeiro score')
    : 'Ainda não dá para calcular o score'
  const text = ready
    ? health.summary
    : `Com os dados de hoje, ${health.coverage?.available ?? 0} de 6 dimensões têm nota. O score aparece quando o fluxo de caixa e mais uma dimensão tiverem dados.`
  const basis = basisText(health)
  const updated = updatedAgo(health.freshness.computedAt)

  return (
    <>
      <div className={styles.heroBody}>
        <ScoreRing score={health.score} estimated={health.estimated} tone="dark" size="md" />
        <div className={styles.heroCopy}>
          <p className={styles.heroHeadline}>{headline}</p>
          {text ? <p className={styles.heroText}>{text}</p> : null}
          {ready && health.estimated ? (
            <p className={styles.heroBadge}>≈ Estimado · menos de 3 meses fechados</p>
          ) : null}
        </div>
      </div>
      <ul className={styles.heroGrid} aria-label="Dimensões">
        {health.dimensions.map((d) => (
          <li key={d.dimension} className={styles.heroCell}>
            <span className={styles.heroCellName}>{DIMENSION_SHORT[d.dimension]}</span>
            <span className={styles.heroCellValue}>
              {d.status === 'OK' ? d.score : '—'}
              {d.status === 'INSUFFICIENT_DATA' ? <span className="sr-only"> dados insuficientes</span> : null}
            </span>
          </li>
        ))}
      </ul>
      <div className={styles.heroFooter}>
        <Link href="/home/health" className={styles.heroButton}>
          Entender meu resultado
          <ArrowRight size={18} aria-hidden="true" />
        </Link>
        <span className={styles.heroMeta}>
          {health.freshness.state === 'RECALCULATING'
            ? 'Atualizando…'
            : [basis ? `Base: ${basis}` : null, updated ? `atualizado ${updated}` : null].filter(Boolean).join(' · ')}
        </span>
      </div>
    </>
  )
}
