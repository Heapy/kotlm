# syntax=docker/dockerfile:1

# ---- build stage: JDK + Kotlin Toolchain ----
# Pin this stage to the builder architecture rather than the target: it emits only
# JVM bytecode, so one build serves both platforms. Otherwise the multi-architecture
# build would run the Kotlin compiler under QEMU and spend most of its time emulating.
FROM --platform=$BUILDPLATFORM bellsoft/liberica-openjdk-debian:25 AS build
# The directory determines the module name, which determines the artifact name below.
WORKDIR /kotlm

# The ./kotlin wrapper provisions the CLI and JDK, which requires curl.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Keep build metadata in a separate cacheable layer because it changes less often than sources.
COPY kotlin kotlin.bat module.yaml libs.versions.toml ./
COPY src ./src
RUN ./kotlin package

# ---- runtime stage: the JRE is the only platform-dependent component ----
FROM bellsoft/liberica-openjre-alpine:25 AS runtime

LABEL org.opencontainers.image.title="kotlm" \
      org.opencontainers.image.description="OpenAI-compatible proxy in front of a Codex subscription" \
      org.opencontainers.image.source="https://github.com/Heapy/kotlm" \
      org.opencontainers.image.licenses="Apache-2.0"

RUN adduser -D -u 10001 kotlm \
 && mkdir -p /var/lib/kotlm \
 && chown kotlm:kotlm /var/lib/kotlm

COPY --from=build /kotlm/build/tasks/_kotlm_executableJarJvm/kotlm-jvm-executable.jar /app/kotlm.jar

USER kotlm
EXPOSE 8080
VOLUME ["/var/lib/kotlm"]

# Check liveness rather than readiness. Missing subscription tokens should surface in
# /health, but should not restart the container repeatedly while its owner logs in.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/live || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/kotlm.jar"]
