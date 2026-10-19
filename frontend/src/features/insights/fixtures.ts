import type { InsightDetail, InsightHighlights, InsightItem, InsightList, InsightRelated } from '@/lib/api/types'

/**
 * Respostas do BFF de Insights para os testes, com os números do `insights-v1` sobre design/specs/09-dados-de-exemplo
 * (alimentação fora 1.284,00 contra 973,00, +32%; reserva 2,4 meses; ADR-0021). Fictícios.
 */
export const SPIKE_ID = '0192b1f0-0000-7000-8000-000000000001'
export const RESERVE_ID = '0192b1f0-0000-7000-8000-000000000002'
export const SCORE_ID = '0192b1f0-0000-7000-8000-000000000003'

const freshness = { state: 'UP_TO_DATE' as const, generatedAt: '2026-09-22T12:00:10Z', healthComputedAt: '2026-09-22T12:00:00Z' }

export function spikeItem(overrides: Partial<InsightItem> = {}): InsightItem {
  return {
    id: SPIKE_ID,
    detector: 'CATEGORY_SPIKE',
    type: 'SPENDING',
    typeLabel: 'Gastos',
    importance: 'IMPORTANT',
    status: 'ACTIVE',
    title: 'Seus gastos com alimentação fora aumentaram 32% neste mês, cerca de R$ 310 a mais.',
    support: 'R$ 1.284,00 até 22 de setembro, contra média de R$ 973,00 no mesmo período de abr–ago. ENTREGA JA concentra 35% do valor.',
    subjectLabel: 'Alimentação fora',
    period: { month: '2026-09', partial: true },
    createdAt: '2026-09-22T12:00:10Z',
    updatedAt: '2026-09-22T12:00:10Z',
    seen: false,
    confidence: 'HIGH',
    feedback: null,
    primaryAction: {
      kind: 'VIEW_TRANSACTIONS',
      label: 'Ver as 4 transações',
      primary: true,
      available: true,
      params: { category: 'DINING_OUT', from: '2026-09-01', to: '2026-09-22', type: 'EXPENSE' },
    },
    ...overrides,
  }
}

export function reserveItem(overrides: Partial<InsightItem> = {}): InsightItem {
  return {
    ...spikeItem(),
    id: RESERVE_ID,
    detector: 'LOW_RESERVE',
    type: 'HEALTH',
    typeLabel: 'Saúde',
    importance: 'INFORMATIVE',
    title: 'Sua reserva cobre cerca de 2,4 meses de despesas.',
    support: 'A referência usada no score é 6 meses. Faltam R$ 15.152,00 (média de R$ 4.192,00/mês em abr–ago).',
    subjectLabel: 'Reserva',
    period: { month: null, partial: true },
    confidence: 'MEDIUM',
    seen: true,
    primaryAction: { kind: 'VIEW_HEALTH', label: 'Ver saúde', primary: true, available: true, params: {} },
    ...overrides,
  }
}

export function scoreItem(overrides: Partial<InsightItem> = {}): InsightItem {
  return {
    ...reserveItem(),
    id: SCORE_ID,
    detector: 'HEALTH_SCORE_CHANGE',
    title: 'Seu score caiu 6 pontos em relação a agosto.',
    support: 'De 90 para 84 pontos.',
    subjectLabel: 'Saúde financeira',
    period: { month: '2026-09', partial: true },
    ...overrides,
  }
}

export function readyList(overrides: Partial<InsightList> = {}): InsightList {
  return {
    workspaceId: 'ws-1',
    algorithmVersion: 'insights-v1',
    state: 'READY',
    freshness,
    period: { month: '2026-09', partial: true },
    counts: { all: 3, important: 1, spending: 1, income: 0, health: 2, cards: { count: 0, available: false } },
    items: [spikeItem(), scoreItem(), reserveItem()],
    page: 0,
    pageSize: 50,
    totalItems: 3,
    ...overrides,
  }
}

export function spikeDetail(overrides: Partial<InsightDetail> = {}): InsightDetail {
  return {
    workspaceId: 'ws-1',
    algorithmVersion: 'insights-v1',
    freshness,
    insight: spikeItem(),
    recommendation: { title: 'Revisar ENTREGA JA', text: 'ENTREGA JA concentra 35% do gasto da categoria (R$ 447,00 em 1 compra).' },
    figures: [
      { code: 'current', label: 'Setembro (1–22)', kind: 'MONEY', value: '1284.00', from: '2026-09-01', to: '2026-09-22' },
      { code: 'average', label: 'Média mensal abr–ago (1–22)', kind: 'MONEY', value: '973.00', from: '2026-04-01', to: '2026-08-22' },
      { code: 'difference', label: 'Diferença', kind: 'MONEY', value: '311.00', from: '2026-09-01', to: '2026-09-22' },
      { code: 'percent', label: 'Variação', kind: 'PERCENT', value: '32.0', from: '2026-09-01', to: '2026-09-22' },
    ],
    series: {
      kind: 'MONEY',
      points: [
        { month: '2026-04', value: '912.00', partial: false },
        { month: '2026-05', value: '1004.00', partial: false },
        { month: '2026-06', value: '948.00', partial: false },
        { month: '2026-07', value: '1021.00', partial: false },
        { month: '2026-08', value: '980.00', partial: false },
        { month: '2026-09', value: '1284.00', partial: true },
      ],
    },
    contributors: [
      { kind: 'MERCHANT', key: 'entrega ja', name: 'ENTREGA JA', amount: { amount: '447.00', currency: 'BRL' }, count: 1, share: '34.8', day: null },
      { kind: 'MERCHANT', key: 'bistro lume', name: 'BISTRO LUME', amount: { amount: '412.80', currency: 'BRL' }, count: 1, share: '32.1', day: null },
    ],
    comparison: 'Comparamos o que você gastou em alimentação fora entre 1 e 22 de setembro com a média do mesmo período nos 5 meses anteriores (abr–ago).',
    limitations: [{ code: 'PARTIAL_MONTH', text: 'Setembro ainda não terminou; o valor pode mudar até o dia 30.' }],
    sources: [{ source: 'analytics.categories', from: '2026-04-01', to: '2026-09-22' }],
    dataSources: ['CSV', 'OFX'],
    actions: [
      spikeItem().primaryAction,
      { kind: 'VIEW_CATEGORY', label: 'Ver categoria', primary: false, available: true, params: { category: 'DINING_OUT', month: '2026-09' } },
      { kind: 'CREATE_SAVINGS_GOAL', label: 'Criar objetivo de economia', primary: false, available: false, params: {} },
      { kind: 'ASK_COPILOT', label: 'Perguntar ao Copilot', primary: false, available: false, params: {} },
    ],
    related: [scoreItem()],
    dimension: { code: 'SPENDING', label: 'Gastos', score: 71 },
    ...overrides,
  }
}

export function readyHighlights(overrides: Partial<InsightHighlights> = {}): InsightHighlights {
  return {
    workspaceId: 'ws-1',
    algorithmVersion: 'insights-v1',
    state: 'READY',
    freshness,
    period: { month: '2026-09', partial: true },
    attention: [spikeItem()],
    changes: [
      { insightId: SPIKE_ID, detector: 'CATEGORY_SPIKE', type: 'SPENDING', label: 'Alimentação fora', value: 'R$ 1.284,00 até 22 set', detail: '+32% vs média de abr–ago' },
    ],
    importantCount: 1,
    ...overrides,
  }
}

export function related(items: InsightItem[] = [spikeItem()]): InsightRelated {
  return { workspaceId: 'ws-1', algorithmVersion: 'insights-v1', freshness, items }
}
