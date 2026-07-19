FROM gradle:9.0.0-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :sidecar:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 observability
COPY --from=build /workspace/sidecar/build/install/sidecar /app
RUN mkdir -p /var/lib/observability && chown -R observability:observability /app /var/lib/observability
USER 10001
EXPOSE 8080
VOLUME ["/var/lib/observability"]
ENV SIDECAR_HOST=0.0.0.0
ENTRYPOINT ["/app/bin/sidecar"]
