import type { Failure } from '@/lib/bff-client'

/** Campos dos formulários de Objetivos, na ordem do foco do primeiro erro. */
export const GOAL_FIELD_ORDER = [
  'type',
  'name',
  'targetAmount',
  'deadline',
  'plannedMonthly',
  'destinationAccountId',
  'sourceAccountId',
] as const
export type GoalField = (typeof GOAL_FIELD_ORDER)[number]

export const CONTRIBUTION_FIELD_ORDER = ['sourceAccountId', 'destinationAccountId', 'amount', 'occurredOn'] as const
export type ContributionField = (typeof CONTRIBUTION_FIELD_ORDER)[number]

const AMOUNT: Record<string, string> = {
  REQUIRED: 'Informe o valor.',
  INVALID_FORMAT: 'Digite o valor em reais, por exemplo 1.800,00.',
  INVALID: 'Digite o valor em reais, por exemplo 1.800,00.',
  TOO_MANY_DECIMALS: 'Use no máximo 2 casas decimais.',
  NOT_POSITIVE: 'Use um valor maior que zero.',
  MUST_BE_POSITIVE: 'Use um valor maior que zero.',
  TOO_LARGE: 'Esse valor é grande demais.',
  UNSUPPORTED: 'Use valores em reais.',
}

const FIELD: Record<string, Record<string, string>> = {
  type: { REQUIRED: 'Escolha o tipo do objetivo.', INVALID: 'Escolha o tipo do objetivo.' },
  name: {
    REQUIRED: 'Dê um nome ao objetivo.',
    TOO_LONG: 'Use no máximo 60 caracteres.',
    INVALID_CHARACTERS: 'O nome tem caracteres que não podemos usar.',
  },
  targetAmount: AMOUNT,
  plannedMonthly: {
    ...AMOUNT,
    NEGATIVE: 'O aporte não pode ser negativo.',
    NOT_POSITIVE: 'Use zero ou um valor positivo.',
  },
  amount: AMOUNT,
  deadline: {
    PAST: 'Escolha um mês a partir do próximo.',
    INVALID: 'Escolha um mês válido.',
  },
  destinationAccountId: {
    REQUIRED: 'Escolha a conta onde o dinheiro do objetivo fica.',
    SAVINGS_NOT_ALLOWED: 'Contas de reserva (poupança) ficam com a Reserva de emergência.',
    NOT_RESERVE_ACCOUNT: 'Escolha uma conta de reserva (poupança) ativa.',
    NOT_ALLOWED: 'A reserva usa as suas contas de poupança; não escolha uma conta.',
    INVALID: 'Escolha uma conta da lista.',
    ACCOUNT_NOT_FOUND: 'Essa conta não existe mais. Escolha outra.',
    ACCOUNT_ARCHIVED: 'Essa conta está arquivada. Escolha outra.',
    GOAL_DESTINATION_IN_USE: 'Essa conta já guarda outro objetivo. Escolha outra.',
  },
  sourceAccountId: {
    REQUIRED: 'Escolha a conta de onde sai o aporte.',
    SAME_AS_DESTINATION: 'A conta de origem precisa ser diferente da conta do objetivo.',
    RESERVE_ACCOUNT: 'Uma conta de reserva não pode ser a origem do aporte da reserva.',
    INVALID: 'Escolha uma conta da lista.',
    ACCOUNT_NOT_FOUND: 'Essa conta não existe mais. Escolha outra.',
    ACCOUNT_ARCHIVED: 'Essa conta está arquivada. Escolha outra.',
  },
  occurredOn: {
    REQUIRED: 'Informe a data do aporte.',
    INVALID: 'Use uma data válida.',
    FUTURE: 'O aporte não pode ter data futura.',
    BEFORE_TRACKING_START: 'Use uma data a partir da criação do objetivo.',
  },
}

const FALLBACK: Record<string, string> = {
  type: 'Escolha o tipo do objetivo.',
  name: 'Revise o nome.',
  deadline: 'Revise o prazo.',
  destinationAccountId: 'Revise a conta do objetivo.',
  sourceAccountId: 'Revise a conta de origem.',
  occurredOn: 'Revise a data.',
}

export function goalFieldMessage(field: string, code: string): string {
  return FIELD[field]?.[code] ?? FIELD[field]?.INVALID ?? FALLBACK[field] ?? 'Revise este campo.'
}

/** Erros por campo vindos do BFF (`VALIDATION_FAILED`), só para os campos do formulário. */
export function fieldErrors<F extends string>(failure: Failure, fields: readonly F[]): Partial<Record<F, string>> {
  const errors: Partial<Record<F, string>> = {}
  for (const { field, code } of failure.details ?? []) {
    if ((fields as readonly string[]).includes(field)) errors[field as F] ??= goalFieldMessage(field, code)
  }
  return errors
}

/** Falha que não é de um campo (ou o código de conta/conflito sem campo). Nunca o texto do servidor. */
export function goalFailureMessage(code: string): string {
  switch (code) {
    case 'GOAL_NOT_FOUND':
      return 'Este objetivo não existe mais. Volte para a lista de objetivos.'
    case 'GOAL_STATUS_CONFLICT':
      return 'Esta ação não vale para o status atual do objetivo. Atualize a página.'
    case 'GOAL_RESERVE_ALREADY_EXISTS':
      return 'Você já tem uma Reserva de emergência. Abra a existente para ajustar o plano.'
    case 'GOAL_DESTINATION_IN_USE':
      return 'Essa conta já guarda outro objetivo. Escolha outra.'
    case 'ACCOUNT_NOT_FOUND':
    case 'ACCOUNT_ARCHIVED':
      return 'Uma das contas escolhidas não está mais disponível. Escolha outra.'
    case 'CONFLICT':
      return 'O objetivo mudou enquanto você editava. Atualize a página e tente de novo.'
    case 'IDEMPOTENCY_KEY_REUSED':
      return 'Este aporte já foi enviado com outros dados. Revise e envie de novo.'
    default:
      return 'Não conseguimos salvar agora. Seus dados estão seguros. Tente de novo.'
  }
}

/** Falha de leitura (lista, detalhe). */
export function goalLoadMessage(code: string): string {
  return code === 'GOAL_NOT_FOUND'
    ? 'Este objetivo não existe ou não está mais disponível.'
    : 'Não conseguimos carregar seus objetivos agora. Seus dados estão seguros.'
}
