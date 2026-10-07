import { getEnv } from '@/server/env'
import { noStoreRedirect } from '@/server/http'
import { buildAuthorizationUrl, createPkce } from '@/server/auth/oidc'
import { writeFlow } from '@/server/auth/session'

export const dynamic = 'force-dynamic'

export async function GET() {
  const { challenge, verifier, state } = createPkce()
  try {
    const url = await buildAuthorizationUrl(challenge, state)
    await writeFlow({ state, verifier })
    return noStoreRedirect(url)
  } catch {
    return noStoreRedirect(`${getEnv().appBaseUrl}/auth/error?reason=idp`)
  }
}
