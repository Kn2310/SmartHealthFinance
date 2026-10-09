/**
 * Textos de Transações a partir dos códigos estáveis do BFF. Código desconhecido cai numa mensagem neutra: a UI
 * nunca mostra o código cru nem texto vindo do servidor.
 */

function lookup(table: Partial<Record<string, string>>, key: string | null | undefined, fallback: string): string {
  return (key != null ? table[key] : undefined) ?? fallback
}

export type FormField = 'type' | 'adjustmentDirection' | 'accountId' | 'amount' | 'occurredOn' | 'description' | 'status'

/** Ordem visual do formulário: o foco vai para o primeiro campo com erro. */
export const FIELD_ORDER: FormField[] = ['type', 'adjustmentDirection', 'accountId', 'amount', 'occurredOn', 'description', 'status']

const FIELD_ERROR: Record<FormField, Partial<Record<string, string>> & { fallback: string }> = {
  type: { fallback: 'Escolha se é receita, despesa ou ajuste.' },
  adjustmentDirection: { REQUIRED: 'Escolha se o ajuste aumenta ou diminui o saldo.', fallback: 'Escolha a direção do ajuste.' },
  accountId: {
    REQUIRED: 'Escolha a conta.',
    NOT_FOUND: 'Esta conta não está mais disponível. Escolha outra.',
    fallback: 'Escolha uma conta ativa.',
  },
  amount: {
    REQUIRED: 'Informe o valor.',
    INVALID_FORMAT: 'Use só números, com vírgula para os centavos (ex.: 1.234,56).',
    NOT_POSITIVE: 'O valor precisa ser maior que zero. Para saídas, escolha "Despesa".',
    TOO_MANY_DECIMALS: 'Use no máximo 2 casas decimais.',
    TOO_LARGE: 'O valor é alto demais.',
    MISMATCH: 'A moeda não é a da conta.',
    fallback: 'Confira o valor.',
  },
  occurredOn: { REQUIRED: 'Informe a data.', fallback: 'Informe uma data válida.' },
  description: {
    REQUIRED: 'Descreva a transação (ex.: "Salário" ou "Mercado").',
    TOO_LONG: 'Use no máximo 200 caracteres na descrição.',
    INVALID_CHARACTERS: 'A descrição tem caracteres que não podem ser usados. Digite-a de novo.',
    fallback: 'Confira a descrição.',
  },
  status: { fallback: 'Escolha se a transação já aconteceu.' },
}

export const fieldErrorMessage = (field: FormField, code: string) =>
  lookup(FIELD_ERROR[field], code, FIELD_ERROR[field].fallback)

const REQUEST_FAILURE: Partial<Record<string, string>> = {
  TRANSACTION_NOT_FOUND: 'Esta transação não existe ou não está disponível para você.',
  TRANSACTION_STATUS_CONFLICT: 'O status desta transação mudou. Recarregue para ver as ações disponíveis.',
  ACCOUNT_NOT_FOUND: 'A conta não está mais disponível. Escolha outra.',
  ACCOUNT_ARCHIVED: 'A conta está arquivada e não recebe movimentações. Reative-a ou escolha outra.',
  CONFLICT: 'Esta transação foi alterada em outra janela. Recarregue os dados antes de salvar de novo.',
  IDEMPOTENCY_KEY_REUSED:
    'Um envio anterior deste formulário já foi registrado. Confira a lista de transações antes de registrar de novo.',
  OPENING_BALANCE_EXISTS: 'O saldo inicial desta conta já foi lançado. Confira as movimentações da conta.',
  VALIDATION_FAILED: 'Confira os dados informados e tente de novo.',
  INVALID_REQUEST: 'Não foi possível enviar os dados. Confira os campos e tente de novo.',
  FORBIDDEN: 'Não foi possível concluir a ação. Atualize a página e tente de novo.',
}

export const requestFailureMessage = (code: string) =>
  lookup(REQUEST_FAILURE, code, 'Não foi possível falar com o servidor. Nada foi alterado; tente de novo.')

export const plural = (n: number, one: string, many: string) =>
  `${n.toLocaleString('pt-BR')} ${n === 1 ? one : many}`
