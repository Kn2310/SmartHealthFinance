'use client'

import { useState } from 'react'
import type { Failure } from '@/lib/bff-client'
import { useKeyed } from '@/lib/use-keyed'
import {
  fetchAllAccounts,
  fetchTransaction,
  fetchTransactions,
  listSearch,
  type Account,
  type ListParams,
  type Transaction,
  type TransactionPage,
} from './api'

type More = { key: string; items: Transaction[]; page: number; loading: boolean; failure: Failure | null }

/**
 * Lista paginada por offset ("Carregar mais"). Trocar qualquer filtro muda a chave: a lista volta ao início e
 * páginas extras de outra chave são ignoradas. `retry` recarrega do início.
 */
export function useTransactionPages(params: ListParams) {
  const [attempt, setAttempt] = useState(0)
  const key = `${listSearch(params)}#${attempt}`
  const first = useKeyed<TransactionPage>(key, async () => {
    const result = await fetchTransactions(params)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  }).value
  const [more, setMore] = useState<More | null>(null)
  const extra = more?.key === key ? more : null

  const items = first.status === 'success' ? [...first.data.items, ...(extra?.items ?? [])] : []
  const total = first.status === 'success' ? first.data.totalItems : 0

  async function loadMore() {
    if (first.status !== 'success' || extra?.loading) return
    const page = (extra?.page ?? 0) + 1
    setMore({ key, items: extra?.items ?? [], page: extra?.page ?? 0, loading: true, failure: null })
    const result = await fetchTransactions(params, page)
    setMore((current) => {
      if (current?.key !== key) return current
      if (!result.ok) return { ...current, loading: false, failure: result }
      // Uma transação nova no meio da paginação desloca o offset: ids repetidos não entram duas vezes.
      const seen = new Set([...first.data.items, ...current.items].map((item) => item.id))
      return { key, items: [...current.items, ...result.data.items.filter((item) => !seen.has(item.id))], page, loading: false, failure: null }
    })
  }

  return {
    first,
    items,
    total,
    hasMore: first.status === 'success' && items.length < total && (extra?.page ?? 0) + 1 < Math.ceil(total / first.data.pageSize),
    loadingMore: extra?.loading ?? false,
    moreFailure: extra?.failure ?? null,
    loadMore,
    retry: () => setAttempt((n) => n + 1),
  }
}

/** Transação da URL. `replace` aplica na hora a resposta de editar, efetivar, cancelar ou estornar. */
export function useTransaction(id: string) {
  const [attempt, setAttempt] = useState(0)
  const { value, replace } = useKeyed<Transaction>(`${id}#${attempt}`, async () => {
    const result = await fetchTransaction(id)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })
  return { ...value, replace, attempt, retry: () => setAttempt((n) => n + 1) }
}

/**
 * Contas do Workspace (inclusive arquivadas) para filtros, formulário e nomes na lista. Falhar aqui não derruba a
 * lista: as transações aparecem com "Conta" genérica (estado parcial).
 */
export function useAccounts() {
  const [attempt, setAttempt] = useState(0)
  const { value } = useKeyed<Account[]>(`accounts#${attempt}`, async () => {
    const result = await fetchAllAccounts()
    return result.ok ? { status: 'success', data: result.data.items } : { status: 'error', failure: result }
  })
  return { ...value, retry: () => setAttempt((n) => n + 1) }
}

export function accountNames(accounts: { status: string; data?: Account[] }): Map<string, string> {
  return new Map(accounts.status === 'success' && accounts.data ? accounts.data.map((a) => [a.id, a.name]) : [])
}
