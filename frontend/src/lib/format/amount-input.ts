/**
 * Valor digitado pela pessoa ("1.234,56") → string decimal da API ("1234.56"), SEM passar por `number`:
 * só manipulação de texto, então não há erro de ponto flutuante nem arredondamento. A regra de verdade
 * (positivo, casas da moeda, limite do NUMERIC) continua no backend; isto é conveniência de UX.
 *
 * Formatos aceitos (pt-BR): "1234", "1234,5", "1.234,56", "R$ 1.234,56". Ponto seguido de 1–2 dígitos, sem
 * vírgula, também é decimal ("10.5", "1234.56"); ponto seguido de 3 dígitos é milhar ("1.234").
 */

export type AmountInputError = 'REQUIRED' | 'INVALID_FORMAT' | 'TOO_MANY_DECIMALS' | 'NOT_POSITIVE' | 'TOO_LARGE'

/** `Money.MAX_INTEGER_DIGITS` do backend (NUMERIC(19,4)). */
const MAX_INTEGER_DIGITS = 15
/** Casas de BRL (o domínio recusa mais que a moeda). */
const CURRENCY_DECIMALS = 2

const GROUPED = /^\d{1,3}(\.\d{3})+$/
const PLAIN = /^\d+$/

export function parseAmountInput(raw: string): { ok: true; value: string } | { ok: false; error: AmountInputError } {
  const text = raw.replace(/^\s*R\$\s*/i, '').replace(/\s+/g, '')
  if (text === '') return { ok: false, error: 'REQUIRED' }
  if (text.startsWith('-')) return { ok: false, error: 'NOT_POSITIVE' }

  let integer: string
  let fraction = ''
  if (text.includes(',')) {
    const [int, frac, ...rest] = text.split(',')
    if (rest.length > 0 || frac === undefined || !/^\d+$/.test(frac)) return { ok: false, error: 'INVALID_FORMAT' }
    integer = int ?? ''
    fraction = frac
    if (GROUPED.test(integer)) integer = integer.replaceAll('.', '')
    else if (!PLAIN.test(integer)) return { ok: false, error: 'INVALID_FORMAT' }
  } else if (GROUPED.test(text)) {
    integer = text.replaceAll('.', '')
  } else if (/^\d+\.\d{1,2}$/.test(text)) {
    ;[integer, fraction] = text.split('.') as [string, string]
  } else if (PLAIN.test(text)) {
    integer = text
  } else {
    return { ok: false, error: 'INVALID_FORMAT' }
  }

  integer = integer.replace(/^0+(?=\d)/, '')
  const significant = fraction.replace(/0+$/, '')
  if (significant.length > CURRENCY_DECIMALS) return { ok: false, error: 'TOO_MANY_DECIMALS' }
  if (integer.length > MAX_INTEGER_DIGITS) return { ok: false, error: 'TOO_LARGE' }
  if (/^0+$/.test(integer) && significant === '') return { ok: false, error: 'NOT_POSITIVE' }

  return { ok: true, value: `${integer}.${(significant + '00').slice(0, CURRENCY_DECIMALS)}` }
}
