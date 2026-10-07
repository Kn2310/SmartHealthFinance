import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Money } from './Money'

const brl = (amount: string) => ({ amount, currency: 'BRL' })

describe('<Money>', () => {
  it('null mostra "—" com "sem dados" para leitor de tela (nunca R$ 0,00)', () => {
    const { container } = render(<Money value={null} />)
    expect(container.textContent).toBe('—sem dados')
    expect(container.textContent).not.toContain('R$')
  })

  it('zero real é R$ 0,00', () => {
    const { container } = render(<Money value={brl('0.00')} />)
    expect(container.textContent).toBe('R$ 0,00')
  })

  it('sinal, símbolo e dígitos unidos por espaço não separável; quebra só depois dos separadores de milhar', () => {
    const { container } = render(<Money value={brl('12345678.90')} />)
    const span = container.querySelector('.money')!
    expect(span.textContent).toBe('R$ 12.345.678,90')
    expect(span.querySelectorAll('wbr')).toHaveLength(2)
    // nenhum espaço comum (que permitiria separar "R$" do número ou o sinal do valor)
    expect(span.textContent).not.toMatch(/ /)
  })

  it('negativo: visual com "−" e texto falado "menos"', () => {
    const { container } = render(<Money value={brl('-420.00')} />)
    expect(container.querySelector('[aria-hidden="true"]')!.textContent).toBe('− R$ 420,00')
    expect(container.querySelector('.sr-only')!.textContent).toBe('menos R$ 420,00')
  })
})
