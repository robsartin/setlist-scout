# Render has no native Java/JVM runtime (see docs/adr/0016-*.md) -- this
# multi-stage build is what makes "Environment: Docker" on Render work.
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /app
COPY . .
# Use the image's preinstalled Gradle (pinned to the same 8.14.3 as the
# wrapper) rather than ./gradlew. The wrapper would re-download its own
# distribution on every build -- a redundant, transient failure point
# (a prod deploy flaked on SocketException fetching it; see docs/adr/0016).
# ./gradlew stays the entry point for local/CI, where Gradle isn't preinstalled.
RUN gradle clean bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/setlist-scout.jar app.jar
EXPOSE 8080
# Startup-tuned for Render's throttled single-CPU free tier: C1-only JIT and SerialGC
# cut startup CPU so the app binds its port within Render's port-detection window.
ENTRYPOINT ["java", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-jar", "app.jar"]
