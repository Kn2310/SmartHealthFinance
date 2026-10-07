import { NextResponse, type NextRequest } from 'next/server'
import { buildCsp, createNonce } from '@/lib/security/csp'

/**
 * Proxy (antigo middleware) do Next.js 16: aplica a CSP com nonce por requisição. O Next lê o nonce do header
 * `Content-Security-Policy` da requisição e o coloca nos próprios scripts/estilos; por isso todas as páginas são
 * renderizadas dinamicamente (ver `await connection()` no layout raiz).
 */
export function proxy(request: NextRequest) {
  const nonce = createNonce()
  const csp = buildCsp({
    nonce,
    dev: process.env.NODE_ENV === 'development',
    https: process.env.APP_BASE_URL?.startsWith('https://') ?? false,
  })

  const requestHeaders = new Headers(request.headers)
  requestHeaders.set('x-nonce', nonce)
  requestHeaders.set('Content-Security-Policy', csp)

  const response = NextResponse.next({ request: { headers: requestHeaders } })
  response.headers.set('Content-Security-Policy', csp)
  return response
}

export const config = {
  matcher: [
    {
      // Páginas e /auth/*. Fora: BFF JSON (/api), assets estáticos e fontes.
      source: '/((?!api/|_next/static|_next/image|fonts/|favicon.ico).*)',
      missing: [
        { type: 'header', key: 'next-router-prefetch' },
        { type: 'header', key: 'purpose', value: 'prefetch' },
      ],
    },
  ],
}
