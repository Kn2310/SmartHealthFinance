import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { getEnv } from '@/server/env'
import { noStoreRedirect } from '@/server/http'
import { exchangeCode } from '@/server/auth/oidc'
import { SessionTooLargeError, takeFlow, writeSession } from '@/server/auth/session'

export const dynamic = 'force-dynamic'

/**
 * Retorno do IdP. Qualquer falha termina em /auth/error (mensagem genérica), nunca em 500.
 * O `reason` só serve para diagnóstico na URL; a página não o exibe.
 */
export async function GET(request: NextRequest) {
  const { appBaseUrl } = getEnv()
  const fail = (reason: string) => noStoreRedirect(`${appBaseUrl}/auth/error?reason=${reason}`)

  try {
    const flow = await takeFlow()
    const { searchParams } = request.nextUrl
    const code = searchParams.get('code')
    if (!flow || !code || searchParams.get('state') !== flow.state) return fail('state')

    const exchanged = await exchangeCode(code, flow.verifier)
    if (!exchanged.ok) return fail('token')
    const tokens = exchanged.tokens

    // ADR-0002/0003: provisionamento idempotente após o login; devolve o Workspace pessoal.
    const { data, response } = await backendClient(tokens.accessToken)
      .POST('/api/v1/users/me')
      .catch(() => ({ data: undefined, response: undefined }))
    if (!data || !response?.ok) return fail('provision')

    await writeSession({ ...tokens, workspaceId: data.workspaceId, displayName: data.displayName })
    return noStoreRedirect(`${appBaseUrl}/home`)
  } catch (error) {
    if (error instanceof SessionTooLargeError) {
      console.warn('session too large at login', { length: error.length })
      return fail('session')
    }
    console.error('login callback failed')
    return fail('unexpected')
  }
}
