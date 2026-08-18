# OpenScholar Frontend

Next.js App Router and strict TypeScript web client for OpenScholar MCP.

## Local development

Start the Java backend on `http://localhost:8080`, then run:

```bash
cp .env.example .env.local
pnpm install
pnpm dev
```

Open `http://localhost:3000`. Browser requests use same-origin Next.js route handlers; the backend origin stays server-only in `OPENSCHOLAR_API_BASE_URL`.

## Verify

```bash
pnpm check
```

This runs ESLint, strict TypeScript, Vitest, and a production Next.js build.

## Current flow

- Search OpenAlex-backed research with bounded filters.
- Reopen immutable cached search snapshots.
- Inspect canonical paper metadata and provenance.
- Resolve and open independently verified legal versions.
- Download BibTeX or CSL-JSON citations.

Provider-reported PDF URLs from search results are never rendered as verified downloads. Legal-access actions use only the backend `/versions` contract.
