# Production image (SPEC 15). Packages the jar that the Maven build produced:
#
#   ./mvnw verify            (CI; or ./mvnw -DskipTests package)
#   docker build -t ecommerce .
#
# Building and testing stay in Maven, where CI caches them, and building the image needs nothing from the network
# but the base image. Eclipse Temurin on Ubuntu, not Alpine: the WebP natives (webp-imageio) need glibc.
# Configuration comes from environment variables at run time; see docs/operations.md.

FROM eclipse-temurin:21-jre-noble AS layers
WORKDIR /build
COPY target/ecommerce-*.jar app.jar
# Dependencies change less often than the application, so they get layers of their own.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-noble
# A fixed, unprivileged user (numeric, so orchestrators can check runAsNonRoot).
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
USER 10001:10001

ENV SPRING_PROFILES_ACTIVE=prod \
    TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
# Probes: /actuator/health/liveness and /actuator/health/readiness (plain http is allowed for them).
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
