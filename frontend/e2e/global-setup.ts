import type { FullConfig } from '@playwright/test'

const ANY_ID = '00000000-0000-7000-8000-000000000000'

/**
 * O perfil `app` do compose serve `next dev`, que compila cada rota na primeira requisição (no container, com
 * polling do bind mount, isso passa de 15 s). Uma requisição sem sessão a cada rota antes dos testes tira essa
 * compilação do meio dos cenários. Em build de produção não custa nada. Nenhum dado é enviado.
 */
const ROUTES = [
  '/auth/login',
  '/auth/callback',
  '/home',
  '/accounts',
  '/accounts/new',
  `/accounts/${ANY_ID}`,
  '/import',
  '/transactions',
  '/transactions/new',
  `/transactions/${ANY_ID}`,
  '/more',
  '/api/bff/overview',
  '/api/bff/accounts',
  `/api/bff/accounts/${ANY_ID}`,
  `/api/bff/accounts/${ANY_ID}/opening-balance`,
  '/api/bff/transactions',
  `/api/bff/transactions/${ANY_ID}`,
  '/api/bff/imports',
  `/api/bff/imports/${ANY_ID}`,
  `/api/bff/imports/${ANY_ID}/records`,
  `/api/bff/imports/${ANY_ID}/confirm`,
]

export default async function globalSetup(config: FullConfig) {
  const baseURL = config.projects[0]!.use.baseURL!
  for (const route of ROUTES) {
    try {
      await fetch(new URL(route, baseURL), { redirect: 'manual', signal: AbortSignal.timeout(120_000) })
    } catch (error) {
      throw new Error(`app fora do ar em ${baseURL} (${route}): suba o compose com --profile app`, { cause: error })
    }
  }
}
