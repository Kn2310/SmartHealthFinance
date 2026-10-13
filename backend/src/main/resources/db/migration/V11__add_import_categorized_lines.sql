-- "Categorizamos X de Y transações" (ADR-0013 §4): das linhas importadas, quantas as regras categorizaram.
alter table import_batches
  add column categorized_lines integer not null default 0,
  add constraint ck_import_batches_categorized check (categorized_lines between 0 and imported_lines);
