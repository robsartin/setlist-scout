# Render has no native Java/JVM runtime (see docs/adr/0016-*.md) -- this
# multi-stage build is what makes "Environment: Docker" on Render work.
#
# Gradle 8.14.3 (preinstalled in the gradle:*-jdk21 image below) can't launch on JDK 25 --
# same constraint as local/CI, see build.gradle.kts -- so this stage borrows a JDK 25 install
# from the official Temurin image (same Ubuntu Noble base as gradle:8.14.3-jdk21, so the
# binaries are glibc-compatible) purely as the toolchain JDK 25 forks into to compile/test.
FROM eclipse-temurin:25-jdk-noble AS jdk25

FROM gradle:8.14.3-jdk21 AS build
# /opt/java is also one of Gradle's hardcoded Linux JDK scan roots, but that's not what makes
# this JDK discoverable -- don't rely on it as the mechanism, it's just a tidy, conventional
# path. Gradle discovers this JDK 25 toolchain via org.gradle.java.installations.fromEnv=
# JAVA_HOME_25_X64 (see gradle.properties), which explicitly points Gradle at this env var
# rather than depending on a scan-root coincidence and without needing a foojay resolver plugin.
COPY --from=jdk25 /opt/java/openjdk /opt/java/jdk-25
ENV JAVA_HOME_25_X64=/opt/java/jdk-25
WORKDIR /app
COPY . .
# Use the image's preinstalled Gradle (pinned to the same 8.14.3 as the
# wrapper) rather than ./gradlew. The wrapper would re-download its own
# distribution on every build -- a redundant, transient failure point
# (a prod deploy flaked on SocketException fetching it; see docs/adr/0016).
# ./gradlew stays the entry point for local/CI, where Gradle isn't preinstalled.
RUN gradle clean bootJar --no-daemon

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/setlist-scout.jar app.jar
EXPOSE 8080
# Startup-tuned for Render's throttled single-CPU free tier: C1-only JIT and SerialGC
# cut startup CPU so the app binds its port within Render's port-detection window.
# Compact Object Headers (JEP 519, product flag as of JDK 25 -- no longer needs
# -XX:+UnlockExperimentalVMOptions the way it did on JDK 24) shrinks the 8/12-byte object
# header to 8 bytes, ~10-22% less heap, which matters on the 512MB free tier (see #43).
ENTRYPOINT ["java", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-XX:+UseCompactObjectHeaders", "-jar", "app.jar"]
