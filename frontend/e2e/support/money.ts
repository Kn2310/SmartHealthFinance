/**
 * Aritmética exata do teste: centavos em `bigint`, nunca `number` com casas decimais. O resultado esperado é
 * calculado aqui, de forma independente, e comparado com o que o backend calculou e a Home exibiu.
 */

/** "1.234,56" / "-86,40" / "6.500,00" (formato do extrato e do formulário) → centavos. */
export function cents(text: string): bigint {
  const match = /^(-?)([\d.]+),(\d{2})$/.exec(text.trim())
  if (!match) throw new Error(`valor fora do formato pt-BR com 2 casas: ${text}`)
  const value = BigInt(match[2]!.replaceAll('.', '') + match[3]!)
  return match[1] ? -value : value
}

export const sum = (values: bigint[]) => values.reduce((total, value) => total + value, 0n)

/** Centavos → "R$ 1.234,56" (sem sinal), como `formatMoney` exibe. */
export function brl(value: bigint): string {
  const abs = value < 0n ? -value : value
  const integer = (abs / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, '.')
  const fraction = (abs % 100n).toString().padStart(2, '0')
  return `R$ ${integer},${fraction}`
}
