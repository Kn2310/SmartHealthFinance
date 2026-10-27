'use client'

import { useState } from 'react'
import { bffCall } from '@/lib/bff-client'
import { useKeyed } from '@/lib/use-keyed'
import {
  cardPath,
  type Card,
  type CardsSummary,
  type FutureInvoices,
  type InstallmentPlan,
  type InstallmentPlanList,
  type Invoice,
  type InvoiceItemPage,
  type InvoiceList,
  type Utilization,
  type UtilizationHistory,
} from './api'

/**
 * Uma chamada ao BFF por chave; trocar os parâmetros muda a chave e recarrega. `null` desliga a chamada. Todos os
 * valores (fatura, estado, utilização, parcelas, percentuais) chegam prontos do backend: aqui nada é somado.
 */
function useCardView<T>(path: string | null) {
  const [attempt, setAttempt] = useState(0)
  const { value, replace } = useKeyed<T | null>(`${path}#${attempt}`, async () => {
    if (!path) return { status: 'success', data: null }
    const result = await bffCall<T>(path)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })
  return { ...value, replace, attempt, retry: () => setAttempt((n) => n + 1) }
}

export const useCardsSummary = () => useCardView<CardsSummary>('/api/bff/cards/summary')

export const useCard = (id: string) => useCardView<Card>(cardPath(id))

export const useUtilization = (id: string) => useCardView<Utilization>(cardPath(id, 'utilization'))

export const useUtilizationHistory = (id: string) =>
  useCardView<UtilizationHistory>(`${cardPath(id, 'utilization', 'history')}?months=6`)

export const useInvoices = (id: string) => useCardView<InvoiceList>(cardPath(id, 'invoices'))

/** `dueMonth` nulo = a fatura atual (período que contém hoje). */
export const useInvoice = (id: string, dueMonth: string | null) =>
  useCardView<Invoice>(cardPath(id, 'invoices', dueMonth ?? 'current'))

export const useInvoiceItems = (id: string, dueMonth: string | null, page: number, pageSize: number) =>
  useCardView<InvoiceItemPage>(
    dueMonth ? `${cardPath(id, 'invoices', dueMonth, 'items')}?page=${page}&pageSize=${pageSize}` : null,
  )

export const useFutureInvoices = (id: string) => useCardView<FutureInvoices>(cardPath(id, 'invoices', 'future'))

export const useInstallmentPlans = (id: string, status: 'ACTIVE' | 'FINISHED' | 'ALL' = 'ALL') =>
  useCardView<InstallmentPlanList>(`${cardPath(id, 'installments')}?status=${status}`)

/** `null` desliga a chamada (ex.: a transação ainda não carregou). */
export const useInstallmentPlan = (id: string | null, transactionId: string | null) =>
  useCardView<InstallmentPlan>(id && transactionId ? cardPath(id, 'installments', transactionId) : null)
