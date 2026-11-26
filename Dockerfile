# syntax=docker/dockerfile:1

# ---------- Etapa 1: compilar el jar ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Las dependencias se descargan en su propia capa: mientras no cambie el pom.xml, Docker la reutiliza y las
# construcciones siguientes solo recompilan el código. Es solo una optimización (si falla, el paso siguiente
# descarga lo que falte), y no se usan cachés de BuildKit porque Railway rechaza el Dockerfile si llevan un id propio.
RUN ./mvnw -B -q dependency:go-offline || true

COPY src/ src/

# Los tests necesitan Docker (Testcontainers) y una base de datos: se ejecutan en el CI, no al construir la imagen.
RUN ./mvnw -B -q -Dmaven.test.skip=true package

# ---------- Etapa 2: imagen final, solo con el JRE ----------
FROM eclipse-temurin:21-jre
RUN groupadd --system nopar \
    && useradd --system --gid nopar --home-dir /app nopar \
    && mkdir -p /app /data/uploads \
    && chown -R nopar:nopar /app /data
WORKDIR /app

COPY --from=build --chown=nopar:nopar /workspace/target/*.jar app.jar

USER nopar

# La zona horaria importa: los partes guardan la hora local (LocalDateTime.now()) y los trabajos programados
# (reseteo nocturno, limpieza de tokens) tienen su propia zona en application.yaml.
# Las fotos van a /data/uploads: monta ahí un disco persistente para que sobrevivan a recrear el contenedor
# (docker compose y Railway lo hacen por su cuenta, por eso no se declara nada aquí).
ENV TZ=Europe/Madrid \
    APPLICATION_STORAGE_LOCATION=/data/uploads \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Europe/Madrid"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
