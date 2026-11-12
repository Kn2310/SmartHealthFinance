import { isUuid } from '@/lib/uuid'
import type { AnswerAction } from './api'

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/
const CATEGORY_CODE = /^[A-Z][A-Z0-9_]{0,39}$/

/**
 * Rota de cada ação da resposta (ADR-0040 §3): o backend manda `kind`, `targetId` e `params`; a UI só monta o link
 * para telas que já existem (como nos Insights, ADR-0021 §2). Ids fora do formato e tipos desconhecidos não têm rota.
 */
export function actionHref(action: AnswerAction): string | null {
  const id = isUuid(action.targetId) ? encodeURIComponent(action.targetId) : null
  const params = action.params ?? {}
  const month = params.month && MONTH.test(params.month) ? params.month : null
  switch (action.kind) {
    case 'OPEN_INVOICE':
      if (!id) return null
      return month ? `/cards/${id}/invoices/${month}` : `/cards/${id}/invoice`
    case 'OPEN_INSTALLMENTS':
      return id ? `/cards/${id}?tab=installments` : null
    case 'OPEN_HEALTH':
      return '/home/health'
    case 'OPEN_INSIGHT':
      return id ? `/insights/${id}` : null
    case 'OPEN_GOAL':
      return id ? `/goals/${id}` : null
    case 'OPEN_ANALYSIS_CATEGORY': {
      const search = new URLSearchParams()
      if (month) search.set('month', month)
      if (action.targetId && CATEGORY_CODE.test(action.targetId)) search.set('category', action.targetId)
      const query = search.toString()
      return `/analysis/categories${query ? `?${query}` : ''}`
    }
    case 'OPEN_FORECAST':
      // A previsão vive nos "Próximos eventos" da Home (mesma rota de VIEW_UPCOMING_EVENTS nos Insights).
      return '/home#events'
    default:
      return null
  }
}
