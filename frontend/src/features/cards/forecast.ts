import type { ForecastInvoicesResponse, ForecastValue, MoneyDto } from '@/lib/api/types'
import type { FutureInvoices } from './api'

type FutureMonth = FutureInvoices['months'][number]

/** O trecho estimado de um total: a API manda `estimatedPart`; a faixa da API é a do total e fica na linha do total. */
export const estimatedPartOf = (total: ForecastValue): ForecastValue => ({
  ...total,
  basis: 'ESTIMATED',
  value: total.estimatedPart,
  partial: false,
})

/** Uma fatura futura para a tela: comprometido (fato) e, quando há estimativa, as novas compras e o total. */
export interface FutureRow {
  dueMonth: string
  committed: MoneyDto
  /** O total estimado da API; `null` quando o mês só tem fatos. */
  estimate: ForecastValue | null
  /** O valor do mês como a API devolve (fato ou "≈"). */
  total: MoneyDto
  installments: FutureMonth['installments']
}

/**
 * Linhas de D-FutureInvoices (ADR-0033 §4). Com estimativa no Forecast, as faturas `NEXT` e `FUTURE` dele; sem ela
 * (`PARTIAL_CARD_HISTORY`, sem snapshot, histórico curto ou erro), só os fatos do próprio Cards, como antes. Nada é
 * somado: comprometido, novas compras e total vêm da API.
 */
export function futureRows(future: FutureInvoices | null, forecast: ForecastInvoicesResponse | null): FutureRow[] {
  const projected = (forecast?.invoices ?? []).filter((invoice) => invoice.kind === 'NEXT' || invoice.kind === 'FUTURE')
  if (projected.some((invoice) => invoice.total.basis === 'ESTIMATED')) {
    return projected.map((invoice) => ({
      dueMonth: invoice.dueMonth,
      committed: invoice.total.basis === 'ESTIMATED' ? invoice.total.factPart : invoice.total.value,
      estimate: invoice.total.basis === 'ESTIMATED' ? invoice.total : null,
      total: invoice.total.value,
      installments: future?.months.find((month) => month.dueMonth === invoice.dueMonth)?.installments ?? [],
    }))
  }
  return (future?.months ?? []).map((month) => ({
    dueMonth: month.dueMonth,
    committed: month.committed,
    estimate: null,
    total: month.committed,
    installments: month.installments,
  }))
}
