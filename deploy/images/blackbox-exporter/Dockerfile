FROM --platform=$BUILDPLATFORM golang:1.26.6-bookworm@sha256:116d58cbd88c1297624acc6e967a060012422bacf9930927e23fb719189c6f36 AS source

ARG BLACKBOX_VERSION=0.28.0
ARG BLACKBOX_COMMIT=5a059bee8d8ffa4e75947c5055fb0abeefc582e6
ARG BLACKBOX_SOURCE_SHA256=12b6eb9b307ebc5e55eafe6eb503816756fa0c344854897fb5aeb687544999f9
ARG BLACKBOX_GO_MOD_SHA256=be64f2317c4fb626bc2c09535ec6a0275782343e94bee7e9b011b32c57799a0a
ARG BLACKBOX_GO_SUM_SHA256=6eb87c85d0f977014965f300a76f4c8faa202a37a14e747d71fc9b0aa82b39b9

ENV CGO_ENABLED=0 \
    GOTOOLCHAIN=local
WORKDIR /src

RUN curl --fail --location --proto '=https' --tlsv1.2 \
      "https://github.com/prometheus/blackbox_exporter/archive/${BLACKBOX_COMMIT}.tar.gz" \
      --output /tmp/blackbox-exporter.tar.gz \
    && printf '%s  %s\n' "${BLACKBOX_SOURCE_SHA256}" /tmp/blackbox-exporter.tar.gz | sha256sum --check --strict - \
    && tar --extract --gzip --file /tmp/blackbox-exporter.tar.gz --strip-components=1 \
    && rm /tmp/blackbox-exporter.tar.gz \
    && go version \
    && test "$(go env GOVERSION)" = go1.26.6 \
    && go get \
      golang.org/x/net@v0.56.0 \
      golang.org/x/crypto@v0.53.0 \
      golang.org/x/text@v0.39.0 \
      google.golang.org/grpc@v1.82.1 \
    && go mod tidy \
    && printf '%s  %s\n' "${BLACKBOX_GO_MOD_SHA256}" go.mod | sha256sum --check --strict - \
    && printf '%s  %s\n' "${BLACKBOX_GO_SUM_SHA256}" go.sum | sha256sum --check --strict - \
    && go mod verify \
    && test "$(go list -m -f '{{.Version}}' golang.org/x/net)" = v0.56.0 \
    && test "$(go list -m -f '{{.Version}}' golang.org/x/crypto)" = v0.53.0 \
    && test "$(go list -m -f '{{.Version}}' golang.org/x/text)" = v0.39.0 \
    && test "$(go list -m -f '{{.Version}}' google.golang.org/grpc)" = v1.82.1

FROM source AS test
RUN go test -count=1 ./...

FROM test AS build
ARG TARGETARCH
ARG TARGETOS
RUN install -d \
      /out/rootfs/bin \
      /out/rootfs/etc/blackbox_exporter \
      /out/rootfs/etc/ssl/certs \
      /out/rootfs/licenses/blackbox_exporter \
    && GOARCH="${TARGETARCH}" GOOS="${TARGETOS}" go build \
      -buildvcs=false \
      -trimpath \
      -ldflags="-s -w \
        -X github.com/prometheus/common/version.Version=${BLACKBOX_VERSION}-hardened.1 \
        -X github.com/prometheus/common/version.Revision=${BLACKBOX_COMMIT} \
        -X github.com/prometheus/common/version.Branch=release-0.28 \
        -X github.com/prometheus/common/version.BuildUser=openscholar-hardened \
        -X github.com/prometheus/common/version.BuildDate=20251204-19:23:14" \
      -o /out/rootfs/bin/blackbox_exporter \
      . \
    && cp blackbox.yml /out/rootfs/etc/blackbox_exporter/config.yml \
    && cp /etc/ssl/certs/ca-certificates.crt /out/rootfs/etc/ssl/certs/ca-certificates.crt \
    && cp LICENSE NOTICE /out/rootfs/licenses/blackbox_exporter/ \
    && go version -m /out/rootfs/bin/blackbox_exporter \
    && go version -m /out/rootfs/bin/blackbox_exporter | grep -E 'dep[[:space:]]+golang.org/x/net[[:space:]]+v0.56.0' \
    && go version -m /out/rootfs/bin/blackbox_exporter | grep -E 'dep[[:space:]]+golang.org/x/crypto[[:space:]]+v0.53.0'

FROM scratch
LABEL org.opencontainers.image.source="https://github.com/prometheus/blackbox_exporter" \
      org.opencontainers.image.revision="5a059bee8d8ffa4e75947c5055fb0abeefc582e6" \
      org.opencontainers.image.version="0.28.0-hardened.1" \
      org.opencontainers.image.licenses="Apache-2.0"
COPY --from=build /out/rootfs/ /
ENV PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt
EXPOSE 9115
USER 65534:65534
ENTRYPOINT ["/bin/blackbox_exporter"]
CMD ["--config.file=/etc/blackbox_exporter/config.yml"]
