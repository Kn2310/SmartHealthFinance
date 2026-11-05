import type { ForecastValue } from '@/lib/api/types'
import { formatMoneyApprox, formatMoneyApproxSpoken } from '@/lib/format/money'
import { Money } from './Money'
import styles from './ProjectedValue.module.css'

/** Selo "Estimado": borda tracejada (Trust UX). Decorativo para leitores de tela: o texto alternativo já diz "estimado". */
export function EstimateBadge({ className }: { className?: string }) {
  return (
    <span className={[styles.badge, styles.badgeEstimated, className].filter(Boolean).join(' ')} aria-hidden="true">
      Estimado
    </span>
  )
}

/** Selo de fato: "Confirmado" ou, quando o valor ainda pode mudar até o fechamento, "Parcial". */
export function FactBadge({ partial = false, className }: { partial?: boolean; className?: string }) {
  const tone = partial ? styles.badgePartial : styles.badgeFact
  return <span className={[styles.badge, tone, className].filter(Boolean).join(' ')}>{partial ? 'Parcial' : 'Confirmado'}</span>
}

interface ProjectedValueProps {
  value: ForecastValue
  /** Mostra o selo ao lado do valor (padrão). */
  badge?: boolean
  /** Mostra "entre R$ a e R$ b" abaixo de uma estimativa (padrão). */
  range?: boolean
  className?: string
}

/**
 * Valor projetado do Forecast (ADR-0033 §1). `ESTIMATED`: "≈ R$ 4.664" sem centavos, sublinhado tracejado, selo
 * "Estimado" e a faixa; `FACT`: centavos e selo "Confirmado" ("Parcial" se `partial`). Leitores de tela recebem
 * "aproximadamente R$ X, entre R$ a e R$ b, estimado"; "≈", tracejado e faixa visual são `aria-hidden`.
 * Formatação apenas: nenhum valor é somado nem derivado aqui (o arredondamento é só de exibição).
 */
export function ProjectedValue({ value, badge = true, range = true, className }: ProjectedValueProps) {
  if (value.basis === 'FACT') {
    return (
      <span className={[styles.wrap, className].filter(Boolean).join(' ')} data-basis="fact">
        <span className={styles.line}>
          <Money value={value.value} />
          {badge ? <FactBadge partial={value.partial} /> : null}
        </span>
      </span>
    )
  }
  const main = formatMoneyApprox(value.value)
  const low = formatMoneyApprox(value.range.low)
  const high = formatMoneyApprox(value.range.high)
  if (main === null || low === null || high === null) return <Money value={null} className={className} />
  const spoken = `aproximadamente ${formatMoneyApproxSpoken(value.value)}, entre ${formatMoneyApproxSpoken(value.range.low)} e ${formatMoneyApproxSpoken(value.range.high)}, estimado`
  return (
    <span className={[styles.wrap, className].filter(Boolean).join(' ')} data-basis="estimated">
      <span className={styles.line}>
        <span className={styles.visual} aria-hidden="true">
          <span className={styles.approx}>≈</span> <span className={styles.estimated}>{main}</span>
        </span>
        <span className="sr-only">{spoken}</span>
        {badge ? <EstimateBadge /> : null}
      </span>
      {range ? (
        <span className={styles.range} aria-hidden="true">
          entre {low} e {high}
        </span>
      ) : null}
    </span>
  )
}
