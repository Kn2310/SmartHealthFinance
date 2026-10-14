import type { MoneyDto } from '@/lib/api/types'

/**
 * Geometria dos gráficos (design/specs/02-components, "Gráficos"). Os valores chegam como strings decimais exatas do
 * backend; aqui eles viram `number` SÓ para calcular altura/largura em pixels. Nenhum valor exibido, somado ou
 * comparado sai daqui: o texto e as tabelas usam o `MoneyDto` original (o frontend só formata).
 */
export function magnitude(money: MoneyDto | null | undefined): number {
  if (!money) return 0
  const value = Number(money.amount)
  return Number.isFinite(value) ? Math.abs(value) : 0
}

/** Escala proporcional de 0..max para 0..size, com 0 seguro (sem divisão por zero). */
export function scale(value: number, max: number, size: number): number {
  if (max <= 0 || value <= 0) return 0
  return Math.max(0, Math.min(size, (value / max) * size))
}

/** Arredonda a geometria para 0,1 px: atributos SVG estáveis entre servidor e cliente (sem diferença na hidratação). */
export function px(value: number): number {
  return Math.round(value * 10) / 10
}

const MONTHS = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez']

/** "2026-09" → "set" (eixo); com {@code withYear}, "set/26". */
export function monthLabel(month: string, withYear = false): string {
  const [year, m] = month.split('-')
  const name = MONTHS[Number(m) - 1] ?? month
  return withYear && year ? `${name}/${year.slice(2)}` : name
}

const LONG_MONTHS = [
  'janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho',
  'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro',
]

/** "2026-09" → "setembro de 2026". */
export function longMonthLabel(month: string): string {
  const [year, m] = month.split('-')
  return `${LONG_MONTHS[Number(m) - 1] ?? month} de ${year}`
}
