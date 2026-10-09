'use client'

import { useState } from 'react'
import type { MoneyDto } from '@/lib/api/types'
import { useKeyed } from '@/lib/use-keyed'
import { fetchAccount, fetchAllAccounts, fetchOverview, type Account } from './api'

export function useAllAccounts() {
  const [attempt, setAttempt] = useState(0)
  const { value } = useKeyed<Account[]>(`accounts#${attempt}`, async () => {
    const result = await fetchAllAccounts()
    return result.ok ? { status: 'success', data: result.data.items } : { status: 'error', failure: result }
  })
  return { ...value, retry: () => setAttempt((n) => n + 1) }
}

/** Conta da URL. `replace` aplica na hora a resposta de salvar, arquivar ou reativar. */
export function useAccount(id: string) {
  const [attempt, setAttempt] = useState(0)
  const { value, replace } = useKeyed<Account>(`${id}#${attempt}`, async () => {
    const result = await fetchAccount(id)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })
  return { ...value, replace, attempt, retry: () => setAttempt((n) => n + 1) }
}

export interface Balances {
  /** Data-base dos saldos (fim do período do Overview; no mês corrente, hoje). */
  asOf: string
  total: MoneyDto | null
  /** Só contas ativas aparecem no Overview; arquivadas ficam de fora do mapa. */
  byAccount: Map<string, MoneyDto | null>
}

/**
 * Saldos já calculados pelo backend (Overview do mês corrente). Falhar aqui não derruba a tela de contas: ela
 * mostra as contas sem saldo ("—") e avisa — estado parcial.
 */
export function useBalances() {
  const { value } = useKeyed<Balances>('overview', async () => {
    const result = await fetchOverview()
    if (!result.ok) return { status: 'error', failure: result }
    const { summary, accounts } = result.data
    return {
      status: 'success',
      data: {
        asOf: summary.balanceAsOf,
        total: summary.totalBalance,
        byAccount: new Map(accounts.map((account) => [account.id, account.balance])),
      },
    }
  })
  return value
}
