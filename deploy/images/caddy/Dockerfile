FROM --platform=$BUILDPLATFORM golang:1.26.7-bookworm@sha256:6ef6e30f0ea5c384f6d111cf856e024e3086bbdcb1779da3f3b3fbba0aea53d2 AS source

ARG CADDY_VERSION=v2.11.4
ARG CADDY_COMMIT=e2eee6a7fce366321294c9c2a79f3146891dcbdf
ARG CADDY_SOURCE_SHA256=a593bd7077c76102ca76d19287a5e247d4e359dd67eddbc933f865afd3c131eb
ARG CADDY_GO_MOD_SHA256=86be144f8127f9bf6a173b26195ce10208dba3b716240cda945828cac029e9a1
ARG CADDY_GO_SUM_SHA256=9a1e280c35131c1add8098db896e53761d316e3fdaf02f16536123c4ef3f523e

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
      golang.org/x/net@v0.56.0 \
      golang.org/x/crypto@v0.53.0 \
      golang.org/x/text@v0.39.0 \
      google.golang.org/grpc@v1.82.1 \
    && go mod tidy \
    && printf '%s  %s\n' "${CADDY_GO_MOD_SHA256}" go.mod | sha256sum --check --strict - \
    && printf '%s  %s\n' "${CADDY_GO_SUM_SHA256}" go.sum | sha256sum --check --strict - \
    && go mod verify \
    && test "$(go list -m -f '{{.Version}}' golang.org/x/net)" = v0.56.0 \
    && test "$(go list -m -f '{{.Version}}' golang.org/x/crypto)" = v0.53.0 \
    && test "$(go list -m -f '{{.Version}}' golang.org/x/text)" = v0.39.0 \
    && test "$(go list -m -f '{{.Version}}' google.golang.org/grpc)" = v1.82.1

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
      -ldflags="-s -w -X github.com/caddyserver/caddy/v2.CustomVersion=${CADDY_VERSION}-hardened.1" \
      -o /out/rootfs/usr/bin/caddy \
      ./cmd/caddy \
    && cp /etc/ssl/certs/ca-certificates.crt /out/rootfs/etc/ssl/certs/ca-certificates.crt \
    && cp LICENSE /out/rootfs/licenses/Caddy/LICENSE \
    && go version -m /out/rootfs/usr/bin/caddy \
    && go version -m /out/rootfs/usr/bin/caddy | grep -E 'dep[[:space:]]+golang.org/x/net[[:space:]]+v0.56.0' \
    && go version -m /out/rootfs/usr/bin/caddy | grep -E 'dep[[:space:]]+golang.org/x/crypto[[:space:]]+v0.53.0'

FROM scratch
LABEL org.opencontainers.image.source="https://github.com/caddyserver/caddy" \
      org.opencontainers.image.revision="e2eee6a7fce366321294c9c2a79f3146891dcbdf" \
      org.opencontainers.image.version="v2.11.4-hardened.1" \
      org.opencontainers.image.licenses="Apache-2.0"
COPY --from=build /out/rootfs/ /
ENV CADDY_VERSION=v2.11.4-hardened.1 \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
    XDG_CONFIG_HOME=/config \
    XDG_DATA_HOME=/data
WORKDIR /srv
EXPOSE 80 443 443/udp 2019
USER 10001:10001
CMD ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
