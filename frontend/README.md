# OpenScholar Frontend

Next.js App Router and strict TypeScript web client for OpenScholar MCP.

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

`predev` and `prebuild` copy the pinned PDF.js worker, character maps, colour
profiles, standard fonts, and WASM modules into a versioned same-origin public
directory. Run `pnpm pdfjs:assets` only when those local assets need to be
refreshed without starting or building Next.js.

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
- Read fresh, verified HTTPS PDF locations directly in the PDF.js canvas reader.
- Download BibTeX or CSL-JSON citations.
- Create, rename, and delete persistent research collections.
- Save canonical papers with reading status and normalized tags.
- Filter the saved library and export selected papers as BibTeX or CSL-JSON.

Provider-reported PDF URLs from search results are never rendered as verified downloads. Legal-access actions use only the backend `/versions` contract. The reader does not proxy or retain document bytes: the browser requests a selected, fresh verified source directly. Sources that do not permit cross-origin reading fail closed to the external-link fallback.
