import { getEnv } from '@/server/env'
import { noStoreRedirect } from '@/server/http'
import { endIdpSession } from '@/server/auth/oidc'
import { clearSession, readSession } from '@/server/auth/session'

export const dynamic = 'force-dynamic'

/**
 * Logout (ADR-0008). Só por POST: um link externo não encerra a sessão.
 * 1. encerra a sessão no IdP de servidor para servidor (refresh token) — sem tela de confirmação;
 * 2. apaga o cookie local SEMPRE, mesmo se o IdP falhar;
 * 3. volta para uma página do próprio app (destino fixo, nunca vindo da requisição).
 */
export async function POST() {
  const session = await readSession()
  await clearSession()
  if (session) await endIdpSession(session.refreshToken)
  return noStoreRedirect(`${getEnv().appBaseUrl}/auth/signed-out`, 303)
}
