import 'server-only'
import type { NextRequest } from 'next/server'
import { backendClient } from './backend'
import { rejectCrossOrigin, withSession } from './bff'
import {
  forecastFailure,
  invalidSeriesId,
  parseSeriesStatus,
  readAdjustmentBody,
  seriesNotFound,
  type RecurringAction,
  type RecurringKind,
} from './forecast'
import { noStoreJson } from './http'

type Params = Promise<{ seriesId: string }>

const EXPENSES = '/api/v1/workspaces/{workspaceId}/recurring-expenses'
const INCOMES = '/api/v1/workspaces/{workspaceId}/recurring-incomes'

/** Lista das séries da direção, filtrada por `?status=` bem formado (ADR-0032 §2). */
export async function recurringList(request: NextRequest, kind: RecurringKind) {
  const status = parseSeriesStatus(request.nextUrl.searchParams.get('status'))
  const label = `recurring ${kind} list`
  return withSession(label, async (session) => {
    const client = backendClient(session.accessToken)
    const params = { path: { workspaceId: session.workspaceId }, query: { status } }
    const { data, error, response } =
      kind === 'expenses' ? await client.GET(EXPENSES, { params }) : await client.GET(INCOMES, { params })
    return data ? noStoreJson(data) : forecastFailure(label, response.status, error)
  })
}

export async function recurringDetail(kind: RecurringKind, params: Params) {
  const { seriesId } = await params
  if (invalidSeriesId(seriesId)) return seriesNotFound()
  const label = `recurring ${kind} detail`
  return withSession(label, async (session) => {
    const client = backendClient(session.accessToken)
    const p = { params: { path: { workspaceId: session.workspaceId, seriesId } } }
    const { data, error, response } =
      kind === 'expenses' ? await client.GET(`${EXPENSES}/{seriesId}`, p) : await client.GET(`${INCOMES}/{seriesId}`, p)
    return data ? noStoreJson(data) : forecastFailure(label, response.status, error)
  })
}

/** Confirmar/ignorar: idempotentes; só da mesma origem; respondem a série e `forecastState: RECALCULATING`. */
export async function recurringAction(
  request: NextRequest,
  kind: RecurringKind,
  params: Params,
  action: RecurringAction,
) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { seriesId } = await params
  if (invalidSeriesId(seriesId)) return seriesNotFound()
  const label = `recurring ${kind} ${action}`
  return withSession(label, async (session) => {
    const client = backendClient(session.accessToken)
    const p = { params: { path: { workspaceId: session.workspaceId, seriesId } } }
    const base = kind === 'expenses' ? EXPENSES : INCOMES
    const result =
      action === 'confirm'
        ? await client.POST(`${base}/{seriesId}/confirm`, p)
        : await client.POST(`${base}/{seriesId}/ignore`, p)
    return result.data ? noStoreJson(result.data) : forecastFailure(label, result.response.status, result.error)
  })
}

/** Ajuste (`PUT`) e "voltar ao detectado" (`DELETE`) dos valores do usuário. */
export async function recurringAdjustment(
  request: NextRequest,
  kind: RecurringKind,
  params: Params,
  method: 'PUT' | 'DELETE',
) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { seriesId } = await params
  if (invalidSeriesId(seriesId)) return seriesNotFound()
  const label = `recurring ${kind} adjustment ${method}`
  const parsed = method === 'PUT' ? await readAdjustmentBody(request) : null
  if (parsed && 'response' in parsed) return parsed.response

  return withSession(label, async (session) => {
    const client = backendClient(session.accessToken)
    const p = { params: { path: { workspaceId: session.workspaceId, seriesId } } }
    const base = kind === 'expenses' ? EXPENSES : INCOMES
    const result = parsed
      ? await client.PUT(`${base}/{seriesId}/adjustment`, { ...p, body: parsed.body })
      : await client.DELETE(`${base}/{seriesId}/adjustment`, p)
    return result.data ? noStoreJson(result.data) : forecastFailure(label, result.response.status, result.error)
  })
}
