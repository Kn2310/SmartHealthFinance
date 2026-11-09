import type { ConsentPurpose } from '@/lib/api/schema'
import { businessToday } from '@/lib/format/business-date'

/**
 * Textos de Consentimentos (D-ProfileConsents; ADR-0038). Código desconhecido cai numa mensagem neutra: a UI nunca
 * mostra o código cru nem texto vindo do servidor.
 */

export const CONSENT_COPY: Record<ConsentPurpose, { label: string; purpose: string }> = {
  COPILOT_DATA_USE: {
    label: 'Permitir que o Copilot use meus dados',
    purpose: 'o Copilot só lê dados dentro do app',
  },
  COPILOT_HISTORY: {
    label: 'Guardar histórico de conversas do Copilot',
    purpose: 'sem histórico, cada conversa começa do zero',
  },
}

const LOAD_ERROR: Partial<Record<string, string>> = {
  UNAVAILABLE: 'Não conseguimos carregar seus consentimentos agora. Nada foi alterado.',
}

export function loadErrorMessage(code: string | null | undefined): string {
  return (code ? LOAD_ERROR[code] : undefined) ?? 'Não conseguimos carregar seus consentimentos. Nada foi alterado.'
}

const CHANGE_ERROR: Partial<Record<string, string>> = {
  CONSENT_PREREQUISITE_MISSING: 'Para guardar o histórico, permita antes que o Copilot use seus dados.',
  CONSENT_TERMS_OUTDATED: 'Os termos foram atualizados. Recarregue a página para ver a versão nova.',
  CONFLICT: 'Seus consentimentos mudaram em outra aba. Recarregue a página e tente de novo.',
}

export function changeErrorMessage(code: string | null | undefined): string {
  return (code ? CHANGE_ERROR[code] : undefined) ?? 'Não conseguimos salvar a mudança. Nada foi alterado.'
}

const MONTHS = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez']

/** "02 abr 2026", no fuso de negócio (o instante vem em UTC). */
export function formatConsentDate(instant: string): string {
  const date = new Date(instant)
  if (Number.isNaN(date.getTime())) return ''
  const [year, month, day] = businessToday(date).split('-')
  return `${day} ${MONTHS[Number(month) - 1]} ${year}`
}
