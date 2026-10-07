/**
 * CONTRATO MANUAL E TEMPORÁRIO do backend usado pelo frontend (rotas de Identity e Overview) — ADR-0007 §6.
 *
 * Escrito no formato do openapi-typescript a partir dos DTOs reais (OverviewResponse, ProvisionedUserResponse,
 * ApiError). É a fonte de verdade do frontend enquanto o OpenAPI do backend não declarar:
 *   - enums como enums (hoje os DTOs usam String → o gerador produziria `string`);
 *   - campos nulos (`totalBalance`, `cashFlow`, `balance`… → o gerador perderia a distinção null ≠ zero).
 *
 * Proteção contra drift: `contract.ts` + `contract.test.ts` comparam este arquivo com os records e enums Java.
 * `pnpm api:generate` gera `openapi.generated.d.ts` SEPARADO, só para comparação; ele não substitui este arquivo.
 */

export interface components {
  schemas: {
    MoneyDto: { amount: string; currency: string }
    ApiError: {
      code: string
      message: string
      details: { field?: string; code?: string; message?: string }[]
      traceId?: string
    }
    ProvisionedUserResponse: {
      id: string
      email: string
      displayName: string
      status: string
      createdAt: string
      workspaceId: string
    }
    OverviewResponse: {
      workspaceId: string
      period: { type: 'CURRENT_MONTH' | 'PREVIOUS_MONTH' | 'CUSTOM'; from: string; to: string }
      state: 'NO_ACCOUNTS' | 'NO_TRANSACTIONS' | 'NO_ACTIVITY_IN_PERIOD' | 'READY'
      summary: {
        totalBalance: components['schemas']['MoneyDto'] | null
        balanceAsOf: string
        accountCount: number
        pendingTransactions: number
      }
      cashFlow: {
        income: components['schemas']['MoneyDto'] | null
        expense: components['schemas']['MoneyDto'] | null
        refunds: components['schemas']['MoneyDto'] | null
        net: components['schemas']['MoneyDto'] | null
        transfers: { count: number; volume: components['schemas']['MoneyDto'] | null }
      } | null
      accounts: {
        id: string
        name: string
        type: 'CHECKING' | 'SAVINGS' | 'PAYMENT' | 'OTHER'
        institutionName: string | null
        includedInTotal: boolean
        balance: components['schemas']['MoneyDto'] | null
        movementCount: number
      }[]
      recentTransactions: {
        id: string
        type: 'INCOME' | 'EXPENSE' | 'TRANSFER' | 'ADJUSTMENT' | 'REFUND'
        flow: 'INFLOW' | 'OUTFLOW' | 'TRANSFER'
        adjustmentDirection: 'INCREASE' | 'DECREASE' | null
        status: 'PENDING' | 'POSTED' | 'CANCELLED' | 'REVERSED'
        amount: components['schemas']['MoneyDto']
        occurredOn: string
        description: string | null
        account: { id: string; name: string }
        destinationAccount: { id: string; name: string } | null
        refundOfTransactionId: string | null
      }[]
    }
  }
}

export interface paths {
  '/api/v1/users/me': {
    post: {
      responses: {
        200: { content: { 'application/json': components['schemas']['ProvisionedUserResponse'] } }
        201: { content: { 'application/json': components['schemas']['ProvisionedUserResponse'] } }
      }
    }
  }
  '/api/v1/workspaces/{workspaceId}/overview': {
    get: {
      parameters: {
        query?: {
          period?: 'CURRENT_MONTH' | 'PREVIOUS_MONTH' | 'CUSTOM'
          from?: string
          to?: string
          recentLimit?: number
        }
        path: { workspaceId: string }
      }
      responses: {
        200: { content: { 'application/json': components['schemas']['OverviewResponse'] } }
        400: { content: { 'application/json': components['schemas']['ApiError'] } }
        404: { content: { 'application/json': components['schemas']['ApiError'] } }
      }
    }
  }
}
