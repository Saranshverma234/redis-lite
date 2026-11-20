# Use the same Java major version your pom.xml compiles for (build with: --build-arg JAVA_VERSION=21).
ARG JAVA_VERSION=17

# ---------- build stage: compiles the code and produces the jar ----------
FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Tests run in CI, so skip them here to keep the image build fast.
RUN mvn -B -q -DskipTests package

# ---------- runtime stage: a JRE only, no Maven, no source ----------
FROM eclipse-temurin:${JAVA_VERSION}-jre

# Run as a normal user, not root. /data is where snapshots live; it should be a mounted volume.
RUN useradd --system --create-home --uid 10001 kv \
    && mkdir /data \
    && chown kv /data
USER kv
WORKDIR /app

COPY --from=build /app/target/redis-lite.jar /app/redis-lite.jar

VOLUME /data
EXPOSE 6380

# Exec form, so java is PID 1 and receives SIGTERM from `docker stop`:
# that triggers the shutdown hook, which writes the final snapshot.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/redis-lite.jar"]

# Inside a container the server must listen on all interfaces to be reachable through a published port.
# There is no authentication, so only publish the port on a trusted network.
CMD ["--bind=0.0.0.0", "--snapshot-file=/data/dump.snapshot"]
