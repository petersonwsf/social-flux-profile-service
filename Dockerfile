# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
# Contexto do build = RAIZ do repositório (docker-compose.yml), para incluir as bibliotecas compartilhadas (eventos e mídia).
WORKDIR /build
COPY libs/socialflux-events/pom.xml libs/socialflux-events/pom.xml
COPY libs/socialflux-events/src libs/socialflux-events/src
RUN mvn -q -B -f libs/socialflux-events/pom.xml -DskipTests install
COPY libs/socialflux-media/pom.xml libs/socialflux-media/pom.xml
COPY libs/socialflux-media/src libs/socialflux-media/src
RUN mvn -q -B -f libs/socialflux-media/pom.xml -DskipTests install
COPY profile_service/pom.xml profile_service/pom.xml
RUN mvn -q -B -f profile_service/pom.xml dependency:go-offline
COPY profile_service/src profile_service/src
RUN mvn -q -B -f profile_service/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runner
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/profile_service/target/profile-service-*.jar app.jar
USER app

EXPOSE 8082
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8082/actuator/health >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
