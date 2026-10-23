/**
 * Textos do cadastro de cartão a partir dos códigos estáveis do BFF. Código desconhecido cai numa mensagem neutra:
 * a UI nunca mostra o código cru nem texto vindo do servidor.
 */

function lookup(table: Partial<Record<string, string>>, key: string | null | undefined, fallback: string): string {
  return (key != null ? table[key] : undefined) ?? fallback
}

export type CardField =
  | 'nickname'
  | 'institutionName'
  | 'brand'
  | 'last4'
  | 'limit'
  | 'closingDay'
  | 'dueDay'
  | 'defaultPaymentAccountId'

/** Ordem visual do formulário: o foco vai para o primeiro campo com erro. */
export const CARD_FIELD_ORDER: CardField[] = [
  'nickname', 'institutionName', 'brand', 'last4', 'limit', 'closingDay', 'dueDay', 'defaultPaymentAccountId',
]

const FIELD_ERROR: Record<CardField, Partial<Record<string, string>> & { fallback: string }> = {
  nickname: {
    REQUIRED: 'Dê um apelido ao cartão (ex.: "Aurora Platinum").',
    TOO_LONG: 'Use no máximo 100 caracteres no apelido.',
    INVALID_CHARACTERS: 'O apelido tem caracteres que não podem ser usados. Digite-o de novo.',
    fallback: 'Confira o apelido.',
  },
  institutionName: {
    TOO_LONG: 'Use no máximo 100 caracteres na instituição.',
    INVALID_CHARACTERS: 'A instituição tem caracteres que não podem ser usados. Digite-a de novo.',
    fallback: 'Confira a instituição.',
  },
  brand: { REQUIRED: 'Escolha a bandeira.', fallback: 'Escolha uma bandeira da lista.' },
  last4: {
    REQUIRED: 'Informe os 4 últimos dígitos do cartão.',
    INVALID: 'Use exatamente os 4 últimos dígitos, só números.',
    fallback: 'Use exatamente os 4 últimos dígitos.',
  },
  limit: {
    REQUIRED: 'Informe o limite.',
    INVALID_FORMAT: 'Use só números, com vírgula para os centavos (ex.: 12.000,00).',
    NOT_POSITIVE: 'O limite precisa ser maior que zero.',
    TOO_MANY_DECIMALS: 'Use no máximo 2 casas decimais.',
    TOO_LARGE: 'O limite é alto demais.',
    fallback: 'Confira o limite.',
  },
  closingDay: { REQUIRED: 'Informe o dia de fechamento.', fallback: 'Use um dia de 1 a 31.' },
  dueDay: {
    REQUIRED: 'Informe o dia de vencimento.',
    SAME_AS_CLOSING: 'O vencimento precisa ser num dia diferente do fechamento.',
    CONFLICTS_WITH_CLOSING:
      'Com vencimento depois do fechamento no mesmo mês, o fechamento vai até o dia 27. Confira os dois dias.',
    fallback: 'Use um dia de 1 a 31.',
  },
  defaultPaymentAccountId: {
    INVALID: 'Escolha uma conta da lista.',
    fallback: 'Escolha uma conta ativa ou "Nenhuma".',
  },
}

export const cardFieldErrorMessage = (field: CardField, code: string) =>
  lookup(FIELD_ERROR[field], code, FIELD_ERROR[field].fallback)

const REQUEST_FAILURE: Partial<Record<string, string>> = {
  ACCOUNT_NOT_FOUND: 'A conta de pagamento não está mais disponível. Escolha outra.',
  ACCOUNT_ARCHIVED: 'A conta de pagamento está arquivada. Escolha outra.',
  VALIDATION_FAILED: 'Confira os dados informados e tente de novo.',
  INVALID_REQUEST: 'Não foi possível enviar os dados. Confira os campos e tente de novo.',
  FORBIDDEN: 'Não foi possível concluir a ação. Atualize a página e tente de novo.',
}

export const cardRequestFailureMessage = (code: string) =>
  lookup(REQUEST_FAILURE, code, 'Não foi possível falar com o servidor. Nada foi alterado; tente de novo.')
