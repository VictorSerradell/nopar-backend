-- V2: trazabilidad de la revision de una inspeccion por un administrador (quien y cuando).
--
-- Es idempotente a proposito: una base existente pudo recibir estas columnas de Hibernate
-- (ddl-auto=update) antes de pasar a Flyway.

alter table vehicle_inspections add column if not exists reviewed_by bigint;
alter table vehicle_inspections add column if not exists reviewed_at timestamp(6);

do $$
begin
    if not exists (
        select 1
        from pg_constraint c
        join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any (c.conkey)
        where c.conrelid = 'vehicle_inspections'::regclass
          and c.contype = 'f'
          and a.attname = 'reviewed_by'
    ) then
        alter table vehicle_inspections
            add constraint fk_vehicle_inspections_reviewed_by
            foreign key (reviewed_by) references users (id);
    end if;
end
$$;
