import type { Health, HealthEvolution, HealthMethod } from '@/lib/api/types'

/**
 * Respostas do BFF do Health para os testes, com os números do `health-v1` sobre design/specs/09-dados-de-exemplo
 * (Cash Flow 97, Spending 71, Reserve 69, Consistency 100 → 84; ADR-0017). Fictícios.
 */
export function readyHealth(overrides: Partial<Health> = {}): Health {
  return {
    workspaceId: 'ws-1',
    state: 'READY',
    algorithmVersion: 'health-v1',
    currency: 'BRL',
    period: { month: '2026-09', asOf: '2026-09-22', partial: true, windowFrom: '2026-04-01', windowTo: '2026-08-31', monthsUsed: 5 },
    score: 84,
    variation: 3,
    estimated: false,
    confidence: 'MEDIUM',
    coverage: { available: 4, total: 6, weightPercent: 70 },
    summary: 'Seu resultado combina 4 de 6 dimensões. Consistência (100) e fluxo de caixa (97) sustentam o score; reserva (69) é o ponto que mais pesa.',
    context: { activeAccounts: 3, uncategorizedShare: '0.0', sources: ['CSV', 'MANUAL', 'OFX'] },
    freshness: { state: 'UP_TO_DATE', computedAt: '2026-09-22T12:00:00Z', analyticsRefreshedAt: '2026-09-22T11:59:58Z' },
    dimensions: [
      {
        dimension: 'CASH_FLOW', label: 'Fluxo de caixa', weight: 25, status: 'OK', score: 97, reason: null, attention: false,
        explanation: 'Receitas e reembolsos cobriram as despesas em 5 de 5 meses (abr/2026 a ago/2026). Sobra média de R$ 2.368,00/mês (36,1% da receita).',
        evidence: [
          { code: 'income', label: 'Receitas', kind: 'MONEY', value: '32800.0000', from: '2026-04-01', to: '2026-08-31', source: 'analytics.monthly' },
          { code: 'savingsRate', label: 'Sobra em relação à receita', kind: 'PERCENT', value: '36.10', from: '2026-04-01', to: '2026-08-31', source: 'analytics.monthly' },
        ],
      },
      {
        dimension: 'SPENDING', label: 'Gastos', weight: 15, status: 'OK', score: 71, reason: null, attention: true,
        explanation: 'Gasto +10,4% vs sua média (1 a 22 de set/2026). Alimentação fora está 32,0% acima da média (+R$ 311,00).',
        evidence: [],
      },
      { dimension: 'CREDIT', label: 'Crédito', weight: 15, status: 'NOT_APPLICABLE', score: null, reason: 'REQUIRES_CARDS', attention: false, explanation: 'Disponível quando houver cartões e parcelamentos.', evidence: [] },
      { dimension: 'DEBT', label: 'Dívidas', weight: 15, status: 'NOT_APPLICABLE', score: null, reason: 'REQUIRES_CARDS', attention: false, explanation: 'Disponível quando houver cartões e parcelamentos.', evidence: [] },
      {
        dimension: 'RESERVE', label: 'Reserva', weight: 20, status: 'OK', score: 69, reason: null, attention: true,
        explanation: 'Sua reserva cobre ≈ 2,4 meses da média de despesas (R$ 4.192,00/mês, abr/2026 a ago/2026). Referência: 6 meses.',
        evidence: [
          { code: 'reserveBalance', label: 'Saldo das contas de reserva', kind: 'MONEY', value: '10000.0000', from: '2026-09-22', to: '2026-09-22', source: 'overview.account-balances' },
          { code: 'coverageMonths', label: 'Meses de cobertura', kind: 'MONTHS', value: '2.39', from: '2026-04-01', to: '2026-09-22', source: 'overview.account-balances' },
        ],
      },
      {
        dimension: 'CONSISTENCY', label: 'Consistência', weight: 10, status: 'OK', score: 100, reason: null, attention: false,
        explanation: 'Receitas e despesas ficaram dentro do seu padrão em 5 de 5 meses; 99,1% da receita vem de fontes recorrentes.',
        evidence: [],
      },
    ],
    factors: [
      { dimension: 'CASH_FLOW', direction: 'SUSTAINING', text: 'Receitas e reembolsos cobriram as despesas em 5 de 5 meses.' },
      { dimension: 'RESERVE', direction: 'WEIGHING', text: 'Sua reserva cobre ≈ 2,4 meses da média de despesas.' },
    ],
    nextSteps: [
      { kind: 'BUILD_RESERVE', text: 'Fortalecer a reserva', evidence: 'Faltam R$ 15.152,00 para 6 meses de despesas.', categoryCode: null, month: null, amount: { amount: '15152.00', currency: 'BRL' } },
      { kind: 'REVIEW_CATEGORY', text: 'Alimentação fora 32,0% acima da média', evidence: '+R$ 311,00 em relação à sua média (1 a 22 de set/2026).', categoryCode: 'DINING_OUT', month: '2026-09', amount: null },
    ],
    ...overrides,
  }
}

export function noDataHealth(): Health {
  return {
    workspaceId: 'ws-1',
    state: 'NO_DATA',
    algorithmVersion: 'health-v1',
    currency: 'BRL',
    period: { month: '2026-09', asOf: null, partial: false, windowFrom: null, windowTo: null, monthsUsed: 0 },
    score: null,
    variation: null,
    estimated: false,
    confidence: null,
    coverage: null,
    summary: null,
    context: null,
    freshness: { state: 'UP_TO_DATE', computedAt: null, analyticsRefreshedAt: null },
    dimensions: [],
    factors: [],
    nextSteps: [],
  }
}

export function evolution(): HealthEvolution {
  return {
    workspaceId: 'ws-1',
    algorithmVersion: 'health-v1',
    to: '2026-09',
    freshness: { state: 'UP_TO_DATE', computedAt: '2026-09-22T12:00:00Z', analyticsRefreshedAt: null },
    months: [
      { month: '2026-04', state: 'READY', score: 91, variation: null, estimated: true, partial: false },
      { month: '2026-05', state: 'READY', score: 88, variation: -3, estimated: true, partial: false },
      { month: '2026-06', state: 'READY', score: 84, variation: -4, estimated: false, partial: false },
      { month: '2026-07', state: 'READY', score: 82, variation: -2, estimated: false, partial: false },
      { month: '2026-08', state: 'READY', score: 81, variation: -1, estimated: false, partial: false },
      { month: '2026-09', state: 'READY', score: 84, variation: 3, estimated: false, partial: true },
    ],
  }
}

export function method(): HealthMethod {
  return {
    algorithmVersion: 'health-v1',
    about: 'O score resume, de 0 a 100, seis dimensões da sua vida financeira.',
    dimensions: [
      { dimension: 'CASH_FLOW', label: 'Fluxo de caixa', weight: 25, applicable: true, observes: 'Receitas e reembolsos comparados às despesas.', formula: '0,7 × sobra + 0,3 × meses positivos.', minimum: '1 mês fechado.', curves: [{ name: 'Sobra', input: 'resultado ÷ receitas', points: [{ x: '-0.1', score: '0' }, { x: '0.4', score: '100' }] }] },
      { dimension: 'CREDIT', label: 'Crédito', weight: 15, applicable: false, observes: 'Utilização do limite.', formula: 'Disponível quando houver cartões.', minimum: '—', curves: [] },
      { dimension: 'RESERVE', label: 'Reserva', weight: 20, applicable: true, observes: 'Meses de despesas cobertos.', formula: 'Saldo ÷ média.', minimum: 'Uma conta poupança.', curves: [{ name: 'Cobertura', input: 'meses de despesas', points: [{ x: '1', score: '45' }, { x: '6', score: '100' }] }] },
    ],
    overall: 'Média ponderada das notas das dimensões disponíveis.',
    window: 'Até os 6 últimos meses fechados.',
    sources: ['Lançamentos manuais', 'Extratos CSV', 'Extratos OFX'],
    estimated: 'Com menos de 3 meses fechados, o score é estimado.',
    confidence: [{ level: 'HIGH', rule: '6 meses fechados.' }, { level: 'MEDIUM', rule: '3 meses fechados.' }, { level: 'LOW', rule: 'Os demais casos.' }],
    partialMonth: 'O mês corrente é parcial.',
    rounding: 'Notas inteiras de 0 a 100.',
    exclusions: ['Investimentos', 'Seguros', 'Empréstimos complexos'],
  }
}
