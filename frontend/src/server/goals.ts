import 'server-only'
import type { NextRequest, NextResponse } from 'next/server'
import { GOAL_MAX_SCENARIOS, GOAL_NAME_MAX_LENGTH, GOAL_STATUSES, GOAL_TYPES, CATEGORY_CODE_MAX_LENGTH } from '@/lib/api/contract'
import type { components } from '@/lib/api/schema'
import { isIsoDate } from '@/lib/format/business-date'
import { isUuid } from '@/lib/uuid'
import { stableCode } from './bff'
import { noStoreJson } from './http'
import { WORKSPACE_CURRENCY, description as transactionDescription, readJsonObject } from './transactions'

type Schemas = components['schemas']
type ApiError = Schemas['ApiError']
type MoneyDto = Schemas['MoneyDto']
type GoalStatus = Schemas['GoalResponse']['status']
type GoalType = Schemas['GoalResponse']['type']

/**
 * BFF dos Objetivos (ADR-0035, ADR-0036, ADR-0037). Aqui só se confere o FORMATO (texto, dinheiro em string com até 2
 * casas, mês `AAAA-MM`, UUIDs) e se remonta um objeto novo com os campos do contrato; as regras do objetivo (prazo
 * futuro, destino exclusivo, reserva única, `SAVINGS`) são do backend.
 */

/** Ações de status (ADR-0035 §1), uma rota do BFF para as quatro. */
export const GOAL_ACTIONS = ['pause', 'resume', 'complete', 'archive'] as const
export type GoalAction = (typeof GOAL_ACTIONS)[number]
export const isGoalAction = (value: string): value is GoalAction => (GOAL_ACTIONS as readonly string[]).includes(value)

export const goalNotFound = () => noStoreJson({ code: 'GOAL_NOT_FOUND' }, 404)

type FieldIssue = { field: string; code: string }
const validationFailed = (details: FieldIssue[]) => noStoreJson({ code: 'VALIDATION_FAILED', details }, 400)
const invalidRequest = () => noStoreJson({ code: 'INVALID_REQUEST' }, 400)

/** Dinheiro como o `Money.of` aceita, com as casas da moeda (BRL): positividade e zero são do domínio. */
const AMOUNT = /^\d{1,15}(\.\d{1,2})?$/
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/
const CATEGORY_CODE = new RegExp(`^[A-Z][A-Z0-9_]{0,${CATEGORY_CODE_MAX_LENGTH - 1}}$`)

const includes = <T extends string>(list: readonly T[], value: unknown): value is T =>
  (list as readonly unknown[]).includes(value)

const absent = (value: unknown) => value === undefined || value === null || value === ''

function money(value: unknown, field: string, issues: FieldIssue[], required = true): MoneyDto | null {
  if (absent(value)) {
    if (required) issues.push({ field, code: 'REQUIRED' })
    return null
  }
  const dto = value as { amount?: unknown; currency?: unknown }
  if (typeof dto !== 'object' || typeof dto.amount !== 'string' || !AMOUNT.test(dto.amount)) {
    issues.push({ field, code: 'INVALID' })
    return null
  }
  return { amount: dto.amount, currency: WORKSPACE_CURRENCY }
}

function month(value: unknown, field: string, issues: FieldIssue[]): string | null {
  if (absent(value)) return null
  if (typeof value !== 'string' || !MONTH.test(value)) {
    issues.push({ field, code: 'INVALID' })
    return null
  }
  return value
}

function accountId(value: unknown, field: string, issues: FieldIssue[], required = false): string | null {
  if (absent(value)) {
    if (required) issues.push({ field, code: 'REQUIRED' })
    return null
  }
  if (!isUuid(value)) {
    issues.push({ field, code: 'INVALID' })
    return null
  }
  return value
}

function name(value: unknown, issues: FieldIssue[]): string {
  if (typeof value !== 'string' || value.trim() === '') {
    issues.push({ field: 'name', code: 'REQUIRED' })
    return ''
  }
  const trimmed = value.trim()
  if (trimmed.length > GOAL_NAME_MAX_LENGTH) issues.push({ field: 'name', code: 'TOO_LONG' })
  else if (CONTROL.test(trimmed)) issues.push({ field: 'name', code: 'INVALID_CHARACTERS' })
  return trimmed
}

type Read<T> = Promise<{ body: T } | { response: NextResponse }>

/** Criação (ADR-0035 §1): `destinationAccountId` só fora da reserva; `origin.insightId` só com `INSIGHT`. */
export async function readCreateGoalBody(request: NextRequest): Read<Schemas['CreateGoalRequest']> {
  const input = await readJsonObject(request)
  if (!input) return { response: invalidRequest() }
  const issues: FieldIssue[] = []
  const goalName = name(input.name, issues)
  let type: GoalType | null = null
  if (absent(input.type)) issues.push({ field: 'type', code: 'REQUIRED' })
  else if (!includes(GOAL_TYPES, input.type)) issues.push({ field: 'type', code: 'INVALID' })
  else type = input.type
  const targetAmount = money(input.targetAmount, 'targetAmount', issues)
  const plannedMonthly = money(input.plannedMonthly, 'plannedMonthly', issues)
  const deadline = month(input.deadline, 'deadline', issues)
  const sourceAccountId = accountId(input.sourceAccountId, 'sourceAccountId', issues)
  const destinationAccountId = accountId(input.destinationAccountId, 'destinationAccountId', issues)

  let origin: Schemas['GoalOriginRequest'] | null = null
  if (!absent(input.origin)) {
    const raw = input.origin as { kind?: unknown; insightId?: unknown }
    if (typeof raw !== 'object' || (raw.kind !== 'MANUAL' && raw.kind !== 'INSIGHT' && raw.kind !== 'HEALTH')) {
      issues.push({ field: 'origin.kind', code: 'INVALID' })
    } else if (raw.kind === 'INSIGHT') {
      if (!isUuid(raw.insightId)) issues.push({ field: 'origin.insightId', code: absent(raw.insightId) ? 'REQUIRED' : 'INVALID' })
      else origin = { kind: 'INSIGHT', insightId: raw.insightId }
    } else {
      origin = { kind: raw.kind }
    }
  }

  if (issues.length > 0) return { response: validationFailed(issues) }
  return {
    body: {
      name: goalName,
      type: type!,
      targetAmount: targetAmount!,
      plannedMonthly: plannedMonthly!,
      deadline,
      sourceAccountId,
      destinationAccountId,
      origin,
    },
  }
}

/** Edição: substitui nome, plano, contas; tipo e origem não mudam (ADR-0035 §1). */
export async function readUpdateGoalBody(request: NextRequest): Read<Schemas['UpdateGoalRequest']> {
  const input = await readJsonObject(request)
  if (!input) return { response: invalidRequest() }
  const issues: FieldIssue[] = []
  const goalName = name(input.name, issues)
  const targetAmount = money(input.targetAmount, 'targetAmount', issues)
  const plannedMonthly = money(input.plannedMonthly, 'plannedMonthly', issues)
  const deadline = month(input.deadline, 'deadline', issues)
  const sourceAccountId = accountId(input.sourceAccountId, 'sourceAccountId', issues)
  const destinationAccountId = accountId(input.destinationAccountId, 'destinationAccountId', issues)
  if (issues.length > 0) return { response: validationFailed(issues) }
  return {
    body: {
      name: goalName,
      targetAmount: targetAmount!,
      plannedMonthly: plannedMonthly!,
      deadline,
      sourceAccountId,
      destinationAccountId,
    },
  }
}

/** "Aplicar ao objetivo" (ADR-0034 §5): só o plano. */
export async function readPlanBody(request: NextRequest): Read<Schemas['ApplyGoalPlanRequest']> {
  const input = await readJsonObject(request)
  if (!input) return { response: invalidRequest() }
  const issues: FieldIssue[] = []
  const plannedMonthly = money(input.plannedMonthly, 'plannedMonthly', issues)
  const deadline = month(input.deadline, 'deadline', issues)
  if (issues.length > 0) return { response: validationFailed(issues) }
  return { body: { plannedMonthly: plannedMonthly!, deadline } }
}

/** "Registrar aporte" (ADR-0035 §2): data ISO; o intervalo (até hoje, desde o início) é do backend. */
export async function readContributionBody(request: NextRequest): Read<Schemas['GoalContributionRequest']> {
  const input = await readJsonObject(request)
  if (!input) return { response: invalidRequest() }
  const issues: FieldIssue[] = []
  const sourceAccountId = accountId(input.sourceAccountId, 'sourceAccountId', issues, true)
  const destinationAccountId = accountId(input.destinationAccountId, 'destinationAccountId', issues)
  const amount = money(input.amount, 'amount', issues)
  let occurredOn: string | null = null
  if (absent(input.occurredOn)) issues.push({ field: 'occurredOn', code: 'REQUIRED' })
  else if (!isIsoDate(input.occurredOn)) issues.push({ field: 'occurredOn', code: 'INVALID' })
  else occurredOn = input.occurredOn as string
  const text = absent(input.description) ? null : transactionDescription(input.description, issues)
  if (issues.length > 0) return { response: validationFailed(issues) }
  return {
    body: { sourceAccountId: sourceAccountId!, destinationAccountId, amount: amount!, occurredOn: occurredOn!, description: text },
  }
}

/** De 1 a 5 cenários (ADR-0036 §2); cada campo é opcional. */
export async function readSimulationBody(request: NextRequest): Read<Schemas['SimulateGoalRequest']> {
  const input = await readJsonObject(request)
  if (!input || !Array.isArray(input.scenarios)) return { response: invalidRequest() }
  const list = input.scenarios as unknown[]
  if (list.length < 1 || list.length > GOAL_MAX_SCENARIOS) return { response: validationFailed([{ field: 'scenarios', code: 'INVALID' }]) }
  const issues: FieldIssue[] = []
  const scenarios = list.map((item) => {
    const raw = (typeof item === 'object' && item !== null ? item : {}) as Record<string, unknown>
    const scenario: Schemas['SimulationScenarioRequest'] = {}
    const monthlyAmount = money(raw.monthlyAmount, 'monthlyAmount', issues, false)
    if (monthlyAmount) scenario.monthlyAmount = monthlyAmount
    const deadline = month(raw.deadline, 'deadline', issues)
    if (deadline) scenario.deadline = deadline
    if (!absent(raw.reduction)) {
      const reduction = raw.reduction as { categoryCode?: unknown; amount?: unknown }
      const code = typeof reduction === 'object' ? reduction.categoryCode : undefined
      const amount = money(typeof reduction === 'object' ? reduction.amount : undefined, 'reduction.amount', issues)
      if (typeof code !== 'string' || !CATEGORY_CODE.test(code)) issues.push({ field: 'reduction.categoryCode', code: 'INVALID' })
      else if (amount) scenario.reduction = { categoryCode: code, amount }
    }
    return scenario
  })
  if (issues.length > 0) return { response: validationFailed(issues) }
  return { body: { scenarios } }
}

/** Prévia do D-CreateGoal (ADR-0036 §3). */
export async function readPreviewBody(request: NextRequest): Read<Schemas['PreviewGoalRequest']> {
  const input = await readJsonObject(request)
  if (!input) return { response: invalidRequest() }
  const issues: FieldIssue[] = []
  let type: GoalType | null = null
  if (!includes(GOAL_TYPES, input.type)) issues.push({ field: 'type', code: absent(input.type) ? 'REQUIRED' : 'INVALID' })
  else type = input.type
  const targetAmount = money(input.targetAmount, 'targetAmount', issues, false)
  const plannedMonthly = money(input.plannedMonthly, 'plannedMonthly', issues)
  const deadline = month(input.deadline, 'deadline', issues)
  const destinationAccountId = accountId(input.destinationAccountId, 'destinationAccountId', issues)
  if (issues.length > 0) return { response: validationFailed(issues) }
  return { body: { type: type!, targetAmount, plannedMonthly: plannedMonthly!, deadline, destinationAccountId } }
}

/** `?status=` da lista: só um status conhecido passa (sem filtro = abertos). */
export function parseGoalStatus(value: string | null): GoalStatus | undefined {
  return includes(GOAL_STATUSES, value) ? value : undefined
}

/** `limit` dos aportes recentes (1 a 20) e página do histórico; fora do formato cai no padrão do backend. */
export function intParam(value: string | null, min: number, max: number): number | undefined {
  if (value === null || !/^\d{1,5}$/.test(value)) return undefined
  const n = Number(value)
  return n >= min && n <= max ? n : undefined
}

const FIELDS = new Set([
  'name', 'type', 'targetAmount', 'plannedMonthly', 'deadline', 'sourceAccountId', 'destinationAccountId',
  'origin.kind', 'origin.insightId', 'amount', 'occurredOn', 'description', 'scenarios', 'monthlyAmount',
  'reduction.categoryCode', 'reduction.amount', 'status', 'limit', 'page', 'pageSize',
])
const BEAN_CODES: Partial<Record<string, string>> = { NotBlank: 'REQUIRED', NotNull: 'REQUIRED', Size: 'TOO_LONG' }
const NOT_FOUND = new Set(['GOAL_NOT_FOUND', 'ACCOUNT_NOT_FOUND', 'INSIGHT_NOT_FOUND'])
const CONFLICTS = new Set([
  'GOAL_STATUS_CONFLICT', 'GOAL_RESERVE_ALREADY_EXISTS', 'GOAL_DESTINATION_IN_USE', 'ACCOUNT_ARCHIVED', 'CONFLICT',
])

/** Campo do backend → campo do formulário: a moeda conta como o valor e o índice do cenário some. */
function knownField(field: string | undefined): string | null {
  if (!field) return null
  const scoped = field.replace(/^scenarios\[\d+\]\./, '')
  if (FIELDS.has(scoped)) return scoped
  const parent = scoped.replace(/\.(currency|amount)$/, '')
  return FIELDS.has(parent) ? parent : null
}

/**
 * Falhas da API de Objetivos → códigos estáveis do BFF. Na validação, só campos conhecidos + código estável;
 * mensagens, `traceId` e ids nunca chegam ao browser nem aos logs (só o rótulo e o status).
 */
export function goalFailure(label: string, status: number, error: ApiError | undefined): NextResponse {
  if (status === 401) return noStoreJson({ code: 'UNAUTHENTICATED' }, 401)
  const code = stableCode(error?.code)
  if (status === 400 && code === 'VALIDATION_FAILED') {
    const details = (error?.details ?? []).flatMap((detail) => {
      const field = knownField(detail.field)
      if (!field) return []
      return [{ field, code: (detail.code && BEAN_CODES[detail.code]) ?? stableCode(detail.code) ?? 'INVALID' }]
    })
    return details.length > 0 ? validationFailed(details) : invalidRequest()
  }
  if (status === 400) return invalidRequest()
  if (status === 404 && code && NOT_FOUND.has(code)) return noStoreJson({ code }, 404)
  if (status === 409 && code && CONFLICTS.has(code)) return noStoreJson({ code }, 409)
  if (status === 422 && code === 'IDEMPOTENCY_KEY_REUSED') return noStoreJson({ code }, 422)
  console.error(`${label} upstream failure`, { status })
  return noStoreJson({ code: 'UNAVAILABLE' }, 502)
}
