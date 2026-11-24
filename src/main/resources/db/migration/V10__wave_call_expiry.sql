-- V10: hasta cuando se puede confirmar ("Enterado") una llamada a una ola. Pasado ese momento la orden ya no vale:
-- el conductor no puede confirmarla y la app deja de insistirle.
alter table wave_calls add column expires_at timestamp(6);

-- Las llamadas que ya existian caducan una hora despues de hacerse, igual que la ventana por defecto
update wave_calls set expires_at = called_at + interval '1 hour';

alter table wave_calls alter column expires_at set not null;
