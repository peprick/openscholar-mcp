# Portfolio demo evidence

These screenshots come from the deterministic isolated Compose workflow, not from mocked React components. The workflow drives the production Next.js build against Spring Boot and PostgreSQL, with a metadata-only OpenAlex fixture. It verifies cold and cached HTTP semantics, exact DOI deduplication, canonical paper details/provenance, collection persistence, citation download, and serious/critical WCAG 2.2 axe findings before capturing the pages.

## Deduplicated search results

![OpenScholar search results showing two canonical papers from three provider records](images/search-results.png)

## Canonical paper and provenance

![OpenScholar canonical paper page with metadata, provenance, access, and library controls](images/paper-details.png)

## Persisted research collection

![OpenScholar collection page showing the saved paper, reading status, and tags](images/saved-collection.png)

## Reproduce

From the repository root, build and start the disposable deterministic stack on its isolated ports:

```bash
COMPOSE_PROJECT_NAME=openscholar-portfolio \
POSTGRES_PORT=55432 BACKEND_PORT=8180 FRONTEND_PORT=3300 \
docker compose -f compose.yaml -f deploy/compose.e2e.yaml \
  up --build --detach --wait --wait-timeout 180
```

Then capture the three checked-in views:

```bash
cd frontend
pnpm install --frozen-lockfile
pnpm exec playwright install chromium
PORTFOLIO_SCREENSHOT_DIR=../docs/images \
PLAYWRIGHT_RUN_KEY=portfolio \
PLAYWRIGHT_COMPOSE_ORIGIN=http://127.0.0.1:3300 \
pnpm test:e2e:compose
```

Return to the repository root and remove only that disposable project and volume:

```bash
cd ..
COMPOSE_PROJECT_NAME=openscholar-portfolio \
POSTGRES_PORT=55432 BACKEND_PORT=8180 FRONTEND_PORT=3300 \
docker compose -f compose.yaml -f deploy/compose.e2e.yaml \
  down --volumes --remove-orphans
```

The screenshot hook is disabled unless `PORTFOLIO_SCREENSHOT_DIR` is set, so normal CI runs do not modify the checkout. The fixture contains synthetic metadata and no PDF document bytes. The separate offline Playwright suite is the executable evidence for provider-warning partial failure, a restricted-paper detail state, and the supported PDF.js reader/keyboard flow; those states are not represented by the three static screenshots above.
