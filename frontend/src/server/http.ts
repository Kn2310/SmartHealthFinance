import 'server-only'
import { NextResponse } from 'next/server'

/**
 * Respostas do BFF e de /auth/*: nunca cacheáveis (dados financeiros, erros de sessão, Set-Cookie).
 * Todo Route Handler responde por aqui.
 */
const NO_STORE = { 'Cache-Control': 'no-store' }

export function noStoreJson(body: unknown, status = 200): NextResponse {
  return NextResponse.json(body, { status, headers: NO_STORE })
}

export function noStoreRedirect(url: string, status: 303 | 307 = 307): NextResponse {
  const response = NextResponse.redirect(url, status)
  response.headers.set('Cache-Control', 'no-store')
  return response
}
