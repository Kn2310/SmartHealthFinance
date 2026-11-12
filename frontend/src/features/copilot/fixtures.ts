import type { Conversation, ConversationPage, ConversationSummary, CopilotAnswer, Usage } from './api'

export const CARD = '01922f5e-0000-7000-8000-0000000000c1'
export const PLAN = '01922f5e-0000-7000-8000-0000000000d1'
export const INSIGHT = '01922f5e-0000-7000-8000-0000000000e1'
export const GOAL = '01922f5e-0000-7000-8000-0000000000f1'
export const CONVERSATION = '01922f5e-0000-7000-8000-000000000a01'
export const REQUEST = '01922f5e-0000-7000-8000-000000000b01'

/** Resposta de fatura (D-CopilotChat): texto com valores em negrito, quadro de fatores com total e ações. */
export const answer = (overrides: Partial<CopilotAnswer> = {}): CopilotAnswer => ({
  requestId: REQUEST,
  conversationId: null,
  messageId: null,
  code: 'OK',
  intent: 'CARDS',
  question: 'Por que minha fatura está mais alta?',
  answer: {
    type: 'explanation',
    segments: [
      { kind: 'TEXT', text: 'Sua fatura de outubro está em ' },
      { kind: 'VALUE', ref: 'v1' },
      { kind: 'TEXT', text: ', ' },
      { kind: 'VALUE', ref: 'v2' },
      { kind: 'TEXT', text: ' acima da média.' },
    ],
    explanation: [
      [{ kind: 'TEXT', text: 'Comparei cada categoria com a média de ' }, { kind: 'VALUE', ref: 'v3' }, { kind: 'TEXT', text: '.' }],
    ],
    values: [
      { ref: 'v1', label: 'Fatura', formatted: 'R$ 3.412,80', kind: 'FACT', period: '2026-10', source: 'cards-v1' },
      { ref: 'v2', label: 'Diferença', formatted: 'R$ 520,60', kind: 'ESTIMATED', period: '2026-10', source: 'cards-v1' },
      { ref: 'v3', label: 'Média', formatted: 'R$ 2.892,20', kind: 'FACT', period: null, source: 'cards-v1' },
      { ref: 'v4', label: 'Alimentação', formatted: '+ R$ 311,00', kind: 'FACT', period: null, source: 'cards-v1' },
      { ref: 'v5', label: 'Notebook', formatted: '+ R$ 160,00', kind: 'SIMULATED', period: null, source: 'cards-v1' },
      { ref: 'v6', label: 'Total', formatted: '+ R$ 471,00', kind: 'FACT', period: null, source: 'cards-v1' },
    ],
    breakdown: {
      title: 'O que explica a diferença',
      rows: [
        { label: 'Alimentação fora de casa', valueRef: 'v4', detail: 'R$ 1.284 vs R$ 973' },
        { label: 'Parcela do notebook', valueRef: 'v5', detail: null },
      ],
      totalRef: 'v6',
    },
    actions: [
      { kind: 'OPEN_INVOICE', label: 'Ver fatura', targetId: CARD, targetName: 'Aurora Platinum', params: { month: '2026-10' } },
      { kind: 'OPEN_INSTALLMENTS', label: 'Ver parcelamentos', targetId: CARD, targetName: null, params: {} },
    ],
    dataUsed: [
      { label: 'Fatura out/2026', partial: true },
      { label: 'Faturas jul–set', partial: false },
    ],
  },
  confidence: 'high',
  validation: 'VALID',
  notices: ['PARTIAL_PERIOD', 'ESTIMATED'],
  versions: { answerPrompt: 'answer-v1' },
  createdAt: '2026-09-22T15:00:00Z',
  ...overrides,
})

/** Resposta simples, sem quadro, confiança nem ações (dados insuficientes e fora do escopo). */
export const plain = (code: CopilotAnswer['code'], text: string): CopilotAnswer =>
  answer({
    code,
    intent: code === 'OUT_OF_SCOPE' ? 'OUT_OF_SCOPE' : 'CASH_FLOW',
    confidence: null,
    notices: [],
    answer: {
      type: code === 'OUT_OF_SCOPE' ? 'out_of_scope' : 'insufficient_data',
      segments: [{ kind: 'TEXT', text }],
      explanation: [],
      values: [],
      breakdown: null,
      actions: [],
      dataUsed: [],
    },
  })

export const usage = (overrides: Partial<Usage> = {}): Usage => ({
  month: { used: 6, limit: 100, resetsAt: '2026-11-01T03:00:00Z' },
  day: { used: 1, limit: 20, resetsAt: '2026-09-23T03:00:00Z' },
  dataScope: { from: '2026-04', to: '2026-09' },
  provisional: true,
  ...overrides,
})

export const summary = (id: string, title: string, lastMessageAt = '2026-09-12T15:00:00Z'): ConversationSummary => ({
  id,
  title,
  createdAt: lastMessageAt,
  lastMessageAt,
  messageCount: 1,
})

export const page = (overrides: Partial<ConversationPage> = {}): ConversationPage => ({
  historyEnabled: true,
  items: [summary(CONVERSATION, 'Quanto gastei com transporte em agosto?')],
  nextCursor: null,
  ...overrides,
})

export const conversation = (overrides: Partial<Conversation> = {}): Conversation => ({
  id: CONVERSATION,
  title: 'Por que minha fatura está mais alta?',
  createdAt: '2026-09-22T15:00:00Z',
  messages: [
    {
      id: '01922f5e-0000-7000-8000-000000000c01',
      requestId: REQUEST,
      question: 'Por que minha fatura está mais alta?',
      createdAt: '2026-09-22T15:00:00Z',
      answer: answer({ conversationId: CONVERSATION, messageId: '01922f5e-0000-7000-8000-000000000c01' }),
    },
  ],
  ...overrides,
})
