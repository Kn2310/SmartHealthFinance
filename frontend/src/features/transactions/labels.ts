import type { SignDisplay } from '@/lib/format/money'
import type { Transaction } from './api'

/**
 * Rótulos e sinais de apresentação do que o backend já decidiu (tipo, direção, status). Nenhum valor é
 * calculado aqui; o saldo vem sempre do Overview (ADR-0006). Valores desconhecidos caem em textos neutros e
 * sem sinal — a UI nunca afirma uma direção que não conhece.
 */

function lookup<T>(table: Partial<Record<string, T>>, key: string | null | undefined, fallback: T): T {
  return (key != null ? table[key] : undefined) ?? fallback
}

/**
 * Sinal do valor na lista, na mesma semântica do contrato de saldo (`TransactionType.originEffect`):
 * receita/reembolso entram, despesa sai, ajuste conforme a direção. Transferência: só com uma conta de
 * referência (página da conta) dá para dizer se entra ou sai; na lista geral, sem sinal.
 */
export function transactionSign(
  t: Pick<Transaction, 'type' | 'adjustmentDirection' | 'accountId' | 'destinationAccountId'>,
  perspectiveAccountId?: string,
): SignDisplay {
  switch (t.type) {
    case 'INCOME':
    case 'REFUND':
      return 'inflow'
    case 'EXPENSE':
      return 'outflow'
    case 'ADJUSTMENT':
      return lookup<SignDisplay>({ INCREASE: 'inflow', DECREASE: 'outflow' }, t.adjustmentDirection, 'never')
    case 'TRANSFER':
      if (perspectiveAccountId && t.destinationAccountId === perspectiveAccountId) return 'inflow'
      if (perspectiveAccountId && t.accountId === perspectiveAccountId) return 'outflow'
      return 'never'
    default:
      return 'never'
  }
}

/** Selo de status (sempre com texto, nunca só cor). */
const STATUS: Partial<Record<string, { label: string; tone: 'success' | 'warning' | 'neutral' }>> = {
  POSTED: { label: 'Lançada', tone: 'success' },
  PENDING: { label: 'Pendente', tone: 'warning' },
  CANCELLED: { label: 'Cancelada', tone: 'neutral' },
  REVERSED: { label: 'Estornada', tone: 'neutral' },
}
export const statusBadge = (status: string) => lookup(STATUS, status, { label: 'Não realizada', tone: 'neutral' as const })

/** O que o status significa para o saldo (texto do detalhe). */
const STATUS_EFFECT: Partial<Record<string, string>> = {
  POSTED: 'Já faz parte do saldo da conta e do saldo total.',
  PENDING: 'Ainda não entra no saldo. Efetive quando o valor for confirmado, ou cancele se não for acontecer.',
  CANCELLED: 'Não afeta o saldo. O registro fica guardado no histórico.',
  REVERSED: 'Estornada: saiu do saldo. O registro fica guardado no histórico.',
}
export const statusEffect = (status: string) => lookup(STATUS_EFFECT, status, 'Não afeta o saldo.')

/** Opções dos filtros (ordem de exibição). */
export const TYPE_OPTIONS = [
  ['INCOME', 'Receitas'],
  ['EXPENSE', 'Despesas'],
  ['ADJUSTMENT', 'Ajustes'],
  ['TRANSFER', 'Transferências'],
  ['REFUND', 'Reembolsos'],
] as const

export const STATUS_OPTIONS = [
  ['POSTED', 'Lançadas'],
  ['PENDING', 'Pendentes'],
  ['CANCELLED', 'Canceladas'],
  ['REVERSED', 'Estornadas'],
] as const

export const SOURCE_LABEL: Partial<Record<string, string>> = { MANUAL: 'Lançamento manual', IMPORT: 'Importado de extrato' }
