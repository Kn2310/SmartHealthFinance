-- Id opaco da série recorrente (ADR-0032 §2): a URL da API identifica a série por ele, sem a chave do comerciante.
-- Migration somente aditiva: a chave natural (workspace_id, direction, series_key) continua a chave primária. As linhas
-- existentes recebem um id aleatório; a redetecção cria o id só na inserção e nunca o troca.
alter table forecast_recurring_series add column id uuid;

update forecast_recurring_series set id = gen_random_uuid() where id is null;

alter table forecast_recurring_series alter column id set not null;
alter table forecast_recurring_series add constraint uq_forecast_recurring_series_id unique (id);
