import { Fragment } from 'react'
import type { MoneyDto } from '@/lib/api/types'
import { formatMoney, formatMoneySpoken, type SignDisplay } from '@/lib/format/money'

interface MoneyProps {
  value: MoneyDto | null | undefined
  sign?: SignDisplay
  className?: string
}

const NBSP = ' '

/**
 * Texto visual do valor. Sinal, símbolo e dígitos ficam unidos por espaço não separável e a única oportunidade
 * de quebra é depois de um separador de milhar ("R$ 12.345.<quebra>678,90"): valores grandes cabem em colunas
 * estreitas sem estourar a tela e sem reduzir a fonte, e nunca separam o sinal ou os centavos.
 */
function Breakable({ text }: { text: string }) {
  const parts = text.replaceAll(' ', NBSP).split(/(?<=\.)/)
  return parts.map((part, index) => (
    <Fragment key={index}>
      {index > 0 ? <wbr /> : null}
      {part}
    </Fragment>
  ))
}

/**
 * Valor monetário. `null` mostra "—" (ausência de dado), nunca R$ 0,00.
 * Leitores de tela recebem o sinal por extenso ("menos R$ 420,00") e o texto visual fica oculto para eles.
 */
export function Money({ value, sign = 'auto', className }: MoneyProps) {
  const visual = formatMoney(value, { sign })
  if (visual === null) {
    return (
      <span className={className}>
        <span aria-hidden="true">—</span>
        <span className="sr-only">sem dados</span>
      </span>
    )
  }
  const spoken = formatMoneySpoken(value, { sign })
  const classes = ['money', className].filter(Boolean).join(' ')
  // Sem sinal, o texto visual já é lido corretamente.
  if (spoken === visual) {
    return (
      <span className={classes}>
        <Breakable text={visual} />
      </span>
    )
  }
  return (
    <span className={classes}>
      <span aria-hidden="true">
        <Breakable text={visual} />
      </span>
      <span className="sr-only">{spoken}</span>
    </span>
  )
}
