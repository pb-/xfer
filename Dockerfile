FROM clojure:tools-deps-1.11.1.1165 AS builder

RUN mkdir -p /build
WORKDIR /build

# cache deps
COPY deps.edn /build
RUN clojure -P
RUN clojure -P -T:build

COPY build.clj /build
COPY src src/
COPY resources resources/

RUN clojure -T:build uber


# ----------------------------------------------------
FROM gcr.io/distroless/java17-debian11
WORKDIR /
EXPOSE 8080
VOLUME /data
COPY --from=builder /build/target/xfer.jar /
ENTRYPOINT ["java", "-jar", "xfer.jar"]
