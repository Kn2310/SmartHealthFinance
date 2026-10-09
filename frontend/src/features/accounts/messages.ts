/**
 * Textos de Accounts a partir dos códigos estáveis do BFF. Código desconhecido cai numa mensagem neutra: a UI
 * nunca mostra o código cru nem texto vindo do servidor.
 */

function lookup(table: Partial<Record<string, string>>, key: string | null | undefined, fallback: string): string {
  return (key != null ? table[key] : undefined) ?? fallback
}

export type AccountField = 'name' | 'institutionName' | 'type' | 'includedInTotal'

/** Ordem visual do formulário: o foco vai para o primeiro campo com erro. */
export const FIELD_ORDER: AccountField[] = ['name', 'institutionName', 'type', 'includedInTotal']

const FIELD_ERROR: Record<AccountField, Partial<Record<string, string>> & { fallback: string }> = {
  name: {
    REQUIRED: 'Informe o nome da conta.',
    TOO_LONG: 'Use no máximo 100 caracteres no nome.',
    INVALID_CHARACTERS: 'O nome tem caracteres que não podem ser usados. Digite-o de novo.',
    fallback: 'Confira o nome da conta.',
  },
  institutionName: {
    TOO_LONG: 'Use no máximo 100 caracteres na instituição.',
    INVALID_CHARACTERS: 'A instituição tem caracteres que não podem ser usados. Digite-a de novo.',
    fallback: 'Confira o nome da instituição.',
  },
  type: { fallback: 'Escolha o tipo da conta.' },
  includedInTotal: { fallback: 'Escolha se a conta entra no saldo total.' },
}

export const fieldErrorMessage = (field: AccountField, code: string) =>
  lookup(FIELD_ERROR[field], code, FIELD_ERROR[field].fallback)

const REQUEST_FAILURE: Partial<Record<string, string>> = {
  ACCOUNT_NOT_FOUND: 'Esta conta não existe ou não está disponível para você.',
  ACCOUNT_ARCHIVED: 'Esta conta está arquivada e não pode ser alterada. Reative-a para editar.',
  CONFLICT: 'Esta conta foi alterada em outra janela. Recarregue os dados antes de salvar de novo.',
  VALIDATION_FAILED: 'Confira os dados informados e tente de novo.',
  INVALID_REQUEST: 'Não foi possível enviar os dados. Confira os campos e tente de novo.',
  FORBIDDEN: 'Não foi possível concluir a ação. Atualize a página e tente de novo.',
}

export const requestFailureMessage = (code: string) =>
  lookup(REQUEST_FAILURE, code, 'Não foi possível falar com o servidor. Seus dados continuam seguros; tente de novo.')
