// @vitest-environment node
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  ACCOUNT_NAME_MAX_LENGTH,
  ACCOUNT_STATUSES,
  ACCOUNT_TYPES,
  ADJUSTMENT_DIRECTIONS,
  IMPORT_FORMATS,
  INSTITUTION_NAME_MAX_LENGTH,
  IDEMPOTENCY_KEY_MAX_LENGTH,
  TRANSACTION_DESCRIPTION_MAX_LENGTH,
  TRANSACTION_MAX_PAGE_SIZE,
  TRANSACTION_SEARCH_MAX_LENGTH,
  IMPORT_STATUSES,
  MOVEMENT_FLOWS,
  RECORD_DIRECTIONS,
  RECORD_STATUSES,
  TRANSACTION_SOURCES,
  OVERVIEW_STATES,
  PERIOD_TYPES,
  RECORD_FIELDS,
  TRANSACTION_STATUSES,
  TRANSACTION_TYPES,
} from './contract'

/**
 * Detecção de drift do contrato manual (`schema.d.ts`) contra o código-fonte REAL do backend (monorepo).
 * O OpenAPI do springdoc não serve para isso hoje: os DTOs expõem enums como String e não declaram nulos, então o
 * schema gerado perderia as uniões e a distinção null ≠ zero. Ver ADR-0007 §6.
 */
const JAVA = resolve(__dirname, '../../../../backend/src/main/java/com/smarthealthfinance')
const hasBackend = existsSync(JAVA)

const read = (path: string) => readFileSync(resolve(JAVA, path), 'utf8').replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, '')

function javaEnum(path: string): string[] {
  // Constantes até o `;` (enum com métodos) ou até o `}` (enum só com constantes).
  const body = /enum\s+\w+\s*\{([^;}]*)[;}]/.exec(read(path))?.[1] ?? ''
  return body.split(',').map((c) => c.trim()).filter(Boolean)
}

function javaRecords(path: string): Record<string, string[]> {
  const records: Record<string, string[]> = {}
  // Anotações com parênteses (`@Size(max = …)` nos requests) saem antes de ler os parâmetros.
  const source = read(path).replace(/@\w+(?:\([^)]*\))?/g, '')
  for (const [, name, params] of source.matchAll(/record\s+(\w+)\s*\(([^)]*)\)/g)) {
    records[name!] = params!
      .split(',')
      .map((p) => p.trim().split(/\s+/).pop()!)
      .filter(Boolean)
  }
  return records
}

const sorted = (values: readonly string[]) => [...values].sort()

describe.skipIf(!hasBackend)('contrato do Overview × backend (drift)', () => {
  it.each([
    ['overview/domain/enums/OverviewState.java', OVERVIEW_STATES],
    ['overview/domain/enums/PeriodType.java', PERIOD_TYPES],
    ['overview/domain/enums/MovementFlow.java', MOVEMENT_FLOWS],
    ['accounts/domain/enums/AccountType.java', ACCOUNT_TYPES],
    ['transactions/domain/enums/TransactionType.java', TRANSACTION_TYPES],
    ['transactions/domain/enums/TransactionStatus.java', TRANSACTION_STATUSES],
    ['transactions/domain/enums/AdjustmentDirection.java', ADJUSTMENT_DIRECTIONS],
  ])('enum %s', (path, frontend) => {
    expect(sorted(frontend)).toEqual(sorted(javaEnum(path)))
  })

  it('records de OverviewResponse têm exatamente os campos do contrato manual', () => {
    const records = javaRecords('overview/presentation/dto/response/OverviewResponse.java')
    for (const name of [
      'OverviewResponse', 'Period', 'Summary', 'CashFlow', 'Transfers', 'AccountSummary', 'AccountRef', 'RecentTransaction',
    ] as const) {
      expect(sorted(records[name] ?? []), name).toEqual(sorted(RECORD_FIELDS[name]))
    }
  })

  it.each([
    ['transactions/domain/enums/TransactionSource.java', TRANSACTION_SOURCES],
    ['accounts/domain/enums/AccountStatus.java', ACCOUNT_STATUSES],
    ['ingestion/domain/enums/ImportFormat.java', IMPORT_FORMATS],
    ['ingestion/domain/enums/ImportStatus.java', IMPORT_STATUSES],
    ['ingestion/domain/enums/RecordStatus.java', RECORD_STATUSES],
  ])('enum %s', (path, frontend) => {
    expect(sorted(frontend)).toEqual(sorted(javaEnum(path)))
  })

  it('records de Accounts e Imports têm exatamente os campos do contrato manual', () => {
    const files: [string, (keyof typeof RECORD_FIELDS)[]][] = [
      ['accounts/presentation/dto/response/AccountResponse.java', ['AccountResponse']],
      ['accounts/presentation/dto/response/AccountListResponse.java', ['AccountListResponse']],
      ['ingestion/presentation/dto/response/ImportResponse.java', ['ImportResponse', 'Lines']],
      ['ingestion/presentation/dto/response/ImportRecordResponse.java', ['ImportRecordResponse', 'Issue']],
      ['ingestion/presentation/dto/response/ImportPageResponses.java', ['ImportPageResponse', 'ImportRecordPageResponse']],
    ]
    for (const [path, names] of files) {
      const records = javaRecords(path)
      for (const name of names) expect(sorted(records[name] ?? []), name).toEqual(sorted(RECORD_FIELDS[name]))
    }
    // O record Java se chama Period (aninhado em ImportResponse); no contrato, ImportPeriod.
    expect(sorted(javaRecords('ingestion/presentation/dto/response/ImportResponse.java').Period ?? [])).toEqual(
      sorted(RECORD_FIELDS.ImportPeriod),
    )
  })

  it('requests de Accounts têm exatamente os campos do contrato manual', () => {
    for (const name of ['CreateAccountRequest', 'UpdateAccountRequest'] as const) {
      const records = javaRecords(`accounts/presentation/dto/request/${name}.java`)
      expect(sorted(records[name] ?? []), name).toEqual(sorted(RECORD_FIELDS[name]))
    }
    // No PUT, includedInTotal é obrigatório (substituição completa); no POST, opcional.
    const notNull = /@NotNull\s+Boolean\s+includedInTotal/
    expect(read('accounts/presentation/dto/request/UpdateAccountRequest.java')).toMatch(notNull)
    expect(read('accounts/presentation/dto/request/CreateAccountRequest.java')).not.toMatch(notNull)
  })

  it('limites de nome e instituição são os mesmos do domínio', () => {
    const max = (path: string) => Number(/MAX_LENGTH\s*=\s*(\d+)/.exec(read(path))?.[1])
    expect(max('accounts/domain/valueobject/AccountName.java')).toBe(ACCOUNT_NAME_MAX_LENGTH)
    expect(max('accounts/domain/valueobject/InstitutionName.java')).toBe(INSTITUTION_NAME_MAX_LENGTH)
  })

  it('records e requests de Transactions têm exatamente os campos do contrato manual', () => {
    const files: [string, (keyof typeof RECORD_FIELDS)[]][] = [
      ['transactions/presentation/dto/response/TransactionResponse.java', ['TransactionResponse']],
      ['transactions/presentation/dto/response/TransactionPageResponse.java', ['TransactionPageResponse']],
      ['transactions/presentation/dto/request/CreateTransactionRequest.java', ['CreateTransactionRequest']],
      ['transactions/presentation/dto/request/UpdateTransactionRequest.java', ['UpdateTransactionRequest']],
    ]
    for (const [path, names] of files) {
      const records = javaRecords(path)
      for (const name of names) expect(sorted(records[name] ?? []), name).toEqual(sorted(RECORD_FIELDS[name]))
    }
    expect(read('transactions/presentation/dto/response/TransactionResponse.java')).toMatch(/MoneyDto\s+amount\b/)
  })

  it('limites de Transactions (descrição, busca, página, Idempotency-Key) são os do backend', () => {
    const constant = (path: string, name: string) => Number(new RegExp(`${name}\\s*=\\s*(\\d+)`).exec(read(path))?.[1])
    expect(constant('transactions/domain/valueobject/TransactionDescription.java', 'MAX_LENGTH')).toBe(
      TRANSACTION_DESCRIPTION_MAX_LENGTH,
    )
    expect(constant('transactions/application/usecase/ListTransactions.java', 'MAX_TEXT_LENGTH')).toBe(
      TRANSACTION_SEARCH_MAX_LENGTH,
    )
    expect(constant('transactions/application/usecase/ListTransactions.java', 'MAX_PAGE_SIZE')).toBe(TRANSACTION_MAX_PAGE_SIZE)
    expect(constant('transactions/domain/valueobject/IdempotencyKey.java', 'MAX_LENGTH')).toBe(IDEMPOTENCY_KEY_MAX_LENGTH)
  })

  it('direção da linha importada é só INFLOW/OUTFLOW', () => {
    const source = read('ingestion/presentation/dto/response/ImportRecordResponse.java')
    expect(source).toContain('"INFLOW"')
    expect(source).toContain('"OUTFLOW"')
    expect(sorted(RECORD_DIRECTIONS)).toEqual(['INFLOW', 'OUTFLOW'])
  })

  it('MoneyDto continua {amount: String, currency: String}', () => {
    const source = read('shared/presentation/money/MoneyDto.java')
    expect(source).toMatch(/record\s+MoneyDto\s*\(\s*@NotBlank\s+String\s+amount\s*,\s*@NotBlank\s+String\s+currency\s*\)/)
  })

  it('dinheiro do Overview trafega como MoneyDto (string), nunca como número', () => {
    const source = read('overview/presentation/dto/response/OverviewResponse.java')
    for (const field of ['totalBalance', 'income', 'expense', 'refunds', 'net', 'volume', 'balance', 'amount']) {
      expect(source, field).toMatch(new RegExp(`MoneyDto\\s+${field}\\b`))
    }
  })
})
