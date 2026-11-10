-- Versões do que foi enviado ao modelo em cada chamada (ADR-0039 §11, adendo da Parte C): prompt com o hash
-- (answer-v1@3fa1c2d9) e esquema da saída (answer-v1). Vazio quando não se aplica; nunca conteúdo.
-- A tabela só tem linhas de chamadas da Parte B (sem versão), por isso o default ''.

alter table ai_usage
  add column prompt_version varchar(80) not null default '',
  add column schema_version varchar(40) not null default '';
