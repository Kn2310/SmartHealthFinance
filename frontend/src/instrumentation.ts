/**
 * Roda uma vez quando o servidor Next.js inicia. Valida a configuração antes de aceitar requisições:
 * produção com APP_BASE_URL em HTTP (ou variável ausente) derruba o processo em vez de subir sem `Secure`.
 * Durante o `next build` não há servidor nem segredos, então a validação é pulada.
 */
export async function register() {
  if (process.env.NEXT_RUNTIME !== 'nodejs') return
  if (process.env.NEXT_PHASE === 'phase-production-build') return
  const { readEnv } = await import('./server/env')
  readEnv(process.env)
}
