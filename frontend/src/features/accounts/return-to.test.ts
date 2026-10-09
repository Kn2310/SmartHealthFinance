import { describe, expect, it } from 'vitest'
import { cancelDestination, destinationAfterCreate, parseReturnTo } from './return-to'

const ID = '01922f5e-0000-7000-8000-0000000000a1'

describe('retorno depois de criar conta (sem open redirect)', () => {
  it.each([
    ['import', 'import'],
    ['IMPORT', null],
    ['https://evil.test', null],
    ['//evil.test', null],
    ['/import', null],
    [['import', 'x'], null],
    [undefined, null],
  ])('parseReturnTo(%j) = %j', (value, expected) => {
    expect(parseReturnTo(value)).toBe(expected)
  })

  it('import abre a importação com a nova conta; sem retorno, a lista', () => {
    expect(destinationAfterCreate('import', ID)).toBe(`/import?accountId=${ID}`)
    expect(destinationAfterCreate(null, ID)).toBe('/accounts')
  })

  it('id que não é UUID nunca entra na URL', () => {
    expect(destinationAfterCreate('import', '../x?y=1')).toBe('/accounts')
  })

  it('cancelar volta para a origem', () => {
    expect(cancelDestination('import')).toBe('/import')
    expect(cancelDestination(null)).toBe('/accounts')
  })
})
