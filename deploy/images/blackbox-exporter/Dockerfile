FROM --platform=$BUILDPLATFORM golang:1.26.6-bookworm@sha256:116d58cbd88c1297624acc6e967a060012422bacf9930927e23fb719189c6f36 AS source

ARG BLACKBOX_VERSION=0.28.0
ARG BLACKBOX_COMMIT=5a059bee8d8ffa4e75947c5055fb0abeefc582e6
ARG BLACKBOX_SOURCE_SHA256=12b6eb9b307ebc5e55eafe6eb503816756fa0c344854897fb5aeb687544999f9
ARG BLACKBOX_GO_MOD_SHA256=41b42fde54198e1b73c699c65b0b8c3cdbafacbe68c286bbdc17f490e7eee79e
ARG BLACKBOX_GO_SUM_SHA256=1550e927463a721ca0854d7ae96617a17bb093683f4ec35f850c0f87ae514179

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
      golang.org/x/net@v0.58.0 \
      golang.org/x/crypto@v0.55.0 \
      golang.org/x/text@v0.41.0 \
      google.golang.org/grpc@v1.83.2 \
      github.com/google/cel-go@v0.29.0 \
      github.com/quic-go/quic-go@v0.59.1 \
    && go mod tidy \
    && printf '%s  %s\n' "${BLACKBOX_GO_MOD_SHA256}" go.mod | sha256sum --check --strict - \
    && printf '%s  %s\n' "${BLACKBOX_GO_SUM_SHA256}" go.sum | sha256sum --check --strict - \
    && go mod verify \
    && test "$(go list -m -f '{{.Version}}' golang.org/x/net)" = v0.58.0 \
    && test "$(go list -m -f '{{.Version}}' golang.org/x/crypto)" = v0.55.0 \
    && test "$(go list -m -f '{{.Version}}' golang.org/x/text)" = v0.41.0 \
    && test "$(go list -m -f '{{.Version}}' google.golang.org/grpc)" = v1.83.2 \
    && test "$(go list -m -f '{{.Version}}' github.com/google/cel-go)" = v0.29.0 \
    && test "$(go list -m -f '{{.Version}}' github.com/quic-go/quic-go)" = v0.59.1

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
        -X github.com/prometheus/common/version.Version=${BLACKBOX_VERSION}-hardened.2 \
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
    && go version -m /out/rootfs/bin/blackbox_exporter | grep -E 'dep[[:space:]]+golang.org/x/net[[:space:]]+v0.58.0' \
    && go version -m /out/rootfs/bin/blackbox_exporter | grep -E 'dep[[:space:]]+golang.org/x/crypto[[:space:]]+v0.55.0' \
    && go version -m /out/rootfs/bin/blackbox_exporter | grep -E 'dep[[:space:]]+golang.org/x/text[[:space:]]+v0.41.0' \
    && go version -m /out/rootfs/bin/blackbox_exporter | grep -E 'dep[[:space:]]+google.golang.org/grpc[[:space:]]+v1.83.2' \
    && go version -m /out/rootfs/bin/blackbox_exporter | grep -E 'dep[[:space:]]+github.com/google/cel-go[[:space:]]+v0.29.0' \
    && go version -m /out/rootfs/bin/blackbox_exporter | grep -E 'dep[[:space:]]+github.com/quic-go/quic-go[[:space:]]+v0.59.1'

FROM scratch
LABEL org.opencontainers.image.source="https://github.com/prometheus/blackbox_exporter" \
      org.opencontainers.image.revision="5a059bee8d8ffa4e75947c5055fb0abeefc582e6" \
      org.opencontainers.image.version="0.28.0-hardened.2" \
      org.opencontainers.image.licenses="Apache-2.0"
COPY --from=build /out/rootfs/ /
ENV PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt
EXPOSE 9115
USER 65534:65534
ENTRYPOINT ["/bin/blackbox_exporter"]
CMD ["--config.file=/etc/blackbox_exporter/config.yml"]
