# Despliegue de Nopar

Guía corta para poner el backend en un servidor propio (Docker; no hace falta instalar Java ni PostgreSQL) o en Railway (sección «Despliegue en Railway»).

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

## Despliegue en Railway

Alternativa al servidor propio: Railway ejecuta la API y PostgreSQL y da HTTPS sin configurar nada, así que aquí **no se usan** `docker-compose.yml`, Caddy ni el perfil `backup`. Para unos 60 conductores hace falta el plan **Pro**: el volumen del plan Hobby llega a 5 GB y las fotos de los partes lo llenarían en semanas. Pon todo en la región **EU West (Ámsterdam)**.

### Puesta en marcha

1. Proyecto nuevo → *Deploy from GitHub repo* → `nopar-backend`. Railway detecta el `Dockerfile` y construye la imagen.
2. En el mismo proyecto: *Add* → *Database* → *PostgreSQL*. El servicio se llama `Postgres` (si lo renombras, cambia el nombre en las referencias de abajo).
3. Servicio de la API → *Variables*:

| Variable | Valor |
|---|---|
| `DB_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
| `DB_PASSWORD` | `${{Postgres.PGPASSWORD}}` |
| `JWT_SECRET` | `openssl rand -base64 48` |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD`, `SUPER_ADMIN_USERNAME`, `SUPER_ADMIN_PASSWORD` | Solo para el primer arranque; después quítalas |
| `OPENAPI_ENABLED` | `false` |
| `SHOW_SQL` | `false` (sin definir, la API imprime todo el SQL en el log) |
| `RAILWAY_RUN_UID` | `0`: la imagen se ejecuta con un usuario sin privilegios y, sin esto, no puede escribir en el volumen |
| `JAVA_OPTS` | `-Xms128m -Xmx384m -XX:+UseSerialGC -Duser.timezone=Europe/Madrid`: limita la memoria de la JVM, que Railway cobra por uso. Empieza así y vigila *Metrics* |
| `FCM_ENABLED`, `FCM_CREDENTIALS_JSON` | Notificaciones push: el JSON de Firebase en Base64 (ver `.env.example`) |

   La API ya escucha en el `PORT` que Railway inyecta. `CORS_ALLOWED_ORIGINS` déjala sin definir mientras no haya cliente web.
4. Volumen para las fotos: clic derecho en el lienzo del proyecto → *Volume* → conéctalo a la API con ruta de montaje `/data/uploads`. Empieza con 20 GB: se puede ampliar en caliente, pero no reducir.
5. API → *Settings* → *Networking* → *Generate Domain*. Esa URL (`https://…up.railway.app/`) es la que lleva la app de producción (ver «La app Android»).
6. En los ajustes de la API, campo *Healthcheck Path*: `/health`. Railway espera a que responda 200 (por defecto, hasta 300 segundos) antes de dar por bueno el despliegue; si no responde, lo marca como fallido. `/health` es público, no pide token y no consulta la base de datos.
7. Comprueba: `curl https://TU_DOMINIO/health` debe responder `{"status":"UP"}` y el log de la API no debe mostrar errores.

### IP real y límite de intentos de login

Según su documentación, Railway pasa la IP del cliente en `X-Real-IP`. Para que el límite de intentos de login cuente por IP real y no por la del proxy, comprueba qué ve la API:

1. Desde el móvil (con datos, no con el wifi de la estación), haz 5 inicios de sesión con una contraseña equivocada.
2. En el log de la API busca `Inicio de sesión bloqueado`: tras `user-ip:usuario|` va la IP que ve la API. Tiene que ser la del móvil.
3. Si sale una dirección interna de Railway, añade `SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER=X-Real-IP` y `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` con el rango de esa dirección (expresión regular, por ejemplo `100\.64\..*`), vuelve a desplegar y repite la prueba.
4. Comprueba también que un cliente no puede falsear su IP: repite los 5 intentos con `curl -H "X-Real-IP: 9.9.9.9" …`; el log debe seguir mostrando tu IP real, no `9.9.9.9`.

Sin esto, todos los conductores compartirían contador de IP (50 fallos cada 15 minutos).

### Copias de seguridad

- En el servicio de PostgreSQL y en el de la API (fotos): pestaña *Backups* → programa una copia diaria y otra semanal. Comprueba que tu plan las incluye. Se restauran desde la misma pestaña, en el mismo servicio, proyecto y entorno.
- Además, de vez en cuando, una copia de la base de datos fuera de Railway. La URL pública sale de la variable `DATABASE_PUBLIC_URL` del servicio PostgreSQL; la versión de `pg_dump` tiene que ser la del servidor (ajusta `17` si no lo es):

```
docker run --rm -v "${PWD}:/out" postgres:17-alpine pg_dump "URL_PUBLICA" -Fc -f /out/copia-railway.dump
```

  Para restaurar ese fichero, sigue «Restaurar» de la sección de copias de seguridad, apuntando a la base de datos de destino.
- Las fotos solo están en el volumen de Railway y en sus copias; si quieres una copia propia, hay que bajarlas aparte.

### Actualizar

Cada despliegue de la API (un `git push`) provoca un corte de unos segundos, porque un servicio con volumen no puede tener dos despliegues activos a la vez: hazlo fuera de las horas de salida de las olas. Las migraciones se aplican solas al arrancar.

## La app Android

La app de producción apunta a la URL pública y con HTTPS del backend. Se define al compilar, sin tocar el código, con una de estas dos opciones:

- En `local.properties` de la app: `nopar.apiBaseUrlRelease=https://api.miempresa.com/` (en Railway, el dominio generado, con `https://` y la barra final)
- O con la variable de entorno `NOPAR_API_BASE_URL`.

Sin ella, `assembleRelease` y `bundleRelease` se niegan a compilar para no publicar una app que apunte a ninguna parte. La versión debug sigue usando `nopar.apiBaseUrl` (o el emulador por defecto).

## Antes de abrirlo a todo el mundo

- [ ] `OPENAPI_ENABLED=false` y `SHOW_SQL=false`.
- [ ] `JWT_SECRET` y `DB_PASSWORD` propios, y el `.env` fuera del repositorio.
- [ ] Puerto 8080 cerrado hacia fuera (solo 80 y 443). En Railway no aplica: ahí, `SHOW_SQL=false`, `RAILWAY_RUN_UID=0` y la prueba de la IP real.
- [ ] Perfil `backup` activo, `./backups` copiado fuera del servidor y una restauración ensayada.
- [ ] `CORS_ALLOWED_ORIGINS` vacío mientras no haya cliente web.
