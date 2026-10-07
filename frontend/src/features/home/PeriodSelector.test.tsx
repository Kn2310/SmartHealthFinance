import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { PeriodSelector } from './PeriodSelector'

describe('PeriodSelector', () => {
  it('marca o período atual e muda para o mês anterior', async () => {
    const onChange = vi.fn()
    render(<PeriodSelector value={{ period: 'CURRENT_MONTH' }} onChange={onChange} />)

    expect(screen.getByRole('button', { name: 'Este mês' })).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'Mês anterior' }))
    expect(onChange).toHaveBeenCalledWith({ period: 'PREVIOUS_MONTH' })
  })

  it('Personalizado abre os campos sem buscar até aplicar', async () => {
    const onChange = vi.fn()
    render(<PeriodSelector value={{ period: 'CURRENT_MONTH' }} onChange={onChange} />)

    await userEvent.click(screen.getByRole('button', { name: 'Personalizado' }))
    expect(screen.getByLabelText('De')).toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('exige as duas datas e anuncia o erro', async () => {
    const onChange = vi.fn()
    render(<PeriodSelector value={{ period: 'CUSTOM', from: '', to: '' }} onChange={onChange} />)

    await userEvent.click(screen.getByRole('button', { name: 'Aplicar' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Informe a data inicial e a final.')
    expect(onChange).not.toHaveBeenCalled()
  })

  it('rejeita intervalo invertido e acima de 366 dias', async () => {
    const onChange = vi.fn()
    render(<PeriodSelector value={{ period: 'CUSTOM', from: '2026-09-10', to: '2026-09-01' }} onChange={onChange} />)

    await userEvent.click(screen.getByRole('button', { name: 'Aplicar' }))
    expect(screen.getByRole('alert')).toHaveTextContent('A data final deve ser igual ou posterior à inicial.')

    const from = screen.getByLabelText('De')
    const to = screen.getByLabelText('Até')
    await userEvent.clear(from)
    await userEvent.type(from, '2025-01-01')
    await userEvent.clear(to)
    await userEvent.type(to, '2026-09-01')
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar' }))
    expect(screen.getByRole('alert')).toHaveTextContent('no máximo 366 dias')
    expect(onChange).not.toHaveBeenCalled()
  })

  it('aplica um intervalo válido', async () => {
    const onChange = vi.fn()
    render(<PeriodSelector value={{ period: 'CUSTOM', from: '2026-09-01', to: '2026-09-15' }} onChange={onChange} />)

    await userEvent.click(screen.getByRole('button', { name: 'Aplicar' }))
    expect(onChange).toHaveBeenCalledWith({ period: 'CUSTOM', from: '2026-09-01', to: '2026-09-15' })
  })
})
