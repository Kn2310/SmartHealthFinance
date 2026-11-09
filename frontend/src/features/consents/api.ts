import { CONSENT_TERMS_VERSIONS } from '@/lib/api/contract'
import type { components, ConsentPurpose } from '@/lib/api/schema'
import { bffCall } from '@/lib/bff-client'

type Schemas = components['schemas']
export type Consent = Schemas['ConsentResponse']

const JSON_BODY = { 'Content-Type': 'application/json' }

export const fetchConsents = () => bffCall<Schemas['ConsentListResponse']>('/api/bff/consents')

/**
 * Concede na versão do termo que esta tela apresenta (ADR-0038 §3): se o backend tiver um termo mais novo, recusa
 * com CONSENT_TERMS_OUTDATED e a pessoa precisa ver o texto novo. Revogar não leva versão.
 */
export const changeConsent = (purpose: ConsentPurpose, granted: boolean) =>
  bffCall<Schemas['ConsentListResponse']>(`/api/bff/consents/${encodeURIComponent(purpose)}`, {
    method: 'PUT',
    headers: JSON_BODY,
    body: JSON.stringify(granted ? { granted, termsVersion: CONSENT_TERMS_VERSIONS[purpose] } : { granted }),
  })
