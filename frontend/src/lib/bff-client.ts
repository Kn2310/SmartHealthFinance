/** Campo recusado pela validação (BFF ou backend), só com códigos estáveis. */
export interface FieldIssue {
  field: string
  code: string
}

/** Falha do BFF já traduzida em código estável; `reason` só vem em `FILE_REJECTED`, `details` em `VALIDATION_FAILED`. */
export interface Failure {
  ok: false
  code: string
  reason?: string
  details?: FieldIssue[]
}
export type Result<T> = { ok: true; status: number; data: T } | Failure

const JSON_ACCEPT = { Accept: 'application/json' }

function fieldIssues(value: unknown): FieldIssue[] | undefined {
  if (!Array.isArray(value)) return undefined
  return value.flatMap((item: unknown) => {
    const { field, code } = (item ?? {}) as { field?: unknown; code?: unknown }
    return typeof field === 'string' && typeof code === 'string' ? [{ field, code }] : []
  })
}

/**
 * Chamada do browser ao BFF. Sessão expirada (401) volta ao login; qualquer outra falha vira um código
 * estável — nunca uma mensagem do servidor.
 */
export async function bffCall<T>(input: string, init: RequestInit = {}): Promise<Result<T>> {
  try {
    const response = await fetch(input, { ...init, cache: 'no-store', headers: { ...JSON_ACCEPT, ...init.headers } })
    if (response.status === 401) {
      // Navegação completa de propósito: /auth/login é um Route Handler que redireciona ao IdP.
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination
      window.location.assign('/auth/login')
      return { ok: false, code: 'UNAUTHENTICATED' }
    }
    const body = (await response.json().catch(() => null)) as unknown
    if (response.ok && body) return { ok: true, status: response.status, data: body as T }
    const failure = (body ?? {}) as { code?: unknown; reason?: unknown; details?: unknown }
    return {
      ok: false,
      code: typeof failure.code === 'string' ? failure.code : 'UNAVAILABLE',
      reason: typeof failure.reason === 'string' ? failure.reason : undefined,
      details: fieldIssues(failure.details),
    }
  } catch {
    return { ok: false, code: 'UNAVAILABLE' }
  }
}
