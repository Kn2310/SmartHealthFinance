'use client'

import { useInsightHighlights } from './api'

/**
 * Contagem de insights Importantes ativos na navegação (sidebar/trilho e M-More, aprovado em 2026-10-19). Some quando
 * não há nenhum, enquanto carrega ou se a chamada falhar: a navegação nunca depende dela.
 */
export function InsightsBadge({ className, variant = 'count' }: { className?: string; variant?: 'count' | 'text' }) {
  const highlights = useInsightHighlights()
  const count = highlights.status === 'success' ? (highlights.data?.importantCount ?? 0) : 0
  if (count <= 0) return null
  const label = `${count} ${count === 1 ? 'importante' : 'importantes'}`
  if (variant === 'text') return <span className={className}>{label}</span>
  return (
    <span className={className}>
      <span aria-hidden="true">{count}</span>
      <span className="sr-only">, {label}</span>
    </span>
  )
}
