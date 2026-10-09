import { isUuid } from '@/lib/uuid'

/**
 * Para onde voltar depois de registrar uma transação. Só destinos internos FIXOS, escolhidos por nome — nunca
 * uma URL vinda da query (open redirect). Sem retorno conhecido: a lista de transações.
 */
export type ReturnTo = { kind: 'account'; accountId: string } | { kind: 'transactions' }

export function parseReturnTo(returnTo: unknown, accountId: unknown): ReturnTo {
  return returnTo === 'account' && isUuid(accountId) ? { kind: 'account', accountId } : { kind: 'transactions' }
}

export const backHref = (to: ReturnTo) => (to.kind === 'account' ? `/accounts/${to.accountId}` : '/transactions')
export const backLabel = (to: ReturnTo) => (to.kind === 'account' ? 'Conta' : 'Transações')

/** Destino depois de registrar, com um aviso por nome (sem valor, descrição ou id na URL). */
export function destinationAfterCreate(to: ReturnTo, status: 'POSTED' | 'PENDING'): string {
  const flag = `registered=${status === 'PENDING' ? 'pending' : 'posted'}`
  return `${backHref(to)}?${flag}`
}

/** Aviso da tela de destino (`?registered=`). Valor desconhecido = sem aviso. */
export function registeredNotice(value: string | null | undefined): string | null {
  if (value === 'posted') return 'Transação registrada. O saldo da conta e da Home já consideram este lançamento.'
  if (value === 'pending') return 'Transação registrada como pendente. Ela entra no saldo quando for efetivada.'
  return null
}
