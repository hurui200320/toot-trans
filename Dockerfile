# Build stage: GraalVM CE with Native Image, the `25` major tag so the image
# follows the JDK 25 updates the toolchain in build.gradle.kts asks for.
# Native images do not cross-compile: this stage builds for the target
# platform (see README "Native image (GraalVM CE)").
FROM ghcr.io/graalvm/native-image-community:25 AS build

# The image sets JAVA_HOME to the GraalVM installation but leaves
# GRAALVM_HOME unset; the GraalVM Gradle plugin looks for it.
ENV GRAALVM_HOME=${JAVA_HOME}

WORKDIR /workspace

# Build inputs only: config.properties is deliberately not copied, so a
# local secrets file can never end up in the image.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src ./src

# Keep the Gradle distribution and dependency cache across rebuilds without
# baking it into an image layer.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain nativeCompile

# Runtime stage: the binary is dynamically linked against the build image's
# glibc, so the runtime glibc must be at least as new (the current build base
# is Oracle Linux, and Debian 13 trixie is newer). Debian slim already
# provides the libraries the binary links against (today: libc and libz); the
# CA roots are embedded in the binary (see README "Native image (GraalVM CE)").
FROM debian:trixie-slim

# `--home-dir /app` without creating anything: a bare system account would
# point $HOME at a directory that does not exist, so an `docker exec` shell
# could not `cd ~`. The shell stays nologin: it blocks su/login tooling,
# while `docker exec <cmd>` runs the command directly and is unaffected.
RUN groupadd --system --gid 10001 toot-trans \
    && useradd --system --uid 10001 --gid toot-trans \
        --no-create-home --home-dir /app --shell /usr/sbin/nologin toot-trans

# WORKDIR is also where the default `./config.properties` is read from.
WORKDIR /app
COPY --from=build /workspace/build/native/nativeCompile/toot-trans ./toot-trans

USER toot-trans
ENTRYPOINT ["/app/toot-trans"]
