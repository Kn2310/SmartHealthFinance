import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { axeViolations } from '@/test/a11y'
import { QuickActions } from './QuickActions'

describe('Ações rápidas (D-Home)', () => {
  it('atalhos do 03-screens; o Copilot abre /copilot (ADR-0041 §6)', async () => {
    const { container } = render(<QuickActions />)
    const section = screen.getByRole('region', { name: 'Ações rápidas' })
    expect(within(section).getByRole('link', { name: /Importar extrato/ })).toHaveAttribute('href', '/import')
    expect(within(section).getByRole('link', { name: /Adicionar conta/ })).toHaveAttribute('href', '/accounts/new')
    expect(within(section).getByRole('link', { name: /Criar objetivo/ })).toHaveAttribute('href', '/goals/new')
    expect(within(section).getByRole('link', { name: /Perguntar ao Copilot/ })).toHaveAttribute('href', '/copilot')
    expect(within(section).queryByText(/Em breve/)).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })
})
