/**
 * CONTRATO MANUAL E TEMPORÁRIO do backend usado pelo frontend (Identity, Overview, Accounts, Transactions e Imports) — ADR-0007 §6.
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
        source: 'MANUAL' | 'IMPORT'
      }[]
    }
    AccountResponse: {
      id: string
      workspaceId: string
      name: string
      type: 'CHECKING' | 'SAVINGS' | 'PAYMENT' | 'OTHER'
      institutionName: string | null
      currency: string
      includedInTotal: boolean
      status: 'ACTIVE' | 'ARCHIVED'
      createdAt: string
      updatedAt: string
    }
    AccountListResponse: { items: components['schemas']['AccountResponse'][] }
    /** `includedInTotal` ausente assume true. Nome e instituição: até 100 caracteres (AccountName/InstitutionName). */
    CreateAccountRequest: {
      name: string
      type: 'CHECKING' | 'SAVINGS' | 'PAYMENT' | 'OTHER'
      institutionName?: string | null
      includedInTotal?: boolean
    }
    /** Substituição completa dos campos editáveis (ADR-0004 §8). */
    UpdateAccountRequest: {
      name: string
      type: 'CHECKING' | 'SAVINGS' | 'PAYMENT' | 'OTHER'
      institutionName: string | null
      includedInTotal: boolean
    }
    /** Transação (ADR-0005). Valor sempre positivo; o sentido vem de `type` (+ `adjustmentDirection`). */
    TransactionResponse: {
      id: string
      workspaceId: string
      accountId: string
      /** Só TRANSFER. */
      destinationAccountId: string | null
      type: 'INCOME' | 'EXPENSE' | 'TRANSFER' | 'ADJUSTMENT' | 'REFUND'
      /** Só ADJUSTMENT. */
      adjustmentDirection: 'INCREASE' | 'DECREASE' | null
      amount: components['schemas']['MoneyDto']
      occurredOn: string
      description: string
      status: 'PENDING' | 'POSTED' | 'CANCELLED' | 'REVERSED'
      source: 'MANUAL' | 'IMPORT'
      refundOfTransactionId: string | null
      createdAt: string
      updatedAt: string
    }
    TransactionPageResponse: {
      items: components['schemas']['TransactionResponse'][]
      page: number
      pageSize: number
      totalItems: number
    }
    /** Lançamento manual. `status` ausente assume POSTED; campos de outro tipo vão nulos/ausentes. */
    CreateTransactionRequest: {
      type: 'INCOME' | 'EXPENSE' | 'TRANSFER' | 'ADJUSTMENT' | 'REFUND'
      accountId: string
      destinationAccountId?: string | null
      adjustmentDirection?: 'INCREASE' | 'DECREASE' | null
      amount: components['schemas']['MoneyDto']
      occurredOn: string
      description: string
      status?: 'PENDING' | 'POSTED' | null
      refundOfTransactionId?: string | null
    }
    /** Só a descrição é editável (ADR-0005 §8). */
    UpdateTransactionRequest: { description: string }
    /** Importação de extrato (ADR-0009). `lines.total = valid + invalid + duplicate`. */
    ImportResponse: {
      id: string
      workspaceId: string
      accountId: string
      format: 'CSV' | 'OFX'
      status: 'PREVIEW' | 'CONFIRMED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'EXPIRED'
      fileName: string
      fileSize: number
      lines: { total: number; valid: number; invalid: number; duplicate: number; imported: number }
      period: { from: string; to: string } | null
      sameFileImportedBefore: boolean
      failureReason: string | null
      createdAt: string
      previewExpiresAt: string | null
      confirmedAt: string | null
      completedAt: string | null
    }
    ImportPageResponse: {
      items: components['schemas']['ImportResponse'][]
      page: number
      pageSize: number
      totalItems: number
    }
    /** Linha do arquivo. Em INVALID só `lineNumber`, `status` e `issue` vêm preenchidos. */
    ImportRecordResponse: {
      lineNumber: number
      status: 'VALID' | 'INVALID' | 'DUPLICATE' | 'IMPORTED'
      occurredOn: string | null
      amount: components['schemas']['MoneyDto'] | null
      direction: 'INFLOW' | 'OUTFLOW' | null
      description: string | null
      issue: { field: string; code: string } | null
      transactionId: string | null
    }
    ImportRecordPageResponse: {
      items: components['schemas']['ImportRecordResponse'][]
      page: number
      pageSize: number
      totalItems: number
    }
  }
}

type ImportErrors = {
  400: { content: { 'application/json': components['schemas']['ApiError'] } }
  404: { content: { 'application/json': components['schemas']['ApiError'] } }
  409: { content: { 'application/json': components['schemas']['ApiError'] } }
}

type ImportPath = { workspaceId: string; importId: string }

type AccountErrors = {
  400: { content: { 'application/json': components['schemas']['ApiError'] } }
  404: { content: { 'application/json': components['schemas']['ApiError'] } }
  409: { content: { 'application/json': components['schemas']['ApiError'] } }
}

type AccountPath = { workspaceId: string; accountId: string }
type AccountResult = {
  200: { content: { 'application/json': components['schemas']['AccountResponse'] } }
} & AccountErrors

type TransactionErrors = {
  400: { content: { 'application/json': components['schemas']['ApiError'] } }
  404: { content: { 'application/json': components['schemas']['ApiError'] } }
  409: { content: { 'application/json': components['schemas']['ApiError'] } }
}

type TransactionPath = { workspaceId: string; transactionId: string }
type TransactionResult = {
  200: { content: { 'application/json': components['schemas']['TransactionResponse'] } }
} & TransactionErrors

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
  '/api/v1/workspaces/{workspaceId}/accounts': {
    get: {
      parameters: { query?: { includeArchived?: boolean }; path: { workspaceId: string } }
      responses: {
        200: { content: { 'application/json': components['schemas']['AccountListResponse'] } }
        404: { content: { 'application/json': components['schemas']['ApiError'] } }
      }
    }
    post: {
      parameters: { path: { workspaceId: string } }
      requestBody: { content: { 'application/json': components['schemas']['CreateAccountRequest'] } }
      responses: {
        201: { content: { 'application/json': components['schemas']['AccountResponse'] } }
      } & AccountErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/accounts/{accountId}': {
    get: { parameters: { path: AccountPath }; responses: AccountResult }
    put: {
      parameters: { path: AccountPath }
      requestBody: { content: { 'application/json': components['schemas']['UpdateAccountRequest'] } }
      responses: AccountResult
    }
  }
  '/api/v1/workspaces/{workspaceId}/accounts/{accountId}/archive': {
    post: { parameters: { path: AccountPath }; responses: AccountResult }
  }
  '/api/v1/workspaces/{workspaceId}/accounts/{accountId}/reactivate': {
    post: { parameters: { path: AccountPath }; responses: AccountResult }
  }
  '/api/v1/workspaces/{workspaceId}/transactions': {
    get: {
      parameters: {
        query?: {
          from?: string
          to?: string
          type?: components['schemas']['TransactionResponse']['type']
          status?: components['schemas']['TransactionResponse']['status']
          accountId?: string
          q?: string
          page?: number
          pageSize?: number
        }
        path: { workspaceId: string }
      }
      responses: {
        200: { content: { 'application/json': components['schemas']['TransactionPageResponse'] } }
      } & TransactionErrors
    }
    post: {
      parameters: { header: { 'Idempotency-Key': string }; path: { workspaceId: string } }
      requestBody: { content: { 'application/json': components['schemas']['CreateTransactionRequest'] } }
      responses: {
        200: { content: { 'application/json': components['schemas']['TransactionResponse'] } }
        201: { content: { 'application/json': components['schemas']['TransactionResponse'] } }
        422: { content: { 'application/json': components['schemas']['ApiError'] } }
      } & TransactionErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}': {
    get: { parameters: { path: TransactionPath }; responses: TransactionResult }
    put: {
      parameters: { path: TransactionPath }
      requestBody: { content: { 'application/json': components['schemas']['UpdateTransactionRequest'] } }
      responses: TransactionResult
    }
  }
  '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/post': {
    post: { parameters: { path: TransactionPath }; responses: TransactionResult }
  }
  '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/cancel': {
    post: { parameters: { path: TransactionPath }; responses: TransactionResult }
  }
  '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/reverse': {
    post: { parameters: { path: TransactionPath }; responses: TransactionResult }
  }
  '/api/v1/workspaces/{workspaceId}/imports': {
    get: {
      parameters: { query?: { page?: number; pageSize?: number }; path: { workspaceId: string } }
      responses: {
        200: { content: { 'application/json': components['schemas']['ImportPageResponse'] } }
      } & ImportErrors
    }
    post: {
      parameters: {
        query: { accountId: string }
        header: { 'Idempotency-Key': string }
        path: { workspaceId: string }
      }
      /** O BFF envia `FormData` com a parte `file`. */
      requestBody: { content: { 'multipart/form-data': { file: Blob } } }
      responses: {
        200: { content: { 'application/json': components['schemas']['ImportResponse'] } }
        201: { content: { 'application/json': components['schemas']['ImportResponse'] } }
        413: { content: { 'application/json': components['schemas']['ApiError'] } }
        422: { content: { 'application/json': components['schemas']['ApiError'] } }
      } & ImportErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/imports/{importId}': {
    get: {
      parameters: { path: ImportPath }
      responses: {
        200: { content: { 'application/json': components['schemas']['ImportResponse'] } }
      } & ImportErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/imports/{importId}/records': {
    get: {
      parameters: {
        query?: { status?: 'VALID' | 'INVALID' | 'DUPLICATE' | 'IMPORTED'; page?: number; pageSize?: number }
        path: ImportPath
      }
      responses: {
        200: { content: { 'application/json': components['schemas']['ImportRecordPageResponse'] } }
      } & ImportErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/imports/{importId}/confirm': {
    post: {
      parameters: { path: ImportPath }
      responses: {
        202: { content: { 'application/json': components['schemas']['ImportResponse'] } }
      } & ImportErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/imports/{importId}/cancel': {
    post: {
      parameters: { path: ImportPath }
      responses: {
        200: { content: { 'application/json': components['schemas']['ImportResponse'] } }
      } & ImportErrors
    }
  }
}
