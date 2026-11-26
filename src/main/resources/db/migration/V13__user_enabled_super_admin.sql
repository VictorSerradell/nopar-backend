-- V13: cuentas desactivables y super administrador.
--
-- enabled: una cuenta desactivada conserva todo su historial (partes, turnos, avisos, mensajes), pero ya no puede iniciar
-- sesión ni usar la app. Es la forma de "borrar" a un conductor o a un encargado sin perder lo que hizo.
--
-- super_admin: un administrador que, además, puede desactivar y reactivar a los demás usuarios. No es un rol nuevo
-- (los permisos de administrador son los mismos): es una marca, y solo tiene sentido en un administrador.

alter table users add column enabled boolean not null default true;
alter table users add column super_admin boolean not null default false;

alter table users
    add constraint users_super_admin_is_admin check (not super_admin or role = 'ADMIN');
