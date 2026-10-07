import {
  ArrowDownLeft,
  ArrowLeftRight,
  ArrowUpRight,
  Circle,
  Landmark,
  PiggyBank,
  SlidersHorizontal,
  Undo2,
  Wallet,
  type LucideIcon,
} from 'lucide-react'
import type { OverviewAccount, OverviewTransaction } from '@/lib/api/types'
import type { SignDisplay } from '@/lib/format/money'

/**
 * Rótulos e ícones dos enums do contrato. O backend envia esses valores como texto e pode evoluir: qualquer
 * valor desconhecido cai em um rótulo e ícone NEUTROS, sem quebrar a renderização e sem inventar significado.
 * As tabelas são `Partial<Record<string, …>>` de propósito, para o TypeScript exigir o fallback.
 */

function lookup<T>(table: Partial<Record<string, T>>, key: string | null | undefined, fallback: T): T {
  return (key != null ? table[key] : undefined) ?? fallback
}

const ACCOUNT_TYPE_LABEL: Partial<Record<string, string>> = {
  CHECKING: 'Conta corrente',
  SAVINGS: 'Poupança',
  PAYMENT: 'Conta de pagamento',
  OTHER: 'Outra conta',
}

/**
 * Ícones: mapas estáticos acessados no componente com o fallback ao lado
 * (`ACCOUNT_TYPE_ICON[type] ?? FallbackAccountIcon`) — o React Compiler não aceita componente vindo de função.
 */
export const ACCOUNT_TYPE_ICON: Partial<Record<string, LucideIcon>> = {
  CHECKING: Landmark,
  SAVINGS: PiggyBank,
  PAYMENT: Wallet,
  OTHER: Wallet,
}
export const FallbackAccountIcon = Wallet

export const accountTypeLabel = (type: OverviewAccount['type']) => lookup(ACCOUNT_TYPE_LABEL, type, 'Conta')

const TRANSACTION_TYPE_LABEL: Partial<Record<string, string>> = {
  INCOME: 'Receita',
  EXPENSE: 'Despesa',
  REFUND: 'Reembolso',
  TRANSFER: 'Transferência',
}

const ADJUSTMENT_LABEL: Partial<Record<string, string>> = {
  INCREASE: 'Ajuste de entrada',
  DECREASE: 'Ajuste de saída',
}

export function transactionTypeLabel(t: Pick<OverviewTransaction, 'type' | 'adjustmentDirection'>): string {
  if (t.type === 'ADJUSTMENT') return lookup(ADJUSTMENT_LABEL, t.adjustmentDirection, 'Ajuste')
  return lookup(TRANSACTION_TYPE_LABEL, t.type, 'Movimentação')
}

export const TRANSACTION_TYPE_ICON: Partial<Record<string, LucideIcon>> = {
  INCOME: ArrowDownLeft,
  EXPENSE: ArrowUpRight,
  REFUND: Undo2,
  TRANSFER: ArrowLeftRight,
  ADJUSTMENT: SlidersHorizontal,
}
export const FallbackTransactionIcon = Circle

/** Tom do ícone (design: entrada verde, transferência azul, saída neutro). Desconhecido = neutro. */
export type Tone = 'success' | 'teal' | 'info' | 'neutral'
const TRANSACTION_TONE: Partial<Record<string, Tone>> = { INCOME: 'success', REFUND: 'teal', TRANSFER: 'info' }
export const transactionTone = (type: OverviewTransaction['type']) => lookup(TRANSACTION_TONE, type, 'neutral')

/** O sinal vem do `flow` do backend. Transferência e flow desconhecido: sem sinal (não afirmamos direção). */
const FLOW_SIGN: Partial<Record<string, SignDisplay>> = { INFLOW: 'inflow', OUTFLOW: 'outflow', TRANSFER: 'never' }
export const flowSign = (flow: OverviewTransaction['flow']) => lookup(FLOW_SIGN, flow, 'never')

/**
 * Selo de status. POSTED (realizada) não tem selo; qualquer outro status, inclusive desconhecido, é sinalizado
 * para não parecer realizado.
 */
const STATUS_LABEL: Partial<Record<string, string>> = {
  PENDING: 'Pendente',
  CANCELLED: 'Cancelada',
  REVERSED: 'Estornada',
}

export function statusLabel(status: OverviewTransaction['status']): string | null {
  if (status === 'POSTED') return null
  return lookup(STATUS_LABEL, status, 'Não realizada')
}

export const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`
