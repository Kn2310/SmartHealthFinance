import 'server-only'
import type { NextRequest, NextResponse } from 'next/server'
import { getFreshSession, type Session } from './auth/session'
import { getEnv } from './env'
import { noStoreJson } from './http'

/**
 * Infra comum dos Route Handlers do BFF (ADR-0007): sessão do cookie selado, Workspace da sessão, verificação de
 * `Origin` em rotas que alteram dados (§6) e falhas sempre como códigos genéricos e `no-store`.
 */
export async function withSession(
  label: string,
  handler: (session: Session) => Promise<NextResponse>,
): Promise<NextResponse> {
  try {
    const fresh = await getFreshSession()
    if (fresh.status === 'none') return noStoreJson({ code: 'UNAUTHENTICATED' }, 401)
    if (fresh.status === 'unavailable') return noStoreJson({ code: 'UNAVAILABLE' }, 502)
    return await handler(fresh.session)
  } catch {
    console.error(`${label} upstream unreachable`)
    return noStoreJson({ code: 'UNAVAILABLE' }, 502)
  }
}

/**
 * ADR-0007 §6: endpoints do BFF que alteram dados exigem `Origin` igual a `APP_BASE_URL`. SameSite=Lax sozinho
 * não cobre subdomínios irmãos. Requisição sem `Origin` também é recusada (navegadores sempre o enviam em POST).
 */
export function rejectCrossOrigin(request: NextRequest): NextResponse | null {
  const origin = request.headers.get('origin')
  if (origin !== null && origin === new URL(getEnv().appBaseUrl).origin) return null
  return noStoreJson({ code: 'FORBIDDEN' }, 403)
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export const isUuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value)

/** Código estável do backend (ex.: `MISSING_COLUMN`). Qualquer outra coisa é descartada: nada de texto livre. */
const STABLE_CODE = /^[A-Z][A-Z0-9_]{0,49}$/

export function stableCode(value: unknown): string | null {
  return typeof value === 'string' && STABLE_CODE.test(value) ? value : null
}
