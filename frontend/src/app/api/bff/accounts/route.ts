import { backendClient } from '@/server/backend'
import { withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

/**
 * BFF das contas ativas do Workspace da sessão (usado para escolher a conta de destino da importação).
 * Corpo de sucesso = contrato do backend; arquivadas ficam de fora (não recebem movimento).
 */
export async function GET() {
  return withSession('accounts', async (session) => {
    const { data, response } = await backendClient(session.accessToken).GET('/api/v1/workspaces/{workspaceId}/accounts', {
      params: { path: { workspaceId: session.workspaceId }, query: { includeArchived: false } },
    })
    if (data) return noStoreJson(data)
    if (response.status === 401) return noStoreJson({ code: 'UNAUTHENTICATED' }, 401)
    console.error('accounts upstream failure', { status: response.status })
    return noStoreJson({ code: 'UNAVAILABLE' }, 502)
  })
}
