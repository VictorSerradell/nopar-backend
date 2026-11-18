-- V3: indices para las consultas de los listados.
--
-- Las inspecciones se piden paginadas y siempre de la mas reciente a la mas antigua (inspection_date, id),
-- filtradas por vehiculo, por conductor, por estado o sin filtrar. Cada indice sigue ese orden para que
-- PostgreSQL devuelva la pagina leyendo el indice, sin ordenar toda la tabla.
-- PostgreSQL no indexa por si solo las claves foraneas, por eso vehicle_id y user_id van aqui.
-- Las columnas unicas (username, license_plate, token) ya tienen su indice por la restriccion unique.

create index if not exists idx_vehicle_inspections_vehicle_date
    on vehicle_inspections (vehicle_id, inspection_date desc, id desc);

create index if not exists idx_vehicle_inspections_driver_date
    on vehicle_inspections (user_id, inspection_date desc, id desc);

create index if not exists idx_vehicle_inspections_status_date
    on vehicle_inspections (status, inspection_date desc, id desc);

create index if not exists idx_vehicle_inspections_date
    on vehicle_inspections (inspection_date desc, id desc);

-- Cerrar sesion borra todos los refresh tokens de un usuario
create index if not exists idx_refresh_tokens_user
    on refresh_tokens (user_id);
