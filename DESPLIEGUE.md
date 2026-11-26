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

- Copia de seguridad de la base de datos: `docker compose exec -T db pg_dump -U postgres fleet_db > copia-$(date +%F).sql`
- Restaurar en una base vacía: `docker compose exec -T db psql -U postgres fleet_db < copia.sql`
- Las fotos están en el volumen `uploads` (`docker volume inspect` te dice dónde): inclúyelo en la copia.
- Los mensajes del chat y sus fotos se borran solos a los 30 días (`CHAT_RETENTION_DAYS`; `0` los conserva siempre).

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
- [ ] Copia de seguridad programada (por ejemplo con `cron` y el `pg_dump` de arriba).
- [ ] `CORS_ALLOWED_ORIGINS` vacío mientras no haya cliente web.
