/**
 * "Hoje" no fuso de negócio (ADR-0006 §10: `shf.overview.zone`, padrão `America/Sao_Paulo`), não no fuso do
 * navegador nem em UTC: entre 21h e 24h de Brasília, UTC já está no dia seguinte. Só serve para valores
 * padrão e atalhos de período na UI; o backend continua sendo quem decide o que entra no saldo.
 */
export const BUSINESS_ZONE = 'America/Sao_Paulo'

const ISO_DAY = new Intl.DateTimeFormat('en-CA', { timeZone: BUSINESS_ZONE, year: 'numeric', month: '2-digit', day: '2-digit' })

/** YYYY-MM-DD de hoje no fuso de negócio. */
export function businessToday(now: Date = new Date()): string {
  return ISO_DAY.format(now)
}

const pad = (n: number) => String(n).padStart(2, '0')

function lastDay(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate()
}

/** Mês civil de uma data YYYY-MM-DD com `offset` meses (0 = o próprio mês, -1 = anterior). */
export function monthRange(isoDay: string, offset = 0): { from: string; to: string } {
  const [y, m] = isoDay.split('-').map(Number) as [number, number]
  const index = y * 12 + (m - 1) + offset
  const year = Math.floor(index / 12)
  const month = (index % 12) + 1
  return { from: `${year}-${pad(month)}-01`, to: `${year}-${pad(month)}-${pad(lastDay(year, month))}` }
}

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/

/** Data de calendário válida no formato YYYY-MM-DD (rejeita 2026-02-30). */
export function isIsoDate(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const match = ISO_DATE.exec(value)
  if (!match) return false
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])]
  return month >= 1 && month <= 12 && day >= 1 && day <= lastDay(year, month)
}
