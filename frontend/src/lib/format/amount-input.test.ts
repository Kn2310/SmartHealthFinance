import { describe, expect, it } from 'vitest'
import { parseAmountInput } from './amount-input'
import { businessToday, isIsoDate, monthRange } from './business-date'

describe('parseAmountInput (texto → string decimal, sem number)', () => {
  it.each([
    ['1234', '1234.00'],
    ['1234,5', '1234.50'],
    ['1.234,56', '1234.56'],
    ['R$ 1.234,56', '1234.56'],
    ['  86,40 ', '86.40'],
    ['10.5', '10.50'],
    ['1234.56', '1234.56'],
    ['1.234', '1234.00'],
    ['1.234.567,89', '1234567.89'],
    ['0,01', '0.01'],
    ['007,10', '7.10'],
    ['5,000', '5.00'],
    // 0,1 + 0,2 em ponto flutuante seria 0.30000000000000004; aqui nunca há aritmética.
    ['999999999999999,99', '999999999999999.99'],
  ])('%s → %s', (input, expected) => {
    expect(parseAmountInput(input)).toEqual({ ok: true, value: expected })
  })

  it.each([
    ['', 'REQUIRED'],
    ['   ', 'REQUIRED'],
    ['-10', 'NOT_POSITIVE'],
    ['0', 'NOT_POSITIVE'],
    ['0,00', 'NOT_POSITIVE'],
    ['1,234', 'TOO_MANY_DECIMALS'],
    ['10,5,1', 'INVALID_FORMAT'],
    ['12a', 'INVALID_FORMAT'],
    ['1e3', 'INVALID_FORMAT'],
    ['1.23.4', 'INVALID_FORMAT'],
    ['12,', 'INVALID_FORMAT'],
    ['1234567890123456', 'TOO_LARGE'],
  ])('%j → %s', (input, error) => {
    expect(parseAmountInput(input)).toEqual({ ok: false, error })
  })
})

describe('data de negócio (America/Sao_Paulo)', () => {
  it('23h30 em Brasília ainda é o mesmo dia, embora UTC já esteja no seguinte', () => {
    expect(businessToday(new Date('2026-10-10T02:30:00Z'))).toBe('2026-10-09')
    expect(businessToday(new Date('2026-10-10T03:30:00Z'))).toBe('2026-10-10')
  })

  it('mês corrente e anterior, inclusive na virada do ano e em fevereiro', () => {
    expect(monthRange('2026-10-09')).toEqual({ from: '2026-10-01', to: '2026-10-31' })
    expect(monthRange('2026-01-15', -1)).toEqual({ from: '2025-12-01', to: '2025-12-31' })
    expect(monthRange('2028-03-01', -1)).toEqual({ from: '2028-02-01', to: '2028-02-29' })
  })

  it('isIsoDate recusa datas inexistentes e outros formatos', () => {
    expect(isIsoDate('2026-02-28')).toBe(true)
    expect(isIsoDate('2026-02-30')).toBe(false)
    expect(isIsoDate('09/10/2026')).toBe(false)
    expect(isIsoDate(20261009)).toBe(false)
  })
})
