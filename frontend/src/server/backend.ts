import 'server-only'
import createClient from 'openapi-fetch'
import type { paths } from '@/lib/api/schema'
import { getEnv } from './env'

/** Cliente tipado da API Spring Boot. Só roda no servidor Next.js (BFF); o browser nunca vê o token. */
export function backendClient(accessToken: string) {
  return createClient<paths>({
    baseUrl: getEnv().apiBaseUrl,
    headers: { Authorization: `Bearer ${accessToken}` },
    cache: 'no-store',
  })
}
