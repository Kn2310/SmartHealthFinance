import { describe, expect, it } from 'vitest'
import { formatMoney, formatMoneySpoken, isNegative, parseAmount } from './money'

const brl = (amount: string) => ({ amount, currency: 'BRL' })

describe('formatMoney', () => {
  it('formata BRL com separadores pt-BR', () => {
    expect(formatMoney(brl('7627.52'))).toBe('R$ 7.627,52')
  })

  it('preserva a precisão de valores grandes (sem passar por number)', () => {
    expect(formatMoney(brl('12345678901234567.89'))).toBe('R$ 12.345.678.901.234.567,89')
    expect(formatMoney(brl('9007199254740993.01'))).toBe('R$ 9.007.199.254.740.993,01')
  })

  it('completa centavos e preserva zeros à direita', () => {
    expect(formatMoney(brl('10'))).toBe('R$ 10,00')
    expect(formatMoney(brl('10.5'))).toBe('R$ 10,50')
    expect(formatMoney(brl('0.07'))).toBe('R$ 0,07')
  })

  it('mais de 2 casas decimais: preserva o valor exato, sem truncar nem arredondar', () => {
    // Hoje BRL só aceita 2 casas no domínio; se isso mudar, o valor aparece exato em vez de errado.
    expect(formatMoney(brl('10.125'))).toBe('R$ 10,125')
    expect(formatMoney(brl('10.1299'))).toBe('R$ 10,1299')
    expect(formatMoney(brl('0.0001'))).toBe('R$ 0,0001')
    expect(formatMoney(brl('-0.005'), { sign: 'auto' })).toBe('− R$ 0,005')
  })

  it('zeros à direita além da 2ª casa (escala 4 do Money) são omitidos sem perder nada', () => {
    expect(formatMoney(brl('7627.5200'))).toBe('R$ 7.627,52')
    expect(formatMoney(brl('10.0000'))).toBe('R$ 10,00')
    expect(formatMoney(brl('10.1250'))).toBe('R$ 10,125')
  })

  it('marca valores negativos com sinal de menos tipográfico', () => {
    expect(formatMoney(brl('-420.00'))).toBe('− R$ 420,00')
  })

  it('zero real é exibido como zero, sem sinal', () => {
    expect(formatMoney(brl('0.00'))).toBe('R$ 0,00')
    expect(formatMoney(brl('0.00'), { sign: 'always' })).toBe('R$ 0,00')
    expect(formatMoney(brl('-0.00'))).toBe('R$ 0,00')
  })

  it('null/undefined (sem dados) NÃO vira zero', () => {
    expect(formatMoney(null)).toBeNull()
    expect(formatMoney(undefined)).toBeNull()
  })

  it('valor malformado não vira R$ 0,00', () => {
    expect(formatMoney(brl('abc'))).toBeNull()
  })

  it('aplica o sinal semântico de entrada e saída (despesa chega positiva)', () => {
    expect(formatMoney(brl('1250.00'), { sign: 'inflow' })).toBe('+ R$ 1.250,00')
    expect(formatMoney(brl('420.00'), { sign: 'outflow' })).toBe('− R$ 420,00')
    expect(formatMoney(brl('0.00'), { sign: 'outflow' })).toBe('R$ 0,00')
  })

  it('sign=always marca positivos; sign=never mostra o módulo', () => {
    expect(formatMoney(brl('2953.80'), { sign: 'always' })).toBe('+ R$ 2.953,80')
    expect(formatMoney(brl('-10.00'), { sign: 'never' })).toBe('R$ 10,00')
  })

  it('moedas diferentes de BRL usam o código', () => {
    expect(formatMoney({ amount: '5.00', currency: 'USD' })).toBe('USD 5,00')
  })
})

describe('formatMoneySpoken', () => {
  it('troca o sinal por palavra para leitores de tela', () => {
    expect(formatMoneySpoken(brl('420.00'), { sign: 'outflow' })).toBe('menos R$ 420,00')
    expect(formatMoneySpoken(brl('1250.00'), { sign: 'inflow' })).toBe('mais R$ 1.250,00')
    expect(formatMoneySpoken(brl('7627.52'))).toBe('R$ 7.627,52')
    expect(formatMoneySpoken(null)).toBeNull()
  })
})

describe('parseAmount / isNegative', () => {
  it('lê sinal, inteiro e fração', () => {
    expect(parseAmount('-12.340')).toEqual({ negative: true, integer: '12', fraction: '340' })
    expect(parseAmount('007.5')).toEqual({ negative: false, integer: '7', fraction: '5' })
  })

  it('isNegative ignora null e zero negativo', () => {
    expect(isNegative(null)).toBe(false)
    expect(isNegative(brl('-0.00'))).toBe(false)
    expect(isNegative(brl('-1.00'))).toBe(true)
  })
})
