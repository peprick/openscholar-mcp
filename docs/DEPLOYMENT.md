# Hosted Deployment Template

## Status and hard gate

`deploy/compose.production.yaml` is a hardened, single-host portfolio deployment template. It is not evidence of a running service or a claim that OpenScholar is ready for anonymous Internet exposure. Production Compose is fail-closed on hosted OIDC configuration and on four project-owned runtime image identities. The example environment deliberately contains non-deployable `replace-me` digest placeholders for backend, frontend, Caddy, and blackbox-exporter. Do not run the stack until CI has built those four checked-in image definitions, scanned the exact outputs, pushed them to the approved GHCR repositories, rescanned the registry digests, signed/attested them, and placed their reviewed `tag@sha256` references in the ignored `deploy/production.env`.

The backend JWT resource server, audience/scope checks, issuer+subject ownership, privacy export/deletion, protected-resource metadata, frontend OIDC BFF, and negative authorization tests are implemented. Keep `PUBLIC_BIND_ADDRESS` on loopback until a real identity-provider registration and the remaining launch gates below have been exercised in the target environment.

After the image gate is satisfied, the template is useful for local production-mode verification and, after identity-provider interoperability is proven, a reviewed private preview. Public launch also requires the legal and provider decisions in `SECURITY_AND_LEGAL.md`.

## Topology

Only Caddy publishes host ports. PostgreSQL, Spring Boot, Next.js, Prometheus, Alertmanager, and the blackbox exporter have no host port. The reviewed production target is explicitly `linux/amd64`; this matches the image scans and scoped VEX evidence even when an image reference points to a multi-platform index. Separate internal networks segment intended application, database, and monitoring connectivity, but multi-homed services necessarily bridge some of those segments. The backend has egress for providers and issuer JWKS, the frontend has a dedicated identity-egress network for server-side token/JWKS calls, the blackbox exporter has probe egress, Alertmanager has notification egress, and Caddy has edge egress for ACME. These ordinary bridge networks express workload intent but are neither per-port firewalls nor destination allowlists; enforce the required east-west and external-destination policy with target-host or platform controls.

The backend and frontend retain the non-root users in their images. Caddy runs as numeric UID `10001` and uses a container-local unprivileged-port sysctl so all capabilities can be dropped. The writable surfaces are deliberately narrow: PostgreSQL data, Caddy certificate state, Prometheus/Alertmanager state, and bounded temporary filesystems. All other container filesystems are read-only and use `no-new-privileges`.

The example CPU, memory, and process ceilings are starting guardrails for a small host, not measured capacity claims. Load-test provider fan-out, large cached libraries, database migrations, refresh workers, and monitoring retention on the target host; adjust one reviewed budget at a time and alert on sustained saturation/OOM restarts.

For a real service, managed PostgreSQL with point-in-time recovery is preferred. The bundled database is suitable for a single-host demonstration and restore drills, not high availability.

## Host prerequisites

- A supported `linux/amd64` host with Docker Engine and Docker Compose v2.
- `age` for backup encryption plus `sha256sum` or `shasum` for integrity sidecars.
- A dedicated DNS name. Public ACME issuance requires correct DNS plus inbound TCP 80/443.
- A registry containing reviewed backend, frontend, Caddy, and blackbox-exporter images. Use immutable digest references after build, publication, and registry-digest rescan.
- Encrypted storage for database and backup data.
- An approved OIDC authorization server with separate registrations for the browser BFF and MCP clients before any public bind.
- An outbound policy that permits only required scholarly-provider, identity-provider, alert-receiver, public-probe, and ACME endpoints where the platform supports destination filtering.

Rootless runtimes or hosts that reject `net.ipv4.ip_unprivileged_port_start` need a reviewed port/capability adaptation; do not silently run the proxy privileged.

## Prepare configuration

From the repository root:

```bash
cp deploy/production.env.example deploy/production.env
cp deploy/prometheus/targets/public-endpoints.example.json \
  deploy/prometheus/targets/public-endpoints.json
```

Edit both copies. `PUBLIC_HOST` and the public probe target must name the same deployed endpoint. Keep `PUBLIC_BIND_ADDRESS=127.0.0.1` until the external launch gates are complete.

Create the ignored secret/state paths. On a Linux production host, the numeric owners must match the container users:

```bash
install -d -m 0700 deploy/secrets
install -d -m 0700 -o 10001 -g 10001 deploy/state/caddy-data
install -d -m 0700 -o 65534 -g 65534 \
  deploy/state/prometheus deploy/state/alertmanager
openssl rand -base64 48 > deploy/secrets/postgres_password
openssl rand -base64 32 > deploy/secrets/auth_session_secret
openssl rand -base64 48 > deploy/secrets/oidc_client_secret
chown 10001:10001 deploy/secrets/postgres_password
chown 1001:1001 \
  deploy/secrets/auth_session_secret deploy/secrets/oidc_client_secret
chmod 0400 deploy/secrets/postgres_password \
  deploy/secrets/auth_session_secret deploy/secrets/oidc_client_secret
```

Compose file-backed secrets preserve host-file constraints differently across runtimes. Verify that PostgreSQL and the non-root backend can read only the mounted database-password target and that the non-root frontend can read only its session and OIDC-client-secret targets. Verify that none is readable to unrelated host users. If the runtime cannot preserve the required ownership, use its native secret store or a reviewed secret-init mechanism instead of loosening host permissions.

The production file intentionally builds no image. Backend, frontend, Caddy, and blackbox-exporter are four project-owned release artifacts: build their checked-in definitions in CI, scan the exact local outputs, push them to the approved `ghcr.io/peprick/openscholar-*` repositories, rescan the registry digests, sign/publish provenance, and set all four image variables to those `tag@sha256` references. The [hardened image build notes](../deploy/images/README.md) record the proxy/prober input and provenance boundaries. The official Caddy and blackbox-exporter runtime images are not production fallbacks because their current findings fail this repository's high/critical gate.

PostgreSQL, Prometheus, and Alertmanager remain reviewed third-party images pinned by tag plus manifest digest. `deploy/production-images.lock` constrains those exact third-party references and the approved repository for each project-owned artifact. The wrapper rejects every example placeholder, floating tag, digest-only reference, wrong repository, and ambient override. Review and update each tag, digest, policy entry, scan result, signature, and attestation together.

The hardened blackbox-exporter build checksum-pins its upstream input, but the upstream project does not publish a detached signature for that input. A checksum detects drift after the value is reviewed; it is not independent proof of publisher identity. Record that provenance limitation in release approval, retain the fetched-source checksum/evidence, and prefer a verifiable signed upstream release if one becomes available.

## Identity-provider registration

Register the Next.js application as a confidential authorization-code client that permits PKCE S256 and the exact callback `https://PUBLIC_HOST/api/auth/callback`. Configure its client ID, authorization endpoint, token endpoint, JWKS endpoint, optional end-session endpoint, issuer, and approved ID-token algorithms in `deploy/production.env`. The application uses explicit endpoints rather than dynamic OIDC discovery, validates state/nonce and ID-token claims/signatures, and authenticates at the token endpoint with `client_secret_basic`.

The authorization server must issue JWT access tokens for the configured backend `OIDC_AUDIENCE`. The current validator accepts one audience for both the REST API and MCP, so set `OIDC_AUDIENCE` equal to the canonical `OIDC_MCP_RESOURCE_URI` and configure the browser registration to receive that audience by default. Grant only the scopes required by the client: `openscholar.search`, `openscholar.library`, `openscholar.jobs`, `openscholar.privacy`, `openscholar.mcp`, and `openscholar.ops`. The checked-in browser client requests the first four by default. `openscholar.jobs` exposes the caller's own search-refresh rows but also every shared-catalog `PAPER_ACCESS` row; it is not a fully private per-user job scope, so remove it from ordinary user clients if that operational visibility is inappropriate.

Register MCP clients against the same canonical HTTPS resource URI ending in `/mcp` and grant `openscholar.mcp`. Conforming clients send that URI as the OAuth resource indicator, and the authorization server must issue a token whose audience the configured validator accepts. Caddy forwards `/.well-known/oauth-protected-resource*` to Spring Boot, which publishes the resource metadata and identifies the external authorization server. OpenScholar validates tokens but does not issue them. Test login, token refresh/rotation, logout, wrong issuer/audience/scope, cross-user ownership, MCP discovery, and key rotation against the chosen provider before changing the public bind.

## Secrets and provider configuration

The PostgreSQL password enters Spring Boot through its `configtree` import. The frontend image contains its reviewed, mode-`0555` entrypoint; Compose invokes that baked script directly instead of injecting executable code with a bind mount. The entrypoint reads the AES-256-GCM session key and confidential-client secret from their mode-`0400` files and exports them only to the Next.js process. Hosted mode does not use or mount the local `MCP_LOCAL_API_KEY`. None of these secrets belongs in command-line flags, image layers, or public frontend variables. The Caddy ACME email and database name/user are not credentials.

For hosted environments, prefer a cloud secret manager, Vault, or an equivalent audited service. Materialize secrets into a root-only or service-owned in-memory filesystem at deploy time, restrict read access to the intended workload identity, and keep secret values out of Compose files, CI logs, GitHub variables that are not marked secret, shell history, and support bundles.

Optional provider secrets are not wired into the template. Add each as a backend-only config-tree secret after the provider's terms, required operator identity, rotation owner, and failure mode are approved. Never expose a provider credential to Next.js or forward an inbound user/MCP bearer token to a provider.

Unpaywall, DataCite, and DOAJ contact emails are non-credential backend identification fields in the ignored production environment file. Prefer a role address with an approved privacy/retention policy. Leaving `UNPAYWALL_EMAIL` blank disables that access source; do not invent or borrow an identity merely to avoid the provider requirement. DataCite and DOAJ remain disabled until their provider flags are enabled.

Before enabling CORE, record the applicable licence/authorization review and retain `CORE_LICENSE_CONFIRMED=false` until it is complete. Enabling a flag is not itself legal permission.

## Validate and start

The commands in this section apply only after all four project-owned registry refs have cleared the image gate above. Use the checked-in production wrapper for every deployment Compose command. It resolves the observability profile as well as the minimum stack, requires `linux/amd64` on every service, rejects missing/extra services, mutable or digest-only image references, and Compose-global file/environment/project overrides, then checks every resolved service image against `deploy/production-images.lock` immediately before delegating an allowlisted command. This also catches unreviewed ambient shell image overrides. The wrapper refuses `down -v` and `down --volumes`. Validate without changing state:

```bash
scripts/production-compose.sh deploy/production.env --check
```

Start the minimum stack, then inspect status and logs:

```bash
scripts/production-compose.sh deploy/production.env \
  up -d postgres backend frontend proxy
scripts/production-compose.sh deploy/production.env ps
```

Enable the optional monitoring profile only after the blackbox-exporter release ref is approved and an actual Alertmanager receiver is configured:

```bash
scripts/production-compose.sh deploy/production.env \
  --profile observability up -d
```

Verify from outside the container networks:

```bash
curl --fail --show-error --silent https://research.example.com/
curl --fail --show-error --silent \
  https://research.example.com/api/v1/system/status
```

The edge deliberately routes `/api/v1/*`, `/mcp`, and `/.well-known/oauth-protected-resource*` directly to Spring Boot. Other paths, including browser `/api/*` BFF routes, go to Next.js. Actuator diagnostics and monitoring UIs are not publicly routed. Use an SSH tunnel or the container network for authorized operational access.

Caddy enforces the same production Content Security Policy that Next.js emits directly. The policy permits local Next.js/PDF.js assets and workers, same-origin browser API calls, outbound HTTPS document fetches, and the WebAssembly-only `wasm-unsafe-eval` capability required by PDF.js; it denies framing, plugins, base-URL changes, inline event handlers, and cross-origin form submissions. OIDC authorization and logout remain server-generated redirects and do not require browser script or connection access to the identity provider. Caddy supplies `strict-origin-when-cross-origin` only when an upstream response has no policy, preserving the auth routes' stricter `no-referrer` response. The checked-in regression test fails if the edge and frontend CSP values drift. The static policy retains `unsafe-inline` for Next.js bootstrap scripts and compatible styling but excludes general `unsafe-eval` in production; a nonce-based policy is a future coordinated rendering change, not an edge-only toggle.

Caddy's JSON access log keeps paths/status/timing while replacing common research and OIDC query values, deleting credential/cookie/forwarding/referrer headers and redirect locations, and truncating client IP precision. This prevents callback authorization codes, state values, sealed cookies, bearer tokens, unmasked forwarded addresses, and authorization redirect parameters from entering the edge log. Review every newly introduced query parameter or sensitive response header; application logs and provider URLs still require their own minimization and retention controls.

The template bounds Docker JSON logs to five compressed 10 MiB files per service so a chatty process cannot consume the whole host disk. Replace that driver only with a tested centralized pipeline that has equivalent backpressure, access control, redaction, retention, and outage behavior.

## Monitoring boundary

The backend includes the Prometheus registry. Production Compose moves Actuator to a separate, un-published `backend:9091` management listener, and Prometheus reaches `/actuator/prometheus` over the internal monitoring network. Caddy has no route to that listener. Docker bridge membership is not a per-port firewall, so workloads sharing one of the backend's other private networks may also reach port 9091; use a platform network policy, host firewall, or separately authenticated management boundary if the target environment requires monitoring-only reachability. Application alerts cover missing backend metrics, provider failure rate/p95, terminal refresh-job failures, MCP rejections, and sustained HTTP 5xx responses. Blackbox probes independently cover readiness, frontend availability, PostgreSQL TCP, public TLS/HTTP, probe duration, certificate expiry, and the monitoring pipeline.

Do not add `/actuator/prometheus` or any other Actuator route to Caddy. If the topology changes, preserve a private or separately authenticated management boundary and test that no public edge path reaches it.

Alertmanager's checked-in receiver is intentionally a no-op placeholder. Its `monitoring-egress` attachment permits a future approved notification integration, but a deployment has no working page path until an operator injects and tests an approved email, PagerDuty, Opsgenie, Slack, or webhook receiver without committing its credentials.

## Release and rollback

Use immutable image digests and record the database migration version in every release. Back up and restore-test before any migration. Flyway migrations are forward-only; a container rollback is safe only when the previous application is compatible with the migrated schema. When compatibility is unknown, restore a verified backup into a new database and validate it before switching traffic.

Do not bypass `scripts/production-compose.sh` for a deployment command, and do not run `down --volumes` through either the wrapper or raw Docker Compose in production. That flag deletes the named PostgreSQL volume.

## External decisions still required

- Public versus private-preview exposure and the acceptable abuse model.
- Real OIDC issuer/client registration, scope grants, interoperability evidence, signing-key/client-secret rotation, and administrator access policy. The application-side resource server and BFF are implemented, not provider-provisioned.
- Domain, DNS, firewall, access gateway/WAF, trusted proxy chain, and rate-limit identity.
- Container registry plus CI build/publication, registry-digest rescan, signing/attestation, and deployment-time verification for all four project-owned runtime images (backend, frontend, Caddy, and blackbox-exporter). The checked-in `replace-me` values are an intentional deployment block, not configuration examples that may be left in place.
- Approval/renewal ownership for the checked-in vulnerability-exception process. Reviewed third-party images/actions are immutable, but every release still requires current scan and exception evidence.
- Managed PostgreSQL versus single-host database, region, encryption key, PITR, RPO, RTO, and restore owner.
- Secret manager, workload identity, rotation schedule, and emergency revocation owner.
- Alert receiver, on-call owner, escalation timing, log/metric retention, and service objectives.
- Provider licences/quotas/attribution, privacy notice and retention policy, and qualified legal review. Export/deletion mechanics exist but do not by themselves establish regulatory compliance.
- Target-environment assistive-technology accessibility, load, penetration, and disaster-recovery evidence before launch. Local WCAG 2.2 axe and performance evidence are not substitutes for these exercises.
- A full target-host Docker smoke test after the approved project-owned Caddy image is published and pinned. Local Dockerfile/configuration tests and scratch-image scans are not runtime evidence for the eventual registry artifact, host, or network.
