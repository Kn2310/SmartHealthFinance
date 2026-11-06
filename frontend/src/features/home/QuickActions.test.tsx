import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { axeViolations } from '@/test/a11y'
import { QuickActions } from './QuickActions'

describe('Ações rápidas (D-Home)', () => {
  it('atalhos do 03-screens; Copilot desabilitado até o M11', async () => {
    const { container } = render(<QuickActions />)
    const section = screen.getByRole('region', { name: 'Ações rápidas' })
    expect(within(section).getByRole('link', { name: /Importar extrato/ })).toHaveAttribute('href', '/import')
    expect(within(section).getByRole('link', { name: /Adicionar conta/ })).toHaveAttribute('href', '/accounts/new')
    expect(within(section).getByRole('link', { name: /Criar objetivo/ })).toHaveAttribute('href', '/goals/new')
    expect(within(section).queryByRole('link', { name: /Copilot/ })).not.toBeInTheDocument()
    expect(within(section).getByText('Sobre seus dados · Em breve')).toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })
})
