# syntax=docker/dockerfile:1
#
# STACKIT Connectors bundle - a self-contained Camunda Connector Runtime.
#
# The build context is the REPOSITORY ROOT, not bundle/: the bundle is the last module of a
# multi-module reactor and needs its siblings' sources to build. Build it with:
#
#   docker build -f bundle/Dockerfile -t envite/stackit-connectors:dev .
#
# (run from the repository root; the trailing "." is the context). A root .dockerignore keeps
# target/, .git/ and local credential files out of that context.

# ---------------------------------------------------------------------------------------------
# Build stage
# ---------------------------------------------------------------------------------------------
# A JDK is required, not a JRE: the element-template-generator-maven-plugin reads annotations and
# Javadoc through javac, which is why .mvn/jvm.config carries --add-exports jdk.compiler/... flags.
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

# The Maven wrapper is the only supported way to build this repository (the template generator
# declares requiredMavenVersion 3.10.0, and the root pom's enforcer fails an older Maven). .mvn/
# carries both the wrapper properties and jvm.config, so it must be copied before any mvnw call.
COPY .mvn/ .mvn/
COPY mvnw ./
COPY pom.xml ./

# Poms first, sources second: the dependency download below is by far the slowest layer, and it
# only has to be redone when a pom changes. Add a line here when you add a module to the reactor -
# a missing pom makes "mvnw -pl bundle -am" fail to load the reactor.
COPY core/pom.xml core/
COPY auth/pom.xml auth/
COPY connectors/pom.xml connectors/
COPY connectors/object-storage/pom.xml connectors/object-storage/
COPY bundle/pom.xml bundle/

# Pre-populate the local repository. -pl bundle -am scopes resolution to the bundle and the modules
# it depends on; inter-module SNAPSHOT dependencies are satisfied from the reactor rather than
# downloaded. The !autoFormat profile is disabled on purpose: an image build must not rewrite the
# sources it was handed (Spotless would otherwise reformat them and insert license headers).
RUN --mount=type=cache,target=/root/.m2/repository \
    ./mvnw -B -ntp -P'!autoFormat' -DskipTests dependency:go-offline

# Sources. .dockerignore keeps target/ and .git/ out, so this stays small and cheap.
COPY . .

# Tests are not executed here. They are the CI gate (./mvnw -B verify), and the integration tests
# need a Docker daemon that a build does not have.
#
# -DskipTests, NOT -Dmaven.test.skip=true: the latter also skips test *compilation*, which stops
# maven-jar-plugin from attaching core's test-jar - and connectors/object-storage declares a
# test-scoped dependency on `stackit-connector-core:jar:tests`. The reactor then fails to resolve
# it, before a single class is compiled. Test compilation is the price of that shared test
# fixture; the test-scoped dependencies live only in this build stage and never reach the runtime
# image.
RUN --mount=type=cache,target=/root/.m2/repository \
    ./mvnw -B -ntp -P'!autoFormat' -DskipTests -pl bundle -am package \
 && cp bundle/target/*-with-dependencies.jar /workspace/stackit-connectors.jar

# ---------------------------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------------------------
# eclipse-temurin:21-jre rather than :21-jre-alpine or a distroless image:
#
#  * glibc, not musl. The Camunda client's gRPC stack pulls io.netty:netty-tcnative-boringssl-static
#    with the linux-x86_64/linux-aarch_64 classifiers, which are glibc builds. On musl they fail to
#    load and Netty silently falls back to the JDK TLS provider - a performance and diagnosability
#    regression that only shows up under load. Camunda's own connector runtime image is built on a
#    glibc JRE base for the same reason.
#  * It ships curl, so HEALTHCHECK below is a one-liner. A distroless image has no shell and no
#    curl, which would force a Java-based health-check command (the approach Camunda has to take)
#    and a second JVM start per probe.
#
# The trade-off is image size - the base alone is ~330 MB uncompressed, where an alpine JRE base is
# roughly half that - and a larger OS package surface than distroless. Pin the digest here if your
# supply-chain policy requires it; Dependabot keeps the tag itself current.
FROM eclipse-temurin:21-jre

# Unprivileged user. UID/GID 1001 matches camunda/connectors-bundle, so a bind-mounted volume
# written by one image stays readable by the other.
RUN groupadd --gid 1001 stackit \
 && useradd --uid 1001 --gid 1001 --no-create-home --shell /usr/sbin/nologin stackit \
 && mkdir -p /opt/app \
 && chown -R 1001:1001 /opt/app

COPY --from=build --chown=1001:1001 /workspace/stackit-connectors.jar /opt/app/stackit-connectors.jar

# Writable scratch space for heap dumps and the OAuth credentials cache. Declared as a volume so
# the layer stays read-only even when the container writes there.
VOLUME /tmp

USER 1001:1001

# Matches server.port in application.yaml and the port camunda/connectors-bundle listens on.
EXPOSE 8080

# The Camunda client caches OAuth tokens on disk; the default location is not writable for an
# unprivileged user. Same value camunda/connectors-bundle uses.
ENV CAMUNDA_CLIENT_AUTH_CREDENTIALS_CACHE_PATH=/tmp/connectors

# readiness, not liveness: it reports DOWN until the Orchestration Cluster is actually reachable,
# which is what "ready to take connector jobs" means. start-period covers JVM start plus the first
# topology request. See application.yaml for how the group is composed.
# exec form, so no shell is spawned per probe. `curl -f` exits non-zero on the 503 the readiness
# group returns while the cluster is unreachable, which is exactly the signal wanted here.
HEALTHCHECK --interval=30s --timeout=5s --retries=5 --start-period=45s \
  CMD ["curl", "-fsS", "-o", "/dev/null", "http://localhost:8080/actuator/health/readiness"]

# exec form, so the JVM is PID 1 and receives SIGTERM directly for a graceful shutdown.
#
#  * MaxRAMPercentage makes the heap follow the container memory limit instead of the host's RAM.
#  * ExitOnOutOfMemoryError turns an OOM into a container restart rather than a half-dead runtime.
#  * --add-opens java.base/java.util.concurrent is needed for Micrometer's thread-pool metrics on
#    the virtual-thread job executor the runtime uses by default
#    (camunda.connector.virtual-threads.enabled).
#
# Extra JVM options go into JAVA_TOOL_OPTIONS, which the JVM applies in addition to these. Anything
# appended as a container argument is passed to Spring Boot as an application argument, so
# `docker run envite/stackit-connectors:dev --camunda.client.mode=saas` works.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/tmp", \
  "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED", \
  "-jar", "/opt/app/stackit-connectors.jar"]
