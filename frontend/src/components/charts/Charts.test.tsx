import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { axeViolations } from '@/test/a11y'
import { CumulativeLine } from './CumulativeLine'
import { HorizontalBars } from './HorizontalBars'
import { MonthBars } from './MonthBars'
import { Waterfall } from './Waterfall'
import { magnitude, monthLabel, px, scale } from './geometry'

const brl = (amount: string) => ({ amount, currency: 'BRL' })

describe('gráficos do DS (SVG próprio)', () => {
  it('geometria só para pixels: zero seguro, proporção e rótulos', () => {
    expect(magnitude(brl('-420.50'))).toBe(420.5)
    expect(magnitude(null)).toBe(0)
    expect(scale(50, 100, 200)).toBe(100)
    expect(scale(10, 0, 200)).toBe(0)
    expect(px(10.04)).toBe(10)
    expect(monthLabel('2026-09')).toBe('set')
    expect(monthLabel('2026-09', true)).toBe('set/26')
  })

  it('barras por mês: SVG decorativo, mês parcial tracejado e tabela com os valores; acessível', async () => {
    const { container } = render(
      <MonthBars title="Receitas e despesas" series={[{ label: 'Receitas', tone: 'income' }, { label: 'Despesas', tone: 'expense' }]}
        data={[
          { month: '2026-08', partial: false, values: [brl('6500.00'), brl('4190.00')] },
          { month: '2026-09', partial: true, values: [brl('6800.00'), brl('3846.20')] },
        ]} caption="*Setembro vai até o dia 22." />,
    )
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
    expect(container.querySelectorAll('rect')).toHaveLength(4)
    // Nenhum estilo inline: a CSP de produção não permite (só atributos SVG e classes).
    expect(container.querySelector('[style]')).toBeNull()
    const table = screen.getByRole('table', { name: 'Receitas e despesas' })
    expect(within(table).getByText('set/26 (parcial)')).toBeInTheDocument()
    expect(within(table).getByText('R$ 3.846,20')).toBeInTheDocument()
    // Tooltip de hover: um por mês, com a legenda e o valor de cada coluna; oculto para leitores de tela.
    const hits = container.querySelector('div[aria-hidden="true"]')!
    expect(hits.children).toHaveLength(2)
    expect(hits.children[1]).toHaveTextContent('set/26 (parcial)')
    expect(hits.children[1]).toHaveTextContent('Receitas')
    expect(hits.children[1]).toHaveTextContent('R$ 6.800,00')
    expect(hits.children[1]).toHaveTextContent('Despesas')
    expect(await axeViolations(container)).toEqual([])
  })

  it('linha acumulada: mês atual e anterior na tabela', () => {
    render(<CumulativeLine title="Acumulado" currentLabel="setembro" previousLabel="agosto"
      current={[{ day: 1, amount: brl('10.00') }, { day: 2, amount: brl('30.00') }]}
      previous={[{ day: 1, amount: brl('5.00') }, { day: 2, amount: brl('6.00') }, { day: 3, amount: brl('9.00') }]} />)
    const rows = within(screen.getByRole('table', { name: 'Acumulado' })).getAllByRole('row')
    expect(rows).toHaveLength(4)
    expect(within(rows[3]!).getByText('R$ 9,00')).toBeInTheDocument()
  })

  it('barras horizontais e waterfall são listas textuais', async () => {
    const { container } = render(
      <>
        <HorizontalBars label="Categorias" items={[{ key: 'a', label: 'Moradia', amount: brl('1986.32'), note: '51,6%' }]} />
        <Waterfall label="Composição" steps={[
          { key: 'in', label: 'Entradas', amount: brl('6800.00'), kind: 'inflow' },
          { key: 'out', label: 'Moradia', amount: brl('1986.32'), kind: 'outflow' },
          { key: 'net', label: 'Resultado', amount: brl('4813.68'), kind: 'result' },
        ]} />
      </>,
    )
    expect(within(screen.getByRole('list', { name: 'Categorias' })).getByText('51,6%')).toBeInTheDocument()
    const waterfall = screen.getByRole('list', { name: 'Composição' })
    expect(within(waterfall).getAllByRole('listitem')).toHaveLength(3)
    expect(within(waterfall).getByText('menos R$ 1.986,32')).toBeInTheDocument()
    expect(container.querySelector('[style]')).toBeNull()
    expect(await axeViolations(container)).toEqual([])
  })
})
