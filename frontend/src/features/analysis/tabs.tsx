'use client'

import Link from 'next/link'
import { useId, useState } from 'react'
import type {
  AnalysisCategories,
  AnalysisCategory,
  AnalysisEvolution,
  AnalysisIncome,
  AnalysisMerchants,
  AnalysisMeta,
  AnalysisOverview,
  AnalysisSpending,
} from '@/lib/api/types'
import { Card } from '@/components/ui/Card'
import { Money } from '@/components/ui/Money'
import { CumulativeLine } from '@/components/charts/CumulativeLine'
import { HorizontalBars } from '@/components/charts/HorizontalBars'
import { MonthBars } from '@/components/charts/MonthBars'
import { Waterfall, type WaterfallStep } from '@/components/charts/Waterfall'
import { longMonthLabel, monthLabel } from '@/components/charts/geometry'
import { formatShortDate } from '@/lib/format/date'
import { useCategoryDetail } from './api'
import type { TabContext } from './AnalysisView'
import { formatShare } from './format'
import { MetricCard, QuietPeriod, Variation } from './parts'
import { toApiSearch, toPageSearch } from './query'
import styles from './Analysis.module.css'

const NO_CATEGORY = 'Sem categoria'

export function categoryLabel(code: string | null, names: Map<string, string>): string {
  return code === null ? NO_CATEGORY : (names.get(code) ?? code)
}

function partialCaption(meta: AnalysisMeta): string | undefined {
  if (!meta.period.partial) return undefined
  const month = longMonthLabel(meta.period.month).split(' de ')[0]!
  return `*${month.charAt(0).toUpperCase()}${month.slice(1)} vai até o dia ${meta.period.daysElapsed}.`
}

/** "Por que meu dinheiro mudou?", parte 1 (ADR-0015 §5): o waterfall vem pronto e fecha no resultado. */
export function compositionSteps(composition: NonNullable<AnalysisOverview['composition']>,
                                 names: Map<string, string>): WaterfallStep[] {
  return [
    { key: 'inflows', label: 'Entradas (receitas e reembolsos)', amount: composition.inflows, kind: 'inflow' },
    ...composition.outflows.map((outflow, i) => ({
      key: `out-${i}`,
      label: outflow.group === 'OTHERS'
        ? `Demais categorias (${outflow.categories})`
        : categoryLabel(outflow.categoryCode, names),
      amount: outflow.amount,
      kind: 'outflow' as const,
    })),
    { key: 'net', label: 'Resultado do período', amount: composition.net, kind: 'result' },
  ]
}

function categoryBars(lines: AnalysisCategory[], meta: AnalysisMeta, names: Map<string, string>) {
  return lines.map((line) => ({
    key: line.categoryCode ?? 'none',
    label: categoryLabel(line.categoryCode, names),
    amount: line.amount,
    note: (
      <>
        {formatShare(line.share) ? `${formatShare(line.share)} das despesas · ` : ''}
        <Variation variation={line.vsAverage} meta={meta} base="average" tone="spending" />
      </>
    ),
  }))
}

// --- Visão geral ---

export function OverviewTab({ data, context }: { data: AnalysisOverview; context: TabContext }) {
  const { names, query } = context
  const search = toPageSearch(query)
  const savedShare = formatShare(data.savedShare)
  return (
    <>
      {data.state === 'NO_ACTIVITY_IN_PERIOD' ? <QuietPeriod /> : null}
      <div className={styles.metrics}>
        <MetricCard label="Receitas" metric={data.income} meta={data} tone="earning" />
        <MetricCard label="Despesas" metric={data.expense} meta={data} tone="spending"
          hint={data.refunds && data.refunds.amount.amount !== '0.00'
            ? <>Reembolsos no mês: <Money value={data.refunds.amount} /></>
            : undefined} />
        <MetricCard label="Resultado" metric={data.net} meta={data} tone="earning" sign="always"
          hint={savedShare ? `${savedShare} da renda não foi gasta` : undefined} />
      </div>

      <div className={styles.grid}>
        <Card id="analysis-months" title="Receitas e despesas" subtitle="Últimos 6 meses">
          <MonthBars
            title="Receitas e despesas nos últimos 6 meses"
            series={[{ label: 'Receitas', tone: 'income' }, { label: 'Despesas', tone: 'expense' }]}
            data={(data.months ?? []).map((m) => ({ month: m.month, partial: m.partial, values: [m.income, m.expense] }))}
            caption={partialCaption(data)}
          />
          {data.averageExpense ? (
            <p className={styles.footnote}>
              Média de despesas nos meses de comparação: <Money value={data.averageExpense} />.
            </p>
          ) : null}
        </Card>

        <Card id="analysis-why" title="Por que meu dinheiro mudou?" subtitle="Entradas e saídas do período, por categoria">
          {data.composition ? <Waterfall label="Composição do resultado do período" steps={compositionSteps(data.composition, names)} /> : null}
          <p className={styles.footnote}>
            Transferências entre suas contas não contam como gasto. Reembolsos são devolução de despesa, não receita.
          </p>
        </Card>
      </div>

      <div className={styles.grid}>
        <Card id="analysis-top-categories" title="Principais categorias"
          action={<Link className={styles.link} href={search ? `/analysis/categories?${search}` : '/analysis/categories'}>Ver todas</Link>}>
          {data.topCategories && data.topCategories.length > 0
            ? <HorizontalBars label="Principais categorias de despesa" items={categoryBars(data.topCategories, data, names)} />
            : <p className={styles.muted}>Nenhuma despesa no período.</p>}
        </Card>

        <Card id="analysis-changes" title="Principais mudanças"
          subtitle={data.changes ? `O que mais mexeu no resultado (${data.comparison.selected === 'AVERAGE' ? 'vs média' : 'vs mês anterior'})` : undefined}>
          {data.changes && data.changes.items.length > 0 ? (
            <ul className={styles.list}>
              {data.changes.items.slice(0, 5).map((item, i) => (
                <li key={i} className={styles.item}>
                  <span className={styles.itemTitle}>
                    {item.kind === 'INCOME' ? 'Receitas' : item.kind === 'REFUNDS' ? 'Reembolsos' : categoryLabel(item.categoryCode, names)}
                  </span>
                  <span className={styles.itemValue}>
                    <Money value={item.impact} sign="always" />
                  </span>
                  <span className={styles.itemMeta}>
                    <Money value={item.current} /> agora · <Money value={item.base} /> na base
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className={styles.muted}>{data.changes ? 'Nada mudou em relação à base.' : 'Ainda não há base para comparar.'}</p>
          )}
          {data.changes ? (
            <p className={styles.footnote}>
              Variação do resultado: <Money value={data.changes.netDelta} sign="always" /> (de <Money value={data.changes.baseNet} sign="always" /> para{' '}
              <Money value={data.changes.currentNet} sign="always" />).
            </p>
          ) : null}
        </Card>
      </div>
    </>
  )
}

// --- Gastos ---

export function SpendingTab({ data, context }: { data: AnalysisSpending; context: TabContext }) {
  const { names, query } = context
  const search = toPageSearch(query)
  const month = longMonthLabel(data.period.month).split(' de ')[0]!
  const previousMonth = data.comparison.previous ? longMonthLabel(data.comparison.previous.from.slice(0, 7)).split(' de ')[0]! : null
  return (
    <>
      {data.state === 'NO_ACTIVITY_IN_PERIOD' ? <QuietPeriod /> : null}
      <div className={styles.metrics}>
        <MetricCard label="Total gasto" metric={data.total} meta={data} tone="spending"
          hint={data.refunds ? <>Reembolsos no mês: <Money value={data.refunds} /></> : undefined} />
        <div className={styles.metric}>
          <span className={styles.metricLabel}>Média por dia</span>
          <span className={styles.metricValue}>
            <Money value={data.dailyAverage} />
          </span>
          {data.previousDailyAverage && previousMonth ? (
            <span className={styles.metricHint}>
              {previousMonth}: <Money value={data.previousDailyAverage} />
            </span>
          ) : null}
        </div>
      </div>

      <div className={styles.grid}>
        <Card id="analysis-cumulative" title="Gasto acumulado no mês"
          subtitle={previousMonth ? `${month} comparado a ${previousMonth}` : month}>
          <CumulativeLine title={`Gasto acumulado em ${month}`} current={data.cumulative} previous={data.previousCumulative}
            currentLabel={month} previousLabel={previousMonth ?? 'Mês anterior'} />
        </Card>

        <Card id="analysis-largest" title="Maiores gastos" subtitle="Transações individuais">
          {data.largestExpenses.length > 0 ? (
            <ul className={styles.list}>
              {data.largestExpenses.map((expense) => (
                <li key={expense.transactionId} className={styles.item}>
                  <Link className={styles.itemTitle} href={`/transactions/${expense.transactionId}`}>
                    {expense.merchantName ?? expense.description}
                  </Link>
                  <span className={styles.itemValue}>
                    <Money value={expense.amount} />
                  </span>
                  <span className={styles.itemMeta}>
                    {categoryLabel(expense.categoryCode, names)} · {formatShortDate(expense.occurredOn)}
                  </span>
                </li>
              ))}
            </ul>
          ) : <p className={styles.muted}>Nenhuma despesa no período.</p>}
        </Card>
      </div>

      <Card id="analysis-spending-categories" title="Gastos por categoria"
        action={<Link className={styles.link} href={search ? `/analysis/categories?${search}` : '/analysis/categories'}>Ver categorias</Link>}>
        {data.categories.length > 0
          ? <HorizontalBars label="Gastos por categoria" items={categoryBars(data.categories, data, names)} />
          : <p className={styles.muted}>Nenhuma despesa no período.</p>}
      </Card>
    </>
  )
}

// --- Receitas ---

export function IncomeTab({ data, context }: { data: AnalysisIncome; context: TabContext }) {
  const { names } = context
  return (
    <>
      {data.state === 'NO_ACTIVITY_IN_PERIOD' ? <QuietPeriod /> : null}
      <div className={styles.metrics}>
        <MetricCard label="Receitas no mês" metric={data.total} meta={data} tone="earning" />
        <div className={styles.metric}>
          <span className={styles.metricLabel}>Média mensal</span>
          <span className={styles.metricValue}>
            <Money value={data.monthlyAverage} />
          </span>
          <span className={styles.metricHint}>Nos meses de comparação, no mesmo recorte de dias.</span>
        </div>
      </div>

      <div className={styles.grid}>
        <Card id="analysis-sources" title="Fontes de receita" subtitle={longMonthLabel(data.period.month)}>
          {data.sources.length > 0 ? (
            <ul className={styles.list}>
              {data.sources.map((source, i) => (
                <li key={source.merchantKey ?? `category-${i}`} className={styles.item}>
                  <span className={styles.itemTitle}>{source.merchantName ?? categoryLabel(source.categoryCode, names)}</span>
                  <span className={styles.itemValue}>
                    <Money value={source.amount} sign="inflow" />
                  </span>
                  <span className={styles.itemMeta}>
                    {source.recurrence === 'RECURRING'
                      ? `Recorrente${source.typicalDay ? ` · costuma cair no dia ${String(source.typicalDay).padStart(2, '0')}` : ''}`
                      : `Pontual · ${formatShortDate(source.lastReceivedOn)}`}
                    {formatShare(source.share) ? ` · ${formatShare(source.share)}` : ''}
                  </span>
                </li>
              ))}
            </ul>
          ) : <p className={styles.muted}>Nenhuma receita no período.</p>}
        </Card>

        <Card id="analysis-income-months" title="Receitas por mês" subtitle="Últimos 6 meses">
          <MonthBars title="Receitas nos últimos 6 meses" series={[{ label: 'Receitas', tone: 'income' }]}
            data={data.months.map((m) => ({ month: m.month, partial: m.partial, values: [m.income] }))}
            caption={partialCaption(data)} />
        </Card>
      </div>
    </>
  )
}

// --- Categorias ---

export function CategoriesTab({ data, context }: { data: AnalysisCategories; context: TabContext }) {
  const { names, query, params, setExtra } = context
  const selectable = data.categories.filter((line) => line.categoryCode !== null)
  const requested = params.get('category')
  const selected = selectable.find((line) => line.categoryCode === requested)?.categoryCode
    ?? selectable[0]?.categoryCode ?? null

  return (
    <div className={styles.grid}>
      <Card id="analysis-categories" title="Todas as categorias" subtitle="Despesas do mês, maior primeiro">
        {data.categories.length > 0 ? (
          <ul className={styles.list}>
            {data.categories.map((line) => {
              const label = categoryLabel(line.categoryCode, names)
              const current = line.categoryCode !== null && line.categoryCode === selected
              return (
                <li key={line.categoryCode ?? 'none'} className={`${styles.item} ${current ? styles.itemSelected : ''}`}>
                  {line.categoryCode ? (
                    <button type="button" className={`${styles.itemTitle} ${styles.link}`} aria-pressed={current}
                      onClick={() => setExtra({ category: line.categoryCode ?? undefined })}>
                      {label}
                    </button>
                  ) : (
                    <Link className={`${styles.itemTitle} ${styles.link}`} href="/transactions?category=none">
                      {label}
                    </Link>
                  )}
                  <span className={styles.itemValue}>
                    <Money value={line.amount} />
                  </span>
                  <span className={styles.itemMeta}>
                    {formatShare(line.share) ? `${formatShare(line.share)} das despesas · ` : ''}
                    <Variation variation={line.vsAverage} meta={data} base="average" tone="spending" />
                  </span>
                </li>
              )
            })}
          </ul>
        ) : <p className={styles.muted}>Nenhuma despesa no período.</p>}
      </Card>
      {selected ? <CategoryDetailPanel code={selected} context={context} search={toApiSearch(query)} /> : null}
    </div>
  )
}

function CategoryDetailPanel({ code, context, search }: { code: string; context: TabContext; search: string }) {
  const detail = useCategoryDetail(code, search)
  const label = categoryLabel(code, context.names)
  if (detail.status === 'loading') {
    return (
      <Card id="analysis-category-detail" title={label} subtitle="Carregando…">
        <span className="sr-only" role="status">Carregando a categoria…</span>
      </Card>
    )
  }
  if (detail.status === 'error' || !detail.data) {
    return (
      <Card id="analysis-category-detail" title={label}>
        <p role="alert" className={styles.muted}>Não conseguimos carregar esta categoria agora.</p>
      </Card>
    )
  }
  const data = detail.data
  return (
    <Card id="analysis-category-detail" title={label} subtitle="Categoria selecionada">
      <dl className={styles.list}>
        <div className={styles.item}>
          <dt className={styles.itemMeta}>No mês</dt>
          <dd className={styles.itemValue}><Money value={data.amount?.amount ?? null} /></dd>
          <dd className={styles.itemMeta}><Variation variation={data.amount?.vsAverage ?? null} meta={data} base="average" tone="spending" /></dd>
        </div>
        <div className={styles.item}>
          <dt className={styles.itemMeta}>Compras</dt>
          <dd className={styles.itemValue}>{data.transactionCount}</dd>
        </div>
        <div className={styles.item}>
          <dt className={styles.itemMeta}>Ticket médio</dt>
          <dd className={styles.itemValue}><Money value={data.averageTicket} /></dd>
        </div>
      </dl>
      <MonthBars title={`${label} nos últimos 6 meses`} series={[{ label, tone: 'expense' }]}
        data={data.history.map((h) => ({ month: h.month, partial: h.partial, values: [h.amount] }))} />
      <h3 className={styles.metricLabel}>Onde mais gastou</h3>
      {data.merchants.length > 0 ? (
        <ul className={styles.list}>
          {data.merchants.map((merchant, i) => (
            <li key={merchant.merchantKey ?? `unknown-${i}`} className={styles.item}>
              <span className={styles.itemTitle}>{merchant.merchantName ?? 'Comerciante não identificado'}</span>
              <span className={styles.itemValue}><Money value={merchant.amount} /></span>
              <span className={styles.itemMeta}>{merchant.transactionCount} {merchant.transactionCount === 1 ? 'compra' : 'compras'}</span>
            </li>
          ))}
        </ul>
      ) : <p className={styles.muted}>Sem compras nesta categoria no mês.</p>}
      <Link className={styles.link}
        href={`/transactions?category=${encodeURIComponent(code)}&period=CUSTOM&from=${data.period.from}&to=${data.period.to}`}>
        Ver transações
      </Link>
    </Card>
  )
}

// --- Comerciantes ---

export function MerchantsTab({ data, context }: { data: AnalysisMerchants; context: TabContext }) {
  const { names, setExtra } = context
  const id = useId()
  const [search, setSearch] = useState('')
  const term = search.trim().toLocaleLowerCase('pt-BR')
  const rows = term ? data.merchants.filter((m) => m.merchantName.toLocaleLowerCase('pt-BR').includes(term)) : data.merchants

  return (
    <Card id="analysis-merchants" title="Comerciantes" subtitle={longMonthLabel(data.period.month)}>
      <div className={styles.toolbar}>
        <label className={styles.field} htmlFor={`${id}-search`}>
          Buscar comerciante
          <input id={`${id}-search`} className={styles.input} type="search" value={search}
            onChange={(event) => setSearch(event.target.value)} />
        </label>
        <div role="group" aria-label="Ordenar" className={styles.segmented}>
          <button type="button" className={styles.segment} aria-pressed={data.sort === 'AMOUNT'} onClick={() => setExtra({ sort: undefined })}>
            Por valor
          </button>
          <button type="button" className={styles.segment} aria-pressed={data.sort === 'COUNT'} onClick={() => setExtra({ sort: 'COUNT' })}>
            Por frequência
          </button>
        </div>
      </div>
      {rows.length > 0 ? (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <caption className="sr-only">Comerciantes do mês, {data.sort === 'COUNT' ? 'por frequência' : 'por valor'}</caption>
            <thead>
              <tr>
                <th scope="col">#</th>
                <th scope="col">Comerciante</th>
                <th scope="col">Categoria</th>
                <th scope="col">Compras</th>
                <th scope="col">Total</th>
                <th scope="col">vs média</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((merchant, i) => (
                <tr key={merchant.merchantKey}>
                  <td>{i + 1}</td>
                  <th scope="row">{merchant.merchantName}</th>
                  <td>{categoryLabel(merchant.categoryCode, names)}</td>
                  <td>{merchant.transactionCount}</td>
                  <td><Money value={merchant.amount} /></td>
                  <td><Variation variation={merchant.vsAverage} meta={data} base="average" tone="spending" /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : <p className={styles.muted}>{term ? 'Nenhum comerciante com esse nome.' : 'Nenhuma compra no período.'}</p>}
      {data.unidentified && data.unidentified.amount !== '0.00' ? (
        <p className={styles.footnote}>
          Despesas sem comerciante identificado (fora do ranking): <Money value={data.unidentified} />.
        </p>
      ) : null}
    </Card>
  )
}

// --- Evolução ---

export function EvolutionTab({ data, context }: { data: AnalysisEvolution; context: TabContext }) {
  const { params, setExtra } = context
  const three = params.get('months') === '3'
  return (
    <Card id="analysis-evolution" title="Evolução mensal" subtitle="Receitas, despesas e resultado"
      action={
        <div role="group" aria-label="Meses" className={styles.segmented}>
          <button type="button" className={styles.segment} aria-pressed={!three} onClick={() => setExtra({ months: undefined })}>6 meses</button>
          <button type="button" className={styles.segment} aria-pressed={three} onClick={() => setExtra({ months: '3' })}>3 meses</button>
        </div>
      }>
      <MonthBars title="Receitas e despesas mês a mês" series={[{ label: 'Receitas', tone: 'income' }, { label: 'Despesas', tone: 'expense' }]}
        data={data.months.map((m) => ({ month: m.month, partial: m.partial, values: [m.income, m.expense] }))}
        caption={partialCaption(data)} />
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <caption className="sr-only">Mês a mês</caption>
          <thead>
            <tr>
              <th scope="col">Mês</th>
              <th scope="col">Receitas</th>
              <th scope="col">Despesas</th>
              <th scope="col">Reembolsos</th>
              <th scope="col">Resultado</th>
            </tr>
          </thead>
          <tbody>
            {data.months.map((m) => (
              <tr key={m.month}>
                <th scope="row">{monthLabel(m.month, true)}{m.partial ? ' (parcial)' : ''}</th>
                <td><Money value={m.income} /></td>
                <td><Money value={m.expense} /></td>
                <td><Money value={m.refunds} /></td>
                <td><Money value={m.net} sign="always" /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  )
}
