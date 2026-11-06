import type { components } from '@/lib/api/schema'
import { bffCall } from '@/lib/bff-client'

type Schemas = components['schemas']
export type Goal = Schemas['GoalResponse']
export type GoalType = Goal['type']
export type GoalStatus = Goal['status']
export type GoalForecast = Schemas['GoalForecastDto']
export type GoalList = Schemas['GoalListResponse']
export type GoalSummary = Schemas['GoalListSummary']
export type RecentContributions = Schemas['RecentContributionsResponse']
export type RecentContribution = Schemas['RecentContributionItem']
export type GoalContributions = Schemas['GoalContributionsResponse']
export type GoalMovement = Schemas['GoalMovementDto']
export type GoalProjection = Schemas['GoalProjectionResponse']
export type Simulation = Schemas['SimulationResponse']
export type SimulationScenario = Schemas['SimulationScenarioDto']
export type SimulationScenarioRequest = Schemas['SimulationScenarioRequest']
export type GoalPreview = Schemas['GoalPreviewResponse']
export type CreateGoalBody = Schemas['CreateGoalRequest']
export type UpdateGoalBody = Schemas['UpdateGoalRequest']
export type ContributionBody = Schemas['GoalContributionRequest']
export type PreviewBody = Schemas['PreviewGoalRequest']
export type Transaction = Schemas['TransactionResponse']
export type GoalAction = 'pause' | 'resume' | 'complete' | 'archive'

const JSON_BODY = { 'Content-Type': 'application/json' }

/** Caminho do BFF de um objetivo; ids sempre codificados. */
export const goalPath = (id: string, ...rest: string[]) =>
  ['/api/bff/goals', id, ...rest].map((part, i) => (i === 0 ? part : encodeURIComponent(part))).join('/')

export const createGoal = (body: CreateGoalBody) =>
  bffCall<Goal>('/api/bff/goals', { method: 'POST', headers: JSON_BODY, body: JSON.stringify(body) })

export const updateGoal = (id: string, body: UpdateGoalBody) =>
  bffCall<Goal>(goalPath(id), { method: 'PUT', headers: JSON_BODY, body: JSON.stringify(body) })

/** "Aplicar ao objetivo": só o plano (ADR-0034 §5). */
export const applyGoalPlan = (id: string, plannedMonthly: Goal['plannedMonthly'], deadline: string | null) =>
  bffCall<Goal>(goalPath(id, 'plan'), { method: 'PUT', headers: JSON_BODY, body: JSON.stringify({ plannedMonthly, deadline }) })

export const changeGoalStatus = (id: string, action: GoalAction) => bffCall<Goal>(goalPath(id, action), { method: 'POST' })

/** "Registrar aporte": uma `Idempotency-Key` por intenção (o mesmo formulário reenviado não duplica). */
export const addContribution = (id: string, body: ContributionBody, idempotencyKey: string) =>
  bffCall<Transaction>(goalPath(id, 'contributions'), {
    method: 'POST',
    headers: { ...JSON_BODY, 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  })

export const simulateGoal = (id: string, scenarios: SimulationScenarioRequest[]) =>
  bffCall<Simulation>(goalPath(id, 'simulations'), { method: 'POST', headers: JSON_BODY, body: JSON.stringify({ scenarios }) })

export const previewGoal = (body: PreviewBody) =>
  bffCall<GoalPreview>('/api/bff/goals/preview', { method: 'POST', headers: JSON_BODY, body: JSON.stringify(body) })
