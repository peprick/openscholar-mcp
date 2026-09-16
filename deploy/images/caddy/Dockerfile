FROM --platform=$BUILDPLATFORM golang:1.26.6-bookworm@sha256:116d58cbd88c1297624acc6e967a060012422bacf9930927e23fb719189c6f36 AS source

ARG CADDY_VERSION=v2.11.4
ARG CADDY_COMMIT=e2eee6a7fce366321294c9c2a79f3146891dcbdf
ARG CADDY_SOURCE_SHA256=a593bd7077c76102ca76d19287a5e247d4e359dd67eddbc933f865afd3c131eb
ARG CADDY_GO_MOD_SHA256=13ddd2eb2d29501b3270d1d901b631a9091eaaaa29ad37f2330f9cd19c161142
ARG CADDY_GO_SUM_SHA256=66a6e3e692f6f3b0ce4ece62c7192161adc7be2e6f600808cb95b1325176a94b

ENV CGO_ENABLED=0 \
    GOTOOLCHAIN=local
WORKDIR /src

RUN curl --fail --location --proto '=https' --tlsv1.2 \
      "https://github.com/caddyserver/caddy/archive/${CADDY_COMMIT}.tar.gz" \
      --output /tmp/caddy.tar.gz \
    && printf '%s  %s\n' "${CADDY_SOURCE_SHA256}" /tmp/caddy.tar.gz | sha256sum --check --strict - \
    && tar --extract --gzip --file /tmp/caddy.tar.gz --strip-components=1 \
    && rm /tmp/caddy.tar.gz \
    && go version \
    && test "$(go env GOVERSION)" = go1.26.6 \
    && go get \
      golang.org/x/net@v0.58.0 \
      golang.org/x/crypto@v0.55.0 \
      golang.org/x/text@v0.41.0 \
      google.golang.org/grpc@v1.83.2 \
      github.com/google/cel-go@v0.28.1 \
      github.com/quic-go/quic-go@v0.59.1 \
    && go mod tidy \
    && printf '%s  %s\n' "${CADDY_GO_MOD_SHA256}" go.mod | sha256sum --check --strict - \
    && printf '%s  %s\n' "${CADDY_GO_SUM_SHA256}" go.sum | sha256sum --check --strict - \
    && go mod verify \
    && test "$(go list -m -f '{{.Version}}' golang.org/x/net)" = v0.58.0 \
    && test "$(go list -m -f '{{.Version}}' golang.org/x/crypto)" = v0.55.0 \
    && test "$(go list -m -f '{{.Version}}' golang.org/x/text)" = v0.41.0 \
    && test "$(go list -m -f '{{.Version}}' google.golang.org/grpc)" = v1.83.2 \
    && test "$(go list -m -f '{{.Version}}' github.com/google/cel-go)" = v0.28.1 \
    && test "$(go list -m -f '{{.Version}}' github.com/quic-go/quic-go)" = v0.59.1

FROM source AS test
RUN go test -count=1 -p 1 ./...

FROM test AS build
ARG TARGETARCH
ARG TARGETOS
RUN install -d \
      /out/rootfs/etc/caddy \
      /out/rootfs/etc/ssl/certs \
      /out/rootfs/config \
      /out/rootfs/data \
      /out/rootfs/licenses/Caddy \
      /out/rootfs/srv \
      /out/rootfs/usr/bin \
    && GOARCH="${TARGETARCH}" GOOS="${TARGETOS}" go build \
      -buildvcs=false \
      -trimpath \
      -ldflags="-s -w -X github.com/caddyserver/caddy/v2.CustomVersion=${CADDY_VERSION}-hardened.2" \
      -o /out/rootfs/usr/bin/caddy \
      ./cmd/caddy \
    && cp /etc/ssl/certs/ca-certificates.crt /out/rootfs/etc/ssl/certs/ca-certificates.crt \
    && cp LICENSE /out/rootfs/licenses/Caddy/LICENSE \
    && go version -m /out/rootfs/usr/bin/caddy \
    && go version -m /out/rootfs/usr/bin/caddy | grep -E 'dep[[:space:]]+golang.org/x/net[[:space:]]+v0.58.0' \
    && go version -m /out/rootfs/usr/bin/caddy | grep -E 'dep[[:space:]]+golang.org/x/crypto[[:space:]]+v0.55.0' \
    && go version -m /out/rootfs/usr/bin/caddy | grep -E 'dep[[:space:]]+golang.org/x/text[[:space:]]+v0.41.0' \
    && go version -m /out/rootfs/usr/bin/caddy | grep -E 'dep[[:space:]]+google.golang.org/grpc[[:space:]]+v1.83.2' \
    && go version -m /out/rootfs/usr/bin/caddy | grep -E 'dep[[:space:]]+github.com/google/cel-go[[:space:]]+v0.28.1' \
    && go version -m /out/rootfs/usr/bin/caddy | grep -E 'dep[[:space:]]+github.com/quic-go/quic-go[[:space:]]+v0.59.1'

FROM scratch
LABEL org.opencontainers.image.source="https://github.com/caddyserver/caddy" \
      org.opencontainers.image.revision="e2eee6a7fce366321294c9c2a79f3146891dcbdf" \
      org.opencontainers.image.version="v2.11.4-hardened.2" \
      org.opencontainers.image.licenses="Apache-2.0"
COPY --from=build /out/rootfs/ /
ENV CADDY_VERSION=v2.11.4-hardened.2 \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
    XDG_CONFIG_HOME=/config \
    XDG_DATA_HOME=/data
WORKDIR /srv
EXPOSE 80 443 443/udp 2019
USER 10001:10001
CMD ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
