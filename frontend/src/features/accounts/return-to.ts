import { isUuid } from '@/lib/uuid'

/**
 * Para onde voltar depois de criar uma conta. Só destinos internos FIXOS, escolhidos por nome — nunca uma URL
 * vinda da query (open redirect). Valor desconhecido = sem retorno.
 */
export type ReturnTo = 'import'

export function parseReturnTo(value: unknown): ReturnTo | null {
  return value === 'import' ? 'import' : null
}

/** Conta criada: a importação já abre com ela selecionada; sem retorno, a lista de contas. */
export function destinationAfterCreate(returnTo: ReturnTo | null, accountId: string): string {
  if (returnTo === 'import' && isUuid(accountId)) return `/import?accountId=${accountId}`
  return '/accounts'
}

export const cancelDestination = (returnTo: ReturnTo | null) => (returnTo === 'import' ? '/import' : '/accounts')
