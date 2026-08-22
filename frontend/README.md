# OpenScholar Frontend

Next.js 16.3.1/React 19.2.8 App Router and strict TypeScript web client for OpenScholar MCP.

Node.js 22.13 or newer is required; Node.js 24 LTS is recommended. The pinned
PDF.js package requires that baseline.

## Local development

Start the Java backend on `http://localhost:8080`, then run:

```bash
cp .env.example .env.local
pnpm install
pnpm dev
```

Open `http://localhost:3000`. Browser requests use same-origin Next.js route handlers; the backend origin stays server-only in `OPENSCHOLAR_API_BASE_URL`.

Authentication defaults to `OPENSCHOLAR_AUTH_MODE=local`, so the existing
single-user local workflow needs no identity provider and behaves exactly as
before.

`predev` and `prebuild` copy the pinned PDF.js worker, character maps, colour
profiles, standard fonts, and WASM modules into a versioned same-origin public
directory. Run `pnpm pdfjs:assets` only when those local assets need to be
refreshed without starting or building Next.js.

## Verify

```bash
pnpm check
```

This runs ESLint, strict TypeScript, Vitest, and a production Next.js build.

After installing Chromium once with `pnpm exec playwright install chromium`, run the deterministic offline browser suite with:

```bash
pnpm test:e2e
```

That suite blocks unexpected external traffic and covers search/cache/provenance, provider warnings, verified and restricted access, PDF.js reader keyboard/text behavior, collections, tags/status, and citation downloads with WCAG 2.2 axe checks. The separate `pnpm test:e2e:compose` lane requires the disposable Spring Boot/PostgreSQL/OpenAlex-fixture stack; use the complete commands in the [portfolio evidence guide](../docs/PORTFOLIO_DEMO.md#reproduce).

## Current flow

- Search OpenAlex-backed research with bounded filters.
- Reopen immutable cached search snapshots.
- Inspect canonical paper metadata and provenance.
- Resolve and open independently verified legal versions.
- Read fresh, verified HTTPS PDF locations directly in the PDF.js canvas reader.
- Download BibTeX or CSL-JSON citations.
- Create, rename, and delete persistent research collections.
- Save canonical papers with reading status and normalized tags.
- Filter the saved library and export selected papers as BibTeX or CSL-JSON.
- Inspect durable metadata/access refresh jobs and retry terminal failures.

Provider-reported PDF URLs from search results are never rendered as verified downloads. Legal-access actions use only the backend `/versions` contract. The reader does not proxy or retain document bytes: the browser requests a selected, fresh verified source directly. Sources that do not permit cross-origin reading fail closed to the external-link fallback.

Every frontend response carries an enforced Content Security Policy and related safety headers. The production Caddy policy mirrors the exact checked-in Next.js value, with a regression test that fails on drift; its documented `unsafe-inline` and PDF.js-specific `wasm-unsafe-eval` allowances are bounded residual risks rather than an absence of CSP.

## Optional hosted OIDC mode

Hosted deployments can set `OPENSCHOLAR_AUTH_MODE=oidc` to enable a
provider-neutral Authorization Code + PKCE BFF flow. Register the exact
`OPENSCHOLAR_OIDC_REDIRECT_URI` and
`OPENSCHOLAR_OIDC_POST_LOGOUT_REDIRECT_URI` values with the provider. Configure
the authorization, token, JWKS, and optional end-session endpoints explicitly;
the frontend does not infer or silently rewrite provider endpoints.

Generate the cookie-encryption key outside source control:

```bash
openssl rand -base64 32
```

Set the output as `OPENSCHOLAR_AUTH_SESSION_SECRET` in the deployment secret
store. All hosted URLs must use HTTPS. The authorization transaction contains
independent state, nonce, and PKCE verifier values and expires after ten
minutes. ID tokens are signature-checked against the configured JWKS and are
validated for issuer, audience, authorized party, expiry, issued time, and
nonce before a session is created.

The session is an AES-256-GCM-sealed `__Host-` cookie with `HttpOnly`, `Secure`,
and `SameSite=Lax`; browser JavaScript never receives readable access or refresh
tokens. Next.js decrypts the session server-side, refreshes access tokens before
expiry, preserves or replaces rotated refresh tokens as the provider directs,
and forwards `Authorization: Bearer ...` to Spring Boot. An explicit
`Authorization` header supplied by a server-side caller is never overwritten.
Sign-in, callback, sign-out, and status endpoints live under `/api/auth/*`.

Supported token-endpoint client authentication methods are `none` (the
default), `client_secret_basic`, and `client_secret_post`. A client secret is
required for the latter two. Supported ID-token signature algorithms are
RS256, PS256, and ES256; allow only the algorithms enabled for the registered
client. The default requested scopes match the hosted backend route groups and
can be narrowed with `OPENSCHOLAR_OIDC_SCOPES` when the UI is deployed with a
smaller feature set.

The current hosted session is intentionally stateless and limited to a
3.8&nbsp;KB encrypted cookie value. Identity providers that return unusually
large token sets fail closed instead of emitting an invalid cookie. Refresh
single-flight coordination is process-local, which matches the supplied
single-frontend-replica deployment; a multi-replica deployment must first move
sessions and refresh coordination to a shared server-side store.
