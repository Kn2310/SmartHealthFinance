import type { ConsentPurpose } from '@/lib/api/schema'
import { businessToday } from '@/lib/format/business-date'

/**
 * Textos de Consentimentos (D-ProfileConsents; ADR-0038). Código desconhecido cai numa mensagem neutra: a UI nunca
 * mostra o código cru nem texto vindo do servidor.
 */

export const CONSENT_COPY: Record<
  ConsentPurpose,
  { label: string; purpose: string; details?: { title: string; paragraphs: readonly string[] } }
> = {
  // Termo `copilot-data-v2` (CONSENT_TERMS_VERSIONS; RIPD R14; adendo ADR-0038). Mudar este texto exige nova versão do
  // termo, que expira os consentimentos anteriores e pede nova concessão.
  COPILOT_DATA_USE: {
    label: 'Permitir que o Copilot use meus dados',
    purpose: 'suas perguntas e um resumo dos seus números vão a um provedor de IA nos EUA',
    details: {
      title: 'O que é enviado',
      paragraphs: [
        'Para responder, o Copilot envia à Anthropic, provedora de IA nos Estados Unidos: a sua pergunta, como você a ' +
          'escreveu, até 3 perguntas anteriores da conversa e um resumo calculado dos seus dados (totais, categorias, ' +
          'datas).',
        'Contas, cartões, objetivos e lugares onde você compra vão com apelidos genéricos, como "comerciante 1". ' +
          'Nunca vão seu nome, e-mail, número de cartão ou identificadores.',
        'Os cálculos são feitos pelo app; a IA só explica.',
        'A Anthropic pode guardar esses dados por até 30 dias para segurança e não os usa para treinar modelos.',
        'Não escreva na pergunta nomes de outras pessoas, CPF ou senhas.',
        'Você pode revogar quando quiser; o histórico guardado é apagado junto.',
      ],
    },
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
