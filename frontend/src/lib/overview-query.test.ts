import { describe, expect, it } from 'vitest'
import { parseOverviewQuery, toSearchParams } from './overview-query'

const parse = (qs: string) => parseOverviewQuery(new URLSearchParams(qs))

describe('overview query', () => {
  it('CURRENT_MONTH é o padrão e não vai para a URL', () => {
    expect(parse('')).toEqual({ period: 'CURRENT_MONTH' })
    expect(toSearchParams({ period: 'CURRENT_MONTH' }).toString()).toBe('')
  })

  it('valores desconhecidos caem no padrão', () => {
    expect(parse('period=YEAR')).toEqual({ period: 'CURRENT_MONTH' })
  })

  it('PREVIOUS_MONTH ignora from/to', () => {
    expect(parse('period=PREVIOUS_MONTH&from=2026-01-01&to=2026-01-31')).toEqual({ period: 'PREVIOUS_MONTH' })
  })

  it('CUSTOM exige from e to; sem eles volta ao padrão', () => {
    expect(parse('period=CUSTOM&from=2026-09-01')).toEqual({ period: 'CURRENT_MONTH' })
    expect(parse('period=CUSTOM&from=2026-09-01&to=2026-09-10')).toEqual({
      period: 'CUSTOM',
      from: '2026-09-01',
      to: '2026-09-10',
    })
  })

  it('serializa o período personalizado', () => {
    expect(toSearchParams({ period: 'CUSTOM', from: '2026-09-01', to: '2026-09-10' }).toString()).toBe(
      'period=CUSTOM&from=2026-09-01&to=2026-09-10',
    )
  })
})
