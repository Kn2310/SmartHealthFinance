import type { MoneyDto } from '@/lib/api/types'

/**
 * Formatação de dinheiro SEM aritmética em `number`: o backend envia strings decimais exatas
 * (BigDecimal) e aqui só reagrupamos os dígitos. `null` (sem dados) nunca vira zero.
 */

const DECIMAL = /^(-)?(\d+)(?:\.(\d+))?$/

export interface ParsedAmount {
  negative: boolean
  integer: string
  fraction: string
}

function isZeroDigits(integer: string, fraction: string): boolean {
  return /^0*$/.test(integer) && /^0*$/.test(fraction)
}

export function parseAmount(raw: string): ParsedAmount | null {
  const match = DECIMAL.exec(raw.trim())
  if (!match) return null
  const integer = (match[2] ?? '0').replace(/^0+(?=\d)/, '')
  const fraction = match[3] ?? ''
  return { negative: match[1] === '-' && !isZeroDigits(integer, fraction), integer, fraction }
}

const SYMBOLS: Record<string, string> = { BRL: 'R$' }

function group(integer: string): string {
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, '.')
}

/**
 * - `auto`: marca só negativos ("− R$ 420,00");
 * - `always`: marca também positivos ("+ R$ 1.250,00"); zero fica sem sinal;
 * - `never`: mostra o módulo, sem sinal (ex.: transferências);
 * - `inflow` / `outflow`: o sinal vem da semântica do campo (o backend envia despesa como valor positivo).
 */
/**
 * Centavos com no mínimo 2 casas. Casas além da 2ª que não sejam zero são PRESERVADAS ("10.125" → "10,125"):
 * nunca truncar nem arredondar aqui. Hoje BRL só aceita 2 casas no domínio (Transaction: TOO_MANY_DECIMALS),
 * mas o contrato (`Money`, escala 4) permitiria mais; se isso mudar, o valor aparece exato em vez de errado.
 */
function fractionDigits(fraction: string): string {
  const significant = fraction.replace(/0+$/, '')
  return significant.length <= 2 ? (fraction + '00').slice(0, 2) : significant
}

export type SignDisplay = 'auto' | 'always' | 'never' | 'inflow' | 'outflow'

/** "R$ 7.627,52". Retorna `null` quando não há dado (null ≠ zero). */
export function formatMoney(
  money: MoneyDto | null | undefined,
  { sign = 'auto' }: { sign?: SignDisplay } = {},
): string | null {
  if (!money) return null
  const parsed = parseAmount(money.amount)
  if (!parsed) return null
  const text = `${SYMBOLS[money.currency] ?? money.currency} ${group(parsed.integer)},${fractionDigits(parsed.fraction)}`
  if (sign === 'never') return text
  if (isZeroDigits(parsed.integer, parsed.fraction)) return text
  if (sign === 'inflow') return `+ ${text}`
  if (sign === 'outflow') return `− ${text}`
  if (parsed.negative) return `− ${text}`
  return sign === 'always' ? `+ ${text}` : text
}

/** Texto para leitores de tela: o sinal vira palavra ("menos R$ 420,00"). */
export function formatMoneySpoken(
  money: MoneyDto | null | undefined,
  options: { sign?: SignDisplay } = {},
): string | null {
  const text = formatMoney(money, options)
  if (text === null) return null
  if (text.startsWith('− ')) return `menos ${text.slice(2)}`
  if (text.startsWith('+ ')) return `mais ${text.slice(2)}`
  return text
}

export function isNegative(money: MoneyDto | null | undefined): boolean {
  return money ? (parseAmount(money.amount)?.negative ?? false) : false
}
