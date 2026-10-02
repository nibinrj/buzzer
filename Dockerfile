# One image recipe for every service. The jar is built BEFORE this runs (.\tasks.ps1 images, later CI), so the
# image never contains Maven, sources or tests, only a JRE and the app.
#   docker build --platform linux/amd64 --build-arg SERVICE=quiz-service --build-arg REVISION=<git sha> -t buzzer/quiz-service:<git sha> .
# The build context is the repo root; .dockerignore lets only services/*/target/*.jar through.

# Multi-arch index digest: the same pin resolves to the amd64 image (kind) or the arm64 image (ECS, Phase 6.5).
ARG JRE_IMAGE=eclipse-temurin:21.0.12.1_1-jre-noble@sha256:22138efd69393501fccd8176ae16b01791ed71ff801b28f0359415389b17c766

# --- Stage 1: split the fat jar into Boot's layers --------------------------------------------------------------
# $BUILDPLATFORM is the machine running the build. Unpacking a jar gives the same files on any CPU, so this stage
# always runs natively, even when the target is arm64: no emulation here.
FROM --platform=$BUILDPLATFORM ${JRE_IMAGE} AS extract
ARG SERVICE
WORKDIR /build
# The glob matches only the repackaged jar: Boot's plugin renames the plain one to *.jar.original.
COPY services/${SERVICE}/target/*.jar application.jar
# Boot 4's "tools" jar mode (Boot 2/3's "layertools" is gone). Without --launcher the result is a plain
# application.jar plus lib/*.jar, spread over the four layer directories listed in BOOT-INF/layers.idx.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# --- Stage 2: the runtime image ---------------------------------------------------------------------------------
FROM ${JRE_IMAGE}
ARG SERVICE
ARG REVISION=unknown
# version is set too, or the base image's "24.04" (Ubuntu's) would show up as ours.
LABEL org.opencontainers.image.title="${SERVICE}" \
      org.opencontainers.image.version="${REVISION}" \
      org.opencontainers.image.revision="${REVISION}"

# A numeric uid/gid, so Kubernetes' runAsNonRoot can verify it (it can't resolve a user name). No home directory,
# no login shell: nothing in the app writes to ~, and Tomcat's work directory goes to /tmp.
RUN groupadd --system --gid 10001 buzzer \
 && useradd --system --uid 10001 --gid buzzer --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin buzzer

WORKDIR /app
# Least-changing layer first. A code change rebuilds only the last COPY, so a new image reuses the ~100 MB of
# dependencies already on the node and only the few hundred KB of the application layer is new.
# Files stay owned by root and read-only to uid 10001: the app can't modify itself.
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./

USER 10001:10001

# Exec form: java is PID 1 and receives SIGTERM directly, so Boot's graceful shutdown runs (a shell would swallow it).
#   MaxRAMPercentage=60   heap = 60% of the container memory limit; the rest is metaspace, threads, buffers (K.1 §7)
#   ExitOnOutOfMemoryError  a Java OOM exits the JVM, so the orchestrator restarts it instead of leaving it limping
#   user.timezone=UTC     postgres:16 rejects some JVM default zone names; containers must not depend on the host's
# Per-environment extras (e.g. -XX:ActiveProcessorCount) come in through JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-Duser.timezone=UTC", "-jar", "application.jar"]
