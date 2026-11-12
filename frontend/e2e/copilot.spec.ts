import { writeFileSync } from 'node:fs'
import type { Locator, Page } from '@playwright/test'
import { expect, test } from './support/test'
import { brl, cents } from './support/money'

const CHECKING = 'Conta Copilot E2E'
const CARD = { nickname: 'Cartão Copilot E2E', last4: '5320' }
const PURCHASE = { amount: '600,00', installments: '3', description: 'Estante Copilot E2E' }
/** Fatura aberta: a 1ª parcela (600 / 3). */
const INVOICE_AMOUNT = '200,00'

const DATA_SWITCH = 'Permitir que o Copilot use meus dados'
const HISTORY_SWITCH = 'Guardar histórico de conversas do Copilot'

const CONSENT_REQUIRED_TEXT = /Para responder, o Copilot precisa usar seus dados financeiros/
const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
/** O `next dev` do compose (local e CI) compila cada rota na primeira visita e pode passar dos 15 s padrão. */
const NAVIGATION = 60_000

function businessToday(): { year: number; month: number; day: number } {
  const [year, month, day] = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo', year: 'numeric',
    month: '2-digit', day: '2-digit' }).format(new Date()).split('-').map(Number) as [number, number, number]
  return { year, month, day }
}

function shift({ year, month }: { year: number; month: number }, back: number) {
  const index = year * 12 + (month - 1) - back
  return { year: Math.floor(index / 12), month: (index % 12) + 1 }
}

/** Período avaliado dos insights (ADR-0021 §1), como em insights.spec.ts. */
function evaluated() {
  const today = businessToday()
  if (today.day < 7) return shift(today, 1)
  return { year: today.year, month: today.month }
}

/**
 * Copilot ponta a ponta (M11 Parte E, ADR-0041) com o adapter SIMULADO do backend (ADR-0039 §2): a intenção vem de
 * palavras-chave e a resposta cita os 3 primeiros valores do contexto por referência, com a primeira ação oferecida.
 *
 * Por que o caso "números iguais às telas" usa `from=invoice`: a pergunta "Por que esta fatura está mais alta?" cai em
 * CARDS ("fatura"); o simulado cita os primeiros valores da ferramenta de cartões: limite total (R$ 1.000,00), limite
 * utilizado (R$ 600,00) e "total a pagar nas faturas abertas" (R$ 200,00), que é o "Valor até agora" da tela da fatura.
 * O número é lido da própria tela da fatura (não hardcoded) e comparado com o negrito da resposta.
 *
 * Dados: extrato CSV (como insights.spec.ts: gera os insights D1, D2, D4 e D10) e um cartão com compra de 600 em 3x
 * (fatura aberta de 200). O fechamento fica a ~10 dias, então os números valem em qualquer dia do mês?.
 */
test('copilot: consentimento, contexto, números iguais às telas, ações, histórico e revogação', async ({ signedIn: page }, testInfo) => {
  test.setTimeout(600_000)
  const main = page.getByRole('main')

  // ---------------------------------------------------------------- seed
  const p = evaluated()
  const lines = [5, 4, 3, 2, 1, 0].flatMap((back, i) => {
    const { year, month } = shift(p, back)
    const day = `01/${String(month).padStart(2, '0')}/${year}`
    return [`${day};Salário Empresa Fictícia;5.000,00;s${i}`,
      `${day};Restaurante Sabor;-${back === 0 ? '900,00' : '300,00'};r${i}`]
  })
  lines.push(`01/${String(p.month).padStart(2, '0')}/${p.year};Compra Loja Xyz;-600,00;x0`)
  const csv = testInfo.outputPath('extrato-copilot.csv')
  writeFileSync(csv, ['data;descricao;valor;id', ...lines].join('\n'), 'utf8')

  await page.goto('/import')
  await main.getByRole('link', { name: 'Adicionar conta' }).click()
  await page.getByLabel('Nome da conta').fill(CHECKING)
  await page.getByRole('button', { name: 'Criar e continuar' }).click()
  await expect(page).toHaveURL(/\/import\?accountId=/, { timeout: NAVIGATION })
  await page.locator('input[type="file"]').setInputFiles(csv)
  await page.getByRole('button', { name: 'Continuar' }).click()
  await page.getByRole('button', { name: `Importar ${lines.length} movimentações` }).click()
  await expect(page.getByRole('heading', { name: `${lines.length} movimentações importadas` })).toBeVisible({
    timeout: 60_000,
  })

  const closingDay = ((businessToday().day + 9) % 28) + 1
  const dueDay = closingDay <= 22 ? closingDay + 5 : closingDay + 5 - 28
  await page.goto('/cards')
  await main.getByRole('link', { name: 'Adicionar cartão' }).first().click()
  await expect(page).toHaveURL(/\/cards\/new\?from=cards/, { timeout: NAVIGATION })
  await page.getByLabel('Apelido').fill(CARD.nickname)
  await page.getByLabel('Bandeira').selectOption('ELO')
  await page.getByLabel('4 últimos dígitos').fill(CARD.last4)
  await page.getByLabel('Limite').fill('1.000,00')
  await page.getByLabel('Dia de fechamento').fill(String(closingDay))
  await page.getByLabel('Dia de vencimento').fill(String(dueDay))
  await page.getByRole('button', { name: 'Cadastrar cartão' }).click()
  await expect(page).toHaveURL(new RegExp(`/cards/${UUID}$`), { timeout: NAVIGATION })
  const cardId = page.url().split('/').pop()!

  await page.goto(`/transactions/new?type=CARD_PURCHASE&cardId=${cardId}`)
  await page.getByLabel('Valor total da compra').fill(PURCHASE.amount)
  await page.getByLabel('Parcelas').fill(PURCHASE.installments)
  await page.getByLabel('Descrição', { exact: true }).fill(PURCHASE.description)
  await page.getByRole('button', { name: 'Registrar transação' }).click()
  await expect(page).toHaveURL(/registered=posted/, { timeout: NAVIGATION })

  // Insights chegam por eventos com debounce (ADR-0022): recarrega até a geração.
  const spikeTitle = /Seus gastos com alimentação fora aumentaram 200%/
  await page.goto('/insights')
  await expect(async () => {
    await page.reload()
    await expect(main.getByRole('link', { name: spikeTitle })).toBeVisible({ timeout: 1_000 })
  }).toPass({ timeout: 150_000, intervals: [2_000, 3_000, 5_000] })

  // ---------------------------------------------------------------- 2. Home e 3. sem consentimento
  await page.goto('/home')
  await page.getByRole('region', { name: 'Ações rápidas' }).getByRole('link', { name: /Perguntar ao Copilot/ }).click()
  // O dev server compila a rota na primeira visita.
  await expect(page).toHaveURL(/\/copilot$/, { timeout: 90_000 })
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(page.getByText('O Copilot pode errar. Confira os dados usados em cada resposta.')).toBeVisible()

  await ask(page, 'Onde mais gastei este mês?')
  const blocked = page.getByRole('alert').filter({ hasText: CONSENT_REQUIRED_TEXT })
  await expect(blocked).toBeVisible()
  await expect(blocked.getByRole('link', { name: 'Rever consentimentos' })).toHaveAttribute('href', '/profile/consents')
  await expect(page.getByRole('article', { name: 'Resposta do Copilot' })).toHaveCount(0)

  // ---------------------------------------------------------------- 3. conceder COPILOT_DATA_USE
  await blocked.getByRole('link', { name: 'Rever consentimentos' }).click()
  await expect(page).toHaveURL(/\/profile\/consents$/, { timeout: NAVIGATION })
  const data = page.getByRole('switch', { name: DATA_SWITCH })
  const history = page.getByRole('switch', { name: HISTORY_SWITCH })
  await data.click()
  await expect(data).toHaveAttribute('aria-checked', 'true')
  await expect(history).toBeEnabled()

  // ---------------------------------------------------------------- 4. insight → Copilot
  await page.goto('/insights')
  await main.getByRole('link', { name: spikeTitle }).click()
  await expect(page).toHaveURL(new RegExp(`/insights/${UUID}$`), { timeout: NAVIGATION })
  const insightId = page.url().split('/').pop()!
  const insightLink = main.getByRole('link', { name: 'Perguntar ao Copilot' })
  await expect(insightLink).toHaveAttribute('href', `/copilot?from=insight&insightId=${insightId}`)
  await insightLink.click()
  await expect(page).toHaveURL(`/copilot?from=insight&insightId=${insightId}`, { timeout: NAVIGATION })

  // Sugestões do contexto do insight (ADR-0041 §5): nada é enviado sem a pessoa escolher.
  await expect(page.getByRole('button', { name: 'Por que isso aconteceu?' })).toBeVisible()
  await expect(page.getByRole('article', { name: 'Resposta do Copilot' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Isso afeta minha saúde financeira?' }).click()
  const insightAnswer = page.getByRole('article', { name: 'Resposta do Copilot' })
  await expect(insightAnswer).toBeVisible({ timeout: 60_000 })
  await expect(insightAnswer).toContainText('✦ Copilot')
  // Sem COPILOT_HISTORY a conversa é efêmera: a URL de contexto não muda (ADR-0041 §1).
  await expect(page).toHaveURL(`/copilot?from=insight&insightId=${insightId}`, { timeout: NAVIGATION })
  await expect(page.getByText('O histórico está desligado e as conversas não são guardadas.')).toBeVisible()

  // ---------------------------------------------------------------- 5. fatura: números iguais aos da tela
  await page.goto(`/cards/${cardId}/invoice`)
  const screenAmount = await readAmount(page.locator('#invoice-amount').locator('xpath=following-sibling::p[1]'))
  expect(screenAmount).toBe(brl(cents(INVOICE_AMOUNT)))
  await main.getByRole('link', { name: 'Perguntar ao Copilot' }).first().click()
  await expect(page).toHaveURL(new RegExp(`^.*/copilot\\?from=invoice&cardId=${cardId}&month=\\d{4}-\\d{2}$`), { timeout: NAVIGATION })
  const invoiceEntryUrl = page.url()
  const invoiceMonth = /month=(\d{4}-\d{2})/.exec(invoiceEntryUrl)![1]!

  await page.getByRole('button', { name: 'Por que esta fatura está mais alta?' }).click()
  const answer = page.getByRole('article', { name: 'Resposta do Copilot' })
  await expect(answer).toBeVisible({ timeout: 60_000 })
  await expect(answer).toContainText('✦ Copilot')
  const bolds = (await answer.locator('strong').allInnerTexts()).map(normalize)
  expect(bolds.length).toBeGreaterThan(0)
  expect(bolds, 'o negrito da resposta deve conter o valor da fatura exibido na tela').toContain(screenAmount)
  // Sem histórico: a URL fica em /copilot (com o contexto).
  await expect(page).toHaveURL(invoiceEntryUrl, { timeout: NAVIGATION })

  // ---------------------------------------------------------------- 6. ação → tela certa
  const invoiceAction = answer.getByRole('link', { name: 'Ver fatura' })
  await expect(invoiceAction).toHaveAttribute('href', `/cards/${cardId}/invoices/${invoiceMonth}`)
  await invoiceAction.click()
  await expect(page).toHaveURL(new RegExp(`/cards/${cardId}/invoices/${invoiceMonth}$`), { timeout: NAVIGATION })
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  expect(await readAmount(page.locator('#invoice-amount').locator('xpath=following-sibling::p[1]'))).toBe(screenAmount)

  // ---------------------------------------------------------------- 7. com COPILOT_HISTORY
  await page.goto('/profile/consents')
  await expect(data).toHaveAttribute('aria-checked', 'true')
  await history.click()
  await expect(history).toHaveAttribute('aria-checked', 'true')

  await page.goto('/copilot')
  await ask(page, 'Onde mais gastei este mês?')
  await expect(page.getByRole('article', { name: 'Resposta do Copilot' })).toBeVisible({ timeout: 60_000 })
  await expect(page).toHaveURL(new RegExp(`/copilot/${UUID}$`), { timeout: NAVIGATION })
  const conversationId = page.url().split('/').pop()!
  const historyPanel = page.getByRole('region', { name: 'Histórico' })
  await expect(historyPanel.getByRole('link', { name: /Onde mais gastei este mês\?/ }))
    .toHaveAttribute('href', `/copilot/${conversationId}`)

  // A conversa guardada reabre pelo histórico, marcada como resposta antiga.
  await page.goto('/copilot')
  await historyPanel.getByRole('link', { name: /Onde mais gastei este mês\?/ }).click()
  await expect(page).toHaveURL(new RegExp(`/copilot/${conversationId}$`), { timeout: NAVIGATION })
  await expect(page.getByText(/^Resposta de .+\. Os números podem ter mudado desde então\.$/)).toBeVisible()

  // ---------------------------------------------------------------- 8. revogar COPILOT_DATA_USE
  await page.goto('/profile/consents')
  await data.click()
  await expect(page.getByText('Revogar e apagar o histórico?')).toBeVisible()
  await page.getByRole('button', { name: 'Revogar' }).click()
  await expect(data).toHaveAttribute('aria-checked', 'false')
  await expect(history).toHaveAttribute('aria-checked', 'false')

  await page.goto('/copilot')
  await ask(page, 'Onde mais gastei este mês?')
  await expect(page.getByRole('alert').filter({ hasText: CONSENT_REQUIRED_TEXT })).toBeVisible()
  await expect(page.getByRole('article', { name: 'Resposta do Copilot' })).toHaveCount(0)

  // A revogação apaga o histórico de forma assíncrona (ADR-0038): reconcede os dois e espera o histórico esvaziar.
  await page.goto('/profile/consents')
  await data.click()
  await expect(data).toHaveAttribute('aria-checked', 'true')
  await history.click()
  await expect(history).toHaveAttribute('aria-checked', 'true')
  await page.goto('/copilot')
  await expect(async () => {
    await page.reload()
    await expect(page.getByRole('region', { name: 'Histórico' })).toBeVisible({ timeout: 2_000 })
    await expect(page.getByRole('region', { name: 'Histórico' }).getByRole('link')).toHaveCount(0, { timeout: 2_000 })
  }).toPass({ timeout: 60_000, intervals: [500, 1_000, 2_000] })
  await page.goto(`/copilot/${conversationId}`)
  await expect(page.getByRole('heading', { name: 'Não encontramos esta conversa' })).toBeVisible()

  // Sem rolagem horizontal nas larguras do DS.
  for (const width of [375, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    for (const path of ['/copilot', `/copilot${new URL(invoiceEntryUrl).search}`]) {
      await page.goto(path)
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
      expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth), `${path} em ${width}px`)
        .toBeLessThanOrEqual(0)
    }
  }
})

async function ask(page: Page, question: string) {
  await page.getByLabel('Pergunta para o Copilot').fill(question)
  await page.getByRole('button', { name: 'Enviar pergunta' }).click()
}

/** Espaços não separáveis do Intl viram espaço comum. */
function normalize(text: string): string {
  return text.replace(/[  ]/g, ' ').trim()
}

async function readAmount(locator: Locator): Promise<string> {
  const text = normalize(await locator.innerText())
  const match = /R\$ [\d.]+,\d{2}/.exec(text)
  if (!match) throw new Error(`nenhum valor em reais em "${text}"`)
  return match[0]
}
