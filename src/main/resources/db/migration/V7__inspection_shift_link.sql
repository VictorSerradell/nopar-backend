-- V7: enlace informativo entre el parte matutino y el turno que el conductor tenia ese dia.
--
-- shift_id es el turno (si lo habia) y matches_assigned_vehicle indica si la furgoneta del parte era la asignada en
-- ese turno cuando se hizo el parte. Son solo informativos: nunca bloquean el parte, y los partes anteriores o los de
-- conductores sin turno quedan en null.
--
-- Si se borra el turno el parte se conserva y pierde el enlace (on delete set null); matches_assigned_vehicle ya no
-- tiene sentido sin turno y la API no lo muestra.

alter table vehicle_inspections add column shift_id bigint;
alter table vehicle_inspections add column matches_assigned_vehicle boolean;

alter table vehicle_inspections
    add constraint fk_vehicle_inspections_shift foreign key (shift_id) references shift_assignments (id) on delete set null;

-- PostgreSQL no indexa por si solo las claves foraneas: borrar un turno busca los partes que lo enlazan
create index idx_vehicle_inspections_shift on vehicle_inspections (shift_id);
