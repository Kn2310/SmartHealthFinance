import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cents } from './money'

export const CARD_STATEMENT_PATH = resolve(__dirname, '../fixtures/fatura-ficticia-aurora.csv')

/** Dia de fechamento do cartão fictício Aurora (09-dados-de-exemplo): fecha 28, vence 05. */
export const AURORA_CLOSING_DAY = 28

const pad = (n: number) => String(n).padStart(2, '0')
const iso = (d: Date) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
const br = (isoDate: string) => isoDate.split('-').reverse().join('/')

/** "Hoje" no fuso de negócio (ADR-0006 §10), como o backend calcula a fatura atual. */
export function businessToday(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo' }).format(new Date())
}

/**
 * Início da fatura atual de um cartão que fecha no dia 28 (ADR-0025 §5: período `[fechamento anterior, fechamento)`):
 * do dia 28 em diante, a fatura atual começou neste mês; antes, no mês anterior.
 */
function currentPeriodStart(today: string): Date {
  const [year, month, day] = today.split('-').map(Number) as [number, number, number]
  const monthIndex = day >= AURORA_CLOSING_DAY ? month - 1 : month - 2
  return new Date(Date.UTC(year, monthIndex, AURORA_CLOSING_DAY))
}

export interface CardStatementLine {
  /** yyyy-MM-dd */
  date: string
  description: string
  /** Sinal da fatura: positivo = compra, negativo = crédito ou pagamento. */
  cents: bigint
  installment: string | null
}

/**
 * A fatura fictícia com as datas resolvidas a partir de hoje: `{HOJE}` cai na fatura atual (a primeira acompanhada de um
 * cartão cadastrado hoje) e `{COMPRA-NOTEBOOK}` é 2 meses antes do início dela, para a parcela 3 de 10 cair na fatura
 * atual e as parcelas 1 e 2 ficarem antes do acompanhamento (ADR-0025 §15).
 */
export function cardStatement(): { buffer: Buffer; lines: CardStatementLine[]; today: string; purchaseDate: string } {
  const today = businessToday()
  const start = currentPeriodStart(today)
  const purchase = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() - 2, AURORA_CLOSING_DAY))
  const purchaseDate = iso(purchase)
  const content = readFileSync(CARD_STATEMENT_PATH, 'utf8')
    .replaceAll('{HOJE}', br(today))
    .replaceAll('{COMPRA-NOTEBOOK}', br(purchaseDate))

  const [, ...rows] = content.replace(/^﻿/, '').trim().split(/\r?\n/)
  const lines = rows.map((row) => {
    const [date, description, value, installment] = row.split(';')
    const [day, month, year] = date!.split('/')
    return { date: `${year}-${month}-${day}`, description: description!, cents: cents(value!), installment: installment || null }
  })
  return { buffer: Buffer.from(content, 'utf8'), lines, today, purchaseDate }
}
