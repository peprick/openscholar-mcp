# Contributing to OpenScholar

Thank you for helping improve OpenScholar. Changes should preserve the project's central promise: useful research discovery with clear provenance and without bypassing access controls.

## Before you start

- Use an issue for bugs and focused feature requests.
- Discuss large scope, new providers, authentication changes, or data-retention changes before implementing them.
- Never include API keys, access tokens, private research documents, production data, or copied publisher content.
- Report vulnerabilities through the private process in [.github/SECURITY.md](.github/SECURITY.md).

## Development setup

The simplest full-stack workflow requires Git and Docker Compose:

```bash
cp .env.example .env
docker compose up --build
```

For component development:

- Backend: JDK 21 and Docker, then `cd backend && ./mvnw spring-boot:run`.
- Frontend: Node.js 22.13+ (Node.js 24 LTS recommended), Corepack, and pnpm, then `cd frontend && corepack enable && pnpm install --frozen-lockfile`.

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for the complete workflow and local configuration.

## Make a focused change

1. Branch from an up-to-date `main` with a short name such as `fix/access-fallback` or `feat/provider-name`.
2. Keep commits scoped and descriptive.
3. Add or update tests alongside behavior changes.
4. Update public contracts and documentation in the same pull request.
5. Run the relevant verification before opening the pull request.

### Verification

Backend changes:

```bash
cd backend
./mvnw --batch-mode --no-transfer-progress verify
```

Frontend changes:

```bash
cd frontend
pnpm check
```

User-flow changes should also run the offline browser suite:

```bash
cd frontend
pnpm exec playwright install chromium
pnpm test:e2e
```

Documentation changes should validate local targets from the repository root:

```bash
node scripts/validate-docs.mjs
```

Deployment, shell, workflow, or supply-chain changes should run the matching scripts under `scripts/`; CI runs the complete policy set.

## Project-specific rules

### Database migrations

- Add a new numbered Flyway migration; never edit a migration that has been released on `main`.
- Test both a clean database and any risky upgrade path.
- Keep Hibernate schema generation disabled outside tests.

### REST and MCP contracts

- Keep controllers, runtime response schemas, MCP tool schemas, and [docs/openapi.yaml](docs/openapi.yaml) aligned.
- Preserve bounded inputs and outputs, stable error codes, owner scoping, and provenance.
- Do not return persistence entities or provider payloads directly from public adapters.

### Research providers and document access

- Use supported APIs; do not add scraping, CAPTCHA workarounds, paywall bypasses, or automated document downloads.
- Document official terms, authentication, attribution, quotas, and rate limits.
- Keep credentials server-side and use only synthetic or legally shareable test fixtures.
- Treat provider-reported links as candidates. Only independently verified locations may be presented as legal-access actions.
- New providers should remain disabled until their configuration, failure isolation, request budgets, and policy review are complete.

### Architecture and security

- Record material boundary decisions as an ADR in `docs/decisions/`.
- Preserve same-origin browser access through the Next.js BFF; do not expose backend or provider secrets to client bundles.
- Add tests for ownership, validation, failure, privacy, and security-policy branches affected by the change.

## Pull request checklist

- [ ] The change is focused and the motivation is explained.
- [ ] Relevant automated tests pass locally.
- [ ] Public REST, MCP, configuration, and documentation changes are synchronized.
- [ ] Database changes use a new Flyway migration.
- [ ] Provider/legal, security, privacy, and data-retention implications were considered.
- [ ] No credentials, private documents, generated build output, or production data are included.

The repository does not currently use a contributor licence agreement or an open-source licence. Before merging a third-party contribution, the repository owner and contributor must agree on the terms under which that contribution is provided.
