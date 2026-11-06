import { describe, expect, it } from 'vitest'
import { confidenceLine, estimateStatus, evidenceMonths, limitationsText, patternPhrase, statusNote } from './format'
import { estimate, readyMonth, notBuiltMonth, recalculating } from './fixtures'

describe('formatação do Forecast', () => {
  it('confiança e base: "confiança média · pelo seu padrão dos últimos 3 meses"', () => {
    const value = estimate('4664.10', '4400.00', '5100.00')
    expect(confidenceLine(value, 3)).toBe('confiança média · pelo seu padrão dos últimos 3 meses')
    expect(confidenceLine(value, 3, 'pelo seu histórico')).toBe('confiança média · pelo seu histórico')
    expect(confidenceLine({ ...value, confidence: 'HIGH' }, 1)).toBe('confiança alta · pelo seu padrão do último mês')
    expect(confidenceLine({ ...value, confidence: 'LOW' }, 0)).toBe('confiança baixa · pelo seu padrão')
    expect(patternPhrase(6)).toBe('pelo seu padrão dos últimos 6 meses')
  })

  it('N vem das evidências mensais da resposta', () => {
    expect(evidenceMonths(estimate('1.00', '1.00', '1.00', { months: 4 }))).toBe(4)
  })

  it('limitações viram frases curtas, sem o código', () => {
    expect(limitationsText([])).toBeNull()
    expect(limitationsText(['SHORT_HISTORY', 'HIGH_VARIABILITY'])).toBe(
      'Histórico curto: a estimativa pode mudar. Seus valores variam bastante.',
    )
    expect(limitationsText(['PARTIAL_CARD_HISTORY'])).not.toMatch(/PARTIAL/)
  })

  it('estados do envelope e notas', () => {
    expect(estimateStatus('READY', readyMonth().freshness)).toBe('READY')
    expect(estimateStatus('READY', recalculating(readyMonth()).freshness)).toBe('RECALCULATING')
    expect(estimateStatus('NOT_BUILT', notBuiltMonth().freshness)).toBe('NOT_BUILT')
    expect(estimateStatus('INSUFFICIENT_HISTORY', readyMonth().freshness)).toBe('INSUFFICIENT_HISTORY')
    expect(statusNote('RECALCULATING')).toBe('Atualizando estimativas…')
    expect(statusNote('NOT_BUILT')).toBe('Estimativas em preparação.')
    expect(statusNote('INSUFFICIENT_HISTORY')).toBe('As estimativas aparecem depois de 3 meses de histórico.')
    expect(statusNote('READY')).toBeNull()
  })
})
