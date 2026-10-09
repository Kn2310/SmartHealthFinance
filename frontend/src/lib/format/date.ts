/** Datas de negócio (YYYY-MM-DD) tratadas como texto: sem `Date` local, logo sem deslocamento de fuso. */

const MONTHS = [
  'janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho',
  'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro',
]
const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/

function parts(iso: string): { year: number; month: number; day: number } | null {
  const match = ISO_DATE.exec(iso)
  if (!match) return null
  const month = Number(match[2])
  const day = Number(match[3])
  if (month < 1 || month > 12 || day < 1 || day > 31) return null
  return { year: Number(match[1]), month, day }
}

const monthName = (m: number) => MONTHS[m - 1] ?? ''
const monthAbbr = (m: number) => monthName(m).slice(0, 3)

/** "27 set." */
export function formatShortDate(iso: string): string {
  const p = parts(iso)
  return p ? `${p.day} ${monthAbbr(p.month)}.` : iso
}

/** "1 a 27 de setembro"; entre meses "28 de ago. a 3 de set."; entre anos inclui o ano. */
export function formatPeriodRange(from: string, to: string): string {
  const a = parts(from)
  const b = parts(to)
  if (!a || !b) return `${from} a ${to}`
  if (a.year === b.year && a.month === b.month) return `${a.day} a ${b.day} de ${monthName(b.month)}`
  if (a.year === b.year) return `${a.day} de ${monthAbbr(a.month)}. a ${b.day} de ${monthAbbr(b.month)}.`
  return `${a.day} de ${monthAbbr(a.month)}. de ${a.year} a ${b.day} de ${monthAbbr(b.month)}. de ${b.year}`
}

export const MAX_CUSTOM_PERIOD_DAYS = 366

function toDayNumber(iso: string): number | null {
  const p = parts(iso)
  return p ? Math.floor(Date.UTC(p.year, p.month - 1, p.day) / 86_400_000) : null
}

export type CustomRangeError = 'REQUIRED' | 'INVALID' | 'ORDER' | 'TOO_LONG'

/** Só conveniência de UX (formato, ordem, limite); a regra de domínio continua no backend (400). */
export function validateCustomRange(from: string, to: string): CustomRangeError | null {
  if (!from || !to) return 'REQUIRED'
  const a = toDayNumber(from)
  const b = toDayNumber(to)
  if (a === null || b === null) return 'INVALID'
  if (b < a) return 'ORDER'
  if (b - a + 1 > MAX_CUSTOM_PERIOD_DAYS) return 'TOO_LONG'
  return null
}

const WEEKDAYS = ['domingo', 'segunda', 'terça', 'quarta', 'quinta', 'sexta', 'sábado']

/** Dia da semana de uma data de calendário (sem fuso: a data de negócio já é o dia). */
function weekday(p: { year: number; month: number; day: number }): string {
  return WEEKDAYS[new Date(Date.UTC(p.year, p.month - 1, p.day)).getUTCDay()] ?? ''
}

/** Cabeçalho de grupo da lista: "22 set · terça". */
export function formatDayHeading(iso: string): string {
  const p = parts(iso)
  return p ? `${p.day} ${monthAbbr(p.month)} · ${weekday(p)}` : iso
}

/** "terça, 22 de setembro de 2026". */
export function formatLongDate(iso: string): string {
  const p = parts(iso)
  return p ? `${weekday(p)}, ${p.day} de ${monthName(p.month)} de ${p.year}` : iso
}
