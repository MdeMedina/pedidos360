# ============================================================================
# Dockerfile generico para cualquier microservicio de Pedidos360.
# Se construye desde la raiz del monorepo:
#     docker build --build-arg SERVICE=ms-pedidos360-orders -t pedidos360/orders .
#
# Multi-stage: la imagen final NO lleva Maven ni el codigo fuente, solo el JRE y
# el jar. Pasa de ~800 MB a ~250 MB y reduce la superficie de ataque.
# ============================================================================

# ---------- etapa 1: compilacion ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Primero solo los pom.xml: si no cambian, Docker reutiliza la capa de dependencias
# y no vuelve a descargar medio Maven Central en cada build.
COPY pom.xml .
COPY pedidos360-common/pom.xml pedidos360-common/
COPY ms-pedidos360-bff/pom.xml ms-pedidos360-bff/
COPY ms-pedidos360-orders/pom.xml ms-pedidos360-orders/
COPY ms-pedidos360-catalog/pom.xml ms-pedidos360-catalog/
COPY ms-pedidos360-notify/pom.xml ms-pedidos360-notify/
COPY ms-pedidos360-report/pom.xml ms-pedidos360-report/
COPY ms-pedidos360-audit/pom.xml ms-pedidos360-audit/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY pedidos360-common pedidos360-common
COPY ms-pedidos360-bff ms-pedidos360-bff
COPY ms-pedidos360-orders ms-pedidos360-orders
COPY ms-pedidos360-catalog ms-pedidos360-catalog
COPY ms-pedidos360-notify ms-pedidos360-notify
COPY ms-pedidos360-report ms-pedidos360-report
COPY ms-pedidos360-audit ms-pedidos360-audit

ARG SERVICE
RUN mvn -B -q -pl "${SERVICE}" -am package -DskipTests \
    && cp "${SERVICE}/target/${SERVICE}-1.0.0.jar" /workspace/app.jar

# ---------- etapa 2: ejecucion ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Usuario sin privilegios: si alguien consigue ejecucion remota dentro del
# contenedor, no es root.
RUN addgroup -S pedidos && adduser -S pedidos -G pedidos
COPY --from=build /workspace/app.jar app.jar
RUN chown -R pedidos:pedidos /app
USER pedidos

EXPOSE 8080
# Todos los servicios escuchan en 8080 DENTRO del contenedor; el puerto propio
# de cada uno (8081, 8082, ...) solo aplica cuando se ejecutan como jar suelto.
# Asi el EXPOSE, el healthcheck y las URLs entre contenedores son uniformes.
ENV SERVER_PORT=8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
