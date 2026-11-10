-- Token bucket do rate limit do Copilot (ADR-0039 §10), atômico no Redis.
-- KEYS[1] = shf:copilot:rl:{workspaceId}:{userId}
-- ARGV[1] = capacidade (perguntas), ARGV[2] = intervalo de recarga de 1 pergunta, em ms
-- Retorna {permitido (1/0), espera em ms até a próxima pergunta}.
-- O relógio é o do Redis (TIME): todas as instâncias da API veem o mesmo tempo.
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local interval = tonumber(ARGV[2])

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local state = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

local refilled = math.floor(math.max(0, now - ts) / interval)
if refilled > 0 then
  tokens = math.min(capacity, tokens + refilled)
  ts = ts + refilled * interval
end
if tokens >= capacity then
  ts = now
end

local allowed = 0
local wait = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  wait = interval - (now - ts)
end

redis.call('HSET', key, 'tokens', tokens, 'ts', ts)
redis.call('PEXPIRE', key, capacity * interval * 2)
return {allowed, wait}
