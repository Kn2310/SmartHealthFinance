import { describe, expect, it } from 'vitest'
import { formatPeriodRange, formatShortDate, validateCustomRange } from './date'

describe('formatShortDate', () => {
  it('formata sem deslocamento de fuso', () => {
    expect(formatShortDate('2026-09-27')).toBe('27 set.')
    expect(formatShortDate('2026-01-01')).toBe('1 jan.')
  })

  it('devolve o texto original quando inválido', () => {
    expect(formatShortDate('ontem')).toBe('ontem')
  })
})

describe('formatPeriodRange', () => {
  it('mesmo mês, meses distintos e anos distintos', () => {
    expect(formatPeriodRange('2026-09-01', '2026-09-27')).toBe('1 a 27 de setembro')
    expect(formatPeriodRange('2026-08-28', '2026-09-03')).toBe('28 de ago. a 3 de set.')
    expect(formatPeriodRange('2025-12-20', '2026-01-10')).toBe('20 de dez. de 2025 a 10 de jan. de 2026')
  })
})

describe('validateCustomRange', () => {
  it('exige as duas datas', () => {
    expect(validateCustomRange('', '2026-09-01')).toBe('REQUIRED')
    expect(validateCustomRange('2026-09-01', '')).toBe('REQUIRED')
  })

  it('rejeita data final anterior à inicial', () => {
    expect(validateCustomRange('2026-09-10', '2026-09-01')).toBe('ORDER')
  })

  it('aceita um único dia e o limite de 366 dias (inclusivo)', () => {
    expect(validateCustomRange('2026-09-01', '2026-09-01')).toBeNull()
    expect(validateCustomRange('2025-09-28', '2026-09-28')).toBeNull() // 366 dias
  })

  it('rejeita mais de 366 dias', () => {
    expect(validateCustomRange('2025-09-27', '2026-09-28')).toBe('TOO_LONG') // 367 dias
  })

  it('rejeita datas inválidas', () => {
    expect(validateCustomRange('2026-13-01', '2026-09-01')).toBe('INVALID')
  })
})
