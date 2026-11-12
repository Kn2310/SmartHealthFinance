import type { CopilotConfidence, CopilotIntent, CopilotNotice } from '@/lib/api/schema'
import { businessToday } from '@/lib/format/business-date'
import { formatShortDate } from '@/lib/format/date'
import type { EntryKind } from './entry'

/**
 * Catálogo de textos do Copilot (ADR-0041 §5, aprovado em 2026-11-12). As respostas vêm do backend; aqui ficam só
 * sugestões, avisos e estados. A UI nunca mostra o código de erro cru nem texto de erro vindo do servidor.
 */

export const INITIAL_SUGGESTIONS = [
  'Por que minha fatura está mais alta este mês?',
  'Quanto já comprometi nas próximas faturas?',
  'Estou no caminho da minha reserva?',
  'Onde mais gastei este mês?',
] as const

export const FIRST_USE_SUGGESTIONS = ['Como estou este mês?', 'Onde mais gastei?'] as const

export const ENTRY_SUGGESTIONS: Record<EntryKind, readonly string[]> = {
  insight: ['Por que isso aconteceu?', 'O que posso fazer a respeito?', 'Isso afeta minha saúde financeira?'],
  invoice: [
    'Por que esta fatura está mais alta?',
    'Quanto já está comprometido na próxima fatura?',
    'Quanto do meu limite fica livre?',
  ],
  installment: ['Quanto falta pagar deste parcelamento?', 'Como ele pesa nas próximas faturas?'],
  goal: ['Estou no ritmo para este objetivo?', 'Quanto preciso guardar por mês para chegar no prazo?'],
}

const GENERIC_CONTINUATION = ['Como estou este mês?', 'Onde mais gastei?'] as const

/** 2 a 3 perguntas fixas por intenção, curtas e sem números. */
const CONTINUATION: Record<CopilotIntent, readonly string[]> = {
  OVERVIEW: ['Onde mais gastei este mês?', 'Como está minha saúde financeira?'],
  CASH_FLOW: ['Onde mais gastei este mês?', 'Quanto já comprometi nas próximas faturas?'],
  CATEGORY: ['Quais lugares mais pesaram nessa categoria?', 'Como isso mudou em relação ao mês passado?'],
  MERCHANT: ['Em quais categorias isso aparece?', 'Como isso mudou em relação ao mês passado?'],
  HEALTH: ['O que mudou no meu score?', 'O que mais pesa na minha saúde financeira?'],
  INSIGHTS: ['Qual insight é o mais importante agora?', 'O que posso fazer a respeito?'],
  CARDS: ['Quanto do limite fica livre no próximo mês?', 'Isso afeta minha saúde financeira?'],
  FORECAST: ['Quanto já comprometi nas próximas faturas?', 'Isso afeta minha reserva?'],
  GOALS: ['Estou no ritmo para este objetivo?', 'Quanto preciso guardar por mês para chegar no prazo?'],
  GOAL_SIMULATION: ['E se eu guardar um pouco mais por mês?', 'Estou no ritmo para este objetivo?'],
  OUT_OF_SCOPE: GENERIC_CONTINUATION,
  UNSUPPORTED_ACTION: GENERIC_CONTINUATION,
}

export const continuationFor = (intent: CopilotIntent | null | undefined): readonly string[] =>
  (intent ? CONTINUATION[intent] : undefined) ?? GENERIC_CONTINUATION

export const NOTICE_TEXT: Record<CopilotNotice, string> = {
  PARTIAL_PERIOD: 'Período parcial: o valor ainda pode mudar.',
  ESTIMATED: 'Inclui valores estimados (≈).',
  SIMULATED: 'Inclui valores simulados. Nada foi gravado.',
  SAFE_MODE: 'Resposta simplificada: mostro só os números calculados pelo sistema.',
  UPDATING: 'Seus dados estão sendo atualizados; alguns números podem mudar.',
}

export const FIXED_WARNING = 'O Copilot pode errar. Confira os dados usados em cada resposta.'

export const CONFIDENCE_TEXT: Record<CopilotConfidence, string> = {
  high: 'Confiança alta',
  medium: 'Confiança média',
  low: 'Confiança baixa',
}

/** Dia (fuso de negócio) de um instante: "22 set". */
const dayOf = (instant: string): string => formatShortDate(businessToday(new Date(instant))).replace(/\.$/, '')

export const historicNotice = (createdAt: string) =>
  `Resposta de ${dayOf(createdAt)}. Os números podem ter mudado desde então.`

export const LOADING = 'Analisando seus dados…'
export const READY = 'Resposta pronta'
export const LOADING_SLOW = 'Ainda calculando. Pode levar alguns segundos.'

export const CONSENT_REQUIRED = {
  text: 'Para responder, o Copilot precisa usar seus dados financeiros. Você decide em Consentimentos.',
  action: 'Rever consentimentos',
}

export function quotaMessage(window: 'month' | 'day', resetsAt: string | undefined) {
  return {
    title: window === 'month' ? 'Você usou as perguntas deste mês' : 'Você usou as perguntas de hoje',
    text: `${resetsAt ? `Novas perguntas liberam em ${dayOf(resetsAt)}. ` : ''}Insights e análises continuam disponíveis.`,
    action: 'Ver insights',
  }
}

export const rateLimitedMessage = (seconds: number | undefined) =>
  `Muitas perguntas seguidas. Tente de novo em ${Math.max(1, seconds ?? 1)} segundos.`

const ERROR_TARGET: Record<EntryKind | 'none', string> = {
  invoice: 'a fatura',
  installment: 'o parcelamento',
  goal: 'o objetivo',
  insight: 'o insight',
  none: 'seus insights',
}

export const errorMessage = (entry: EntryKind | null) => ({
  title: 'Não consegui concluir esta análise',
  text: `Seus dados estão seguros. Você pode tentar de novo ou ver ${ERROR_TARGET[entry ?? 'none']}${entry ? ' diretamente' : ''}.`,
  retry: 'Tentar novamente',
})

export const ALREADY_ANSWERED = 'Esta pergunta já foi respondida, mas a resposta não foi guardada. Pergunte de novo.'

export const CONVERSATION_NOT_FOUND = {
  title: 'Não encontramos esta conversa',
  text: 'Ela pode ter sido apagada ou ter passado do prazo de guarda.',
  action: 'Nova pergunta',
}

export const HISTORY_OFF = {
  text: 'O histórico está desligado e as conversas não são guardadas.',
  action: 'Ativar em Consentimentos',
}

export const HISTORY_ERROR = 'Não conseguimos carregar o histórico agora.'
export const CONFIRM_DELETE_ONE = 'Apagar esta conversa? Ela é removida de vez.'
export const CONFIRM_DELETE_ALL = 'Apagar todas as conversas? Elas são removidas de vez.'
export const DELETE_ERROR = 'Não conseguimos apagar agora. Tente de novo.'

export const FEEDBACK = {
  helpful: 'Resposta útil',
  notHelpful: 'Resposta não útil',
  thanks: 'Obrigado pelo retorno',
  error: 'Não conseguimos registrar agora. Tente de novo.',
  reasonGroup: 'Motivo',
  reasons: {
    WRONG_NUMBERS: 'Números errados',
    DID_NOT_ANSWER: 'Não respondeu',
    CONFUSING: 'Confuso',
    OTHER: 'Outro',
  },
} as const


const MONTH_ABBR = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez']

/** "abr a set" (mesmo ano) ou "abr de 2025 a set de 2026". */
export function dataScopeLabel(scope: { from: string; to: string }): string {
  const abbr = (month: string) => MONTH_ABBR[Number(month.slice(5, 7)) - 1] ?? month
  const sameYear = scope.from.slice(0, 4) === scope.to.slice(0, 4)
  return sameYear
    ? `${abbr(scope.from)} a ${abbr(scope.to)}`
    : `${abbr(scope.from)} de ${scope.from.slice(0, 4)} a ${abbr(scope.to)} de ${scope.to.slice(0, 4)}`
}
