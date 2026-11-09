'use client'

import { useState } from 'react'
import { useKeyed } from '@/lib/use-keyed'
import { fetchConsents, type Consent } from './api'

/** Consentimentos da pessoa. `replace` aplica na hora a lista devolvida por uma mudança (a cascata muda dois itens). */
export function useConsents() {
  const [attempt, setAttempt] = useState(0)
  const { value, replace } = useKeyed<Consent[]>(`consents#${attempt}`, async () => {
    const result = await fetchConsents()
    return result.ok ? { status: 'success', data: result.data.items } : { status: 'error', failure: result }
  })
  return { ...value, replace, retry: () => setAttempt((n) => n + 1) }
}
