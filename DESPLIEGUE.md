# Despliegue de Nopar

Guía corta para poner el backend en un servidor propio. Todo funciona con Docker; no hace falta instalar Java ni PostgreSQL.

## Qué necesitas

- Un servidor Linux con Docker y el plugin de Compose (un VPS pequeño basta: son unas decenas de usuarios).
- Un dominio (por ejemplo `api.miempresa.com`) apuntando a la IP del servidor, si quieres HTTPS (lo necesita la app en producción).
- Los puertos 80 y 443 abiertos para el HTTPS. El 8080 de la API no hace falta abrirlo hacia fuera cuando usas HTTPS.

## Primera puesta en marcha

1. Copia el proyecto al servidor (`git clone` o similar) y entra en la carpeta.
2. `cp .env.example .env` y rellena, como mínimo:
   - `DB_PASSWORD`: contraseña de la base de datos.
   - `JWT_SECRET`: `openssl rand -base64 48`.
   - `ADMIN_USERNAME` y `ADMIN_PASSWORD`: el primer administrador (solo se crea si no hay ninguno).
   - `SUPER_ADMIN_USERNAME` y `SUPER_ADMIN_PASSWORD`: el super administrador, que puede desactivar y reactivar cuentas. Si pones el nombre de un administrador que ya existe, se le da el rango sin tocar su contraseña.
   - `NOPAR_DOMAIN`: tu dominio, para el HTTPS.
   - `OPENAPI_ENABLED=false`: oculta la documentación Swagger en producción.
3. Arranca: `docker compose --profile https up --build -d` (sin `--profile https` no se levanta el HTTPS y la API queda en el puerto 8080).
4. Comprueba: `curl https://TU_DOMINIO/api/v1/auth/login` debe responder (un 4xx sin credenciales es buena señal) y `docker compose logs api` no debe mostrar errores.
5. Cuando los administradores estén creados, quita `ADMIN_*` y `SUPER_ADMIN_*` del `.env`.

## Notificaciones push (opcional)

Con `FCM_ENABLED=true` y el JSON de Firebase en `./secrets/firebase-service-account.json` (fuera del repositorio, ver `.env.example`).

## Datos que se conservan

Se guardan en volúmenes de Docker: `db-data` (base de datos), `uploads` (fotos de partes y del chat) y `caddy-data` (certificados). `docker compose down` los conserva; `docker compose down -v` los BORRA.

- Los mensajes del chat y sus fotos se borran solos a los 30 días (`CHAT_RETENTION_DAYS`; `0` los conserva siempre).

## Seguridad de las cuentas

- **Intentos de login:** tras 5 contraseñas equivocadas de un mismo usuario desde una misma IP, ese usuario queda bloqueado 15 minutos en esa IP (la app muestra cuánto esperar); también se frena a quien prueba un mismo usuario desde muchas IP (20 fallos) o muchos usuarios desde una IP (50 fallos). Lo borra un inicio de sesión correcto. Los contadores viven en memoria: reiniciar el backend los borra. `LOGIN_RATE_LIMIT_ENABLED=false` lo apaga (solo para depurar).
- **Auditoría:** altas, desactivaciones, reactivaciones, cambios y restablecimientos de contraseña y ascensos a super administrador quedan anotados con quién lo hizo y sobre quién (`GET /api/v1/admin/audit`, solo administradores, solo lectura). Se conserva siempre y va incluida en la copia de seguridad de la base de datos.

## Copias de seguridad

Con el perfil `backup` se hacen solas: `docker compose --profile backup up -d` (con HTTPS: `docker compose --profile https --profile backup up -d`).

- Al arrancar y después cada 24 horas (`BACKUP_EVERY_HOURS`) se guardan en `./backups` la base de datos (`db-AAAA-MM-DD_HHMM.dump`) y las fotos (`uploads-AAAA-MM-DD_HHMM.tar.gz`). Se borran las de más de 14 días (`BACKUP_KEEP_DAYS`).
- Comprueba que funciona: `docker compose logs backup` debe decir `base de datos: OK` y `fotos: OK`, y deben aparecer los dos ficheros en `./backups`.
- **Copia `./backups` fuera del servidor** (otro equipo, almacenamiento en la nube): si el servidor se pierde, las copias que están en él se pierden también.
- Copia manual puntual de la base de datos (sin redirecciones `>` ni `<`, que en PowerShell estropean los ficheros binarios):
  ```
  docker compose exec db pg_dump -U postgres -d fleet_db -Fc -f /tmp/copia.dump
  docker compose cp db:/tmp/copia.dump ./copia.dump
  ```

### Restaurar

Con la API parada, para no escribir mientras se restaura:

```
docker compose stop api
# base de datos (borra lo que haya y deja lo de la copia)
docker compose cp backups/db-AAAA-MM-DD_HHMM.dump db:/tmp/restaurar.dump
docker compose exec db pg_restore -U postgres -d fleet_db --clean --if-exists --no-owner /tmp/restaurar.dump
# fotos (opcional, si también se perdieron)
docker compose run --rm --no-deps -v ./backups:/backups:ro --entrypoint sh api -c "tar xzf /backups/uploads-AAAA-MM-DD_HHMM.tar.gz -C /data/uploads"
docker compose start api
```

Ensaya la restauración una vez en un servidor de pruebas antes de necesitarla: una copia que nunca se ha restaurado es una suposición.

## Actualizar

```
git pull
docker compose --profile https up --build -d
```

Las migraciones de la base de datos se aplican solas al arrancar.

## La app Android

La app de producción apunta a la URL pública y con HTTPS del backend. Se define al compilar, sin tocar el código, con una de estas dos opciones:

- En `local.properties` de la app: `nopar.apiBaseUrlRelease=https://api.miempresa.com/`
- O con la variable de entorno `NOPAR_API_BASE_URL`.

Sin ella, `assembleRelease` y `bundleRelease` se niegan a compilar para no publicar una app que apunte a ninguna parte. La versión debug sigue usando `nopar.apiBaseUrl` (o el emulador por defecto).

## Antes de abrirlo a todo el mundo

- [ ] `OPENAPI_ENABLED=false` y `SHOW_SQL=false`.
- [ ] `JWT_SECRET` y `DB_PASSWORD` propios, y el `.env` fuera del repositorio.
- [ ] Puerto 8080 cerrado hacia fuera (solo 80 y 443).
- [ ] Perfil `backup` activo, `./backups` copiado fuera del servidor y una restauración ensayada.
- [ ] `CORS_ALLOWED_ORIGINS` vacío mientras no haya cliente web.
