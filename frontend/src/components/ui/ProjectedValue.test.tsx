import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { estimate, fact } from '@/features/forecast/fixtures'
import { axeViolations } from '@/test/a11y'
import { ProjectedValue } from './ProjectedValue'

const text = (el: Element | null) => el?.textContent?.replace(/ /g, ' ') ?? ''

describe('<ProjectedValue>', () => {
  it('ESTIMATED: "≈ R$ 4.664" sem centavos, selo "Estimado" e a faixa', async () => {
    const { container } = render(<ProjectedValue value={estimate('4664.10', '4400.00', '5100.00', { factPart: '3846.20' })} />)
    const visual = container.querySelector('[aria-hidden="true"]')!
    expect(text(visual)).toBe('≈ R$ 4.664')
    expect(container.textContent).not.toContain('4.664,10')
    expect(screen.getByText('Estimado')).toBeInTheDocument()
    expect(screen.getByText('entre R$ 4.400 e R$ 5.100')).toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('texto alternativo para leitor de tela; "≈", tracejado, faixa visual e selo são aria-hidden', () => {
    const { container } = render(<ProjectedValue value={estimate('4664.10', '4400.00', '5100.00')} />)
    expect(container.querySelector('.sr-only')).toHaveTextContent(
      'aproximadamente R$ 4.664, entre R$ 4.400 e R$ 5.100, estimado',
    )
    const hidden = [...container.querySelectorAll('[aria-hidden="true"]')].map((el) => text(el))
    expect(hidden).toEqual(expect.arrayContaining(['≈ R$ 4.664', 'Estimado', 'entre R$ 4.400 e R$ 5.100']))
    expect(container.querySelector('[data-basis="estimated"]')).not.toBeNull()
  })

  it('FACT: centavos e selo "Confirmado"; parcial usa "Parcial"; nunca "≈"', () => {
    const { container, rerender } = render(<ProjectedValue value={fact('480.00')} />)
    expect(text(container)).toContain('R$ 480,00')
    expect(screen.getByText('Confirmado')).toBeInTheDocument()
    expect(container.textContent).not.toContain('≈')

    rerender(<ProjectedValue value={fact('1480.00', true)} />)
    expect(screen.getByText('Parcial')).toBeInTheDocument()
    expect(screen.queryByText('Confirmado')).not.toBeInTheDocument()
  })

  it('badge e faixa podem ser omitidos (a faixa fica em outro lugar)', () => {
    render(<ProjectedValue value={estimate('2532.10', '2400.00', '2700.00')} badge={false} range={false} />)
    expect(screen.queryByText('Estimado')).not.toBeInTheDocument()
    expect(screen.queryByText(/entre R\$ 2\.400/)).not.toBeInTheDocument()
  })

  it('valor inválido não vira R$ 0: mostra "sem dados"', () => {
    const broken = estimate('abc', '1', '2')
    const { container } = render(<ProjectedValue value={broken} />)
    expect(container.textContent).toBe('—sem dados')
  })
})
