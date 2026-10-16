import 'server-only'

/** Visões do Health servidas por `/api/bff/health/{view}` (ADR-0019). */
export const HEALTH_VIEWS = ['summary', 'evolution', 'method', 'status'] as const
export type HealthView = (typeof HEALTH_VIEWS)[number]

export function isHealthView(value: string): value is HealthView {
  return (HEALTH_VIEWS as readonly string[]).includes(value)
}

/** Parâmetros aceitos; o Workspace vem SEMPRE da sessão. */
export interface HealthQueryParams {
  month?: string
  months?: number
}

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/

/**
 * Só valores bem formados passam (mês YYYY-MM, `months` de 1 a 12, como na Análise); o resto é descartado e cai no
 * padrão do backend. Mês futuro continua com o backend, que responde 400 com o campo.
 */
export function parseHealthQuery(search: URLSearchParams): HealthQueryParams {
  const month = search.get('month')
  const months = search.get('months')
  return {
    month: month && MONTH.test(month) ? month : undefined,
    months: months && /^\d{1,2}$/.test(months) && Number(months) >= 1 && Number(months) <= 12 ? Number(months) : undefined,
  }
}
