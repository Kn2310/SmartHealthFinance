import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cents } from './money'

export const STATEMENT_PATH = resolve(__dirname, '../fixtures/extrato-ficticio-2026-jul-set.csv')

export interface StatementLine {
  /** yyyy-MM-dd */
  date: string
  description: string
  cents: bigint
}

/** Lê o extrato fictício do mesmo jeito que o modelo canônico (ADR-0009 §6): `data;descricao;valor;id`. */
export function readStatement(): StatementLine[] {
  const [, ...rows] = readFileSync(STATEMENT_PATH, 'utf8').replace(/^﻿/, '').trim().split(/\r?\n/)
  return rows.map((row) => {
    const [date, description, value] = row.split(';')
    const [day, month, year] = date!.split('/')
    return { date: `${year}-${month}-${day}`, description: description!, cents: cents(value!) }
  })
}
