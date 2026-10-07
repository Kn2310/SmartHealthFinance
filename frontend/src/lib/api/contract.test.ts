// @vitest-environment node
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  ACCOUNT_TYPES,
  ADJUSTMENT_DIRECTIONS,
  MOVEMENT_FLOWS,
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
  const body = /enum\s+\w+\s*\{([^;]*);/.exec(read(path))?.[1] ?? ''
  return body.split(',').map((c) => c.trim()).filter(Boolean)
}

function javaRecords(path: string): Record<string, string[]> {
  const records: Record<string, string[]> = {}
  for (const [, name, params] of read(path).matchAll(/record\s+(\w+)\s*\(([^)]*)\)/g)) {
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
