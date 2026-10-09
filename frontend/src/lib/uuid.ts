const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** IDs do backend são UUID: qualquer outra coisa nunca vira caminho de URL nem chega à API. */
export const isUuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value)
