# Render has no native Java/JVM runtime (see docs/adr/0016-*.md) -- this
# multi-stage build is what makes "Environment: Docker" on Render work.
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /app
COPY . .
RUN ./gradlew clean bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/setlist-scout.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
