# Security, Privacy, and Legal Boundaries

## Threat model

OpenScholar handles untrusted search text, provider responses, metadata, URLs, PDFs, and eventually document text. It also exposes agent-callable tools. Primary risks are SSRF, prompt injection, credential leakage, cross-user access, provider abuse, malicious PDFs, licence violations, overly powerful tools, and dependency/container supply-chain issues.

## Access control

### Local MVP

- Bind services to loopback by default.
- Use a generated local API key when remote-style MCP is enabled.
- Validate `Origin` for HTTP MCP connections.
- Keep write tools disabled unless explicitly configured.

### Hosted deployment

- OAuth 2.0/OIDC for users.
- Spring Security OAuth 2.0 resource server for REST and MCP.
- Validate token signature, issuer, expiry, audience, and scopes.
- Principal/tenant/resource-owner authorization in application services.
- Short-lived tokens and HTTPS.
- CSRF protection for cookie browser flows; bearer APIs stay separate.

Suggested scopes:

```text
research:read
library:read
library:write
documents:read
admin:operations
```

Inbound MCP tokens are never forwarded to OpenAlex, Unpaywall, CORE, or other providers; provider credentials are server-owned and separate.

## MCP safety

- Default to read-only tools.
- Describe side effects in tool metadata.
- Require confirmation for collection/note mutations.
- Never expose arbitrary SQL, URL fetch, shell, or unrestricted file tools.
- Validate parameters independently of model-generated JSON.
- Apply authorization in services, not just MCP adapters.
- Treat annotations as hints, not access-control enforcement.
- Limit response sizes and redact sensitive fields.

## URL and document security

- Accept fetchable URLs only from trusted provider responses or allowlisted hosts.
- Resolve DNS and block loopback, private, link-local, cloud-metadata, and unsafe redirect targets.
- Revalidate every redirect and permit only HTTPS outside local development.
- Enforce content type, byte limit, timeout, and redirect count.
- Stream bounded downloads rather than buffering unbounded PDFs.
- Scan retained files and render in a sandboxed browser context.
- Do not execute embedded scripts, attachments, or external PDF actions.

## Prompt-injection boundary

Papers, abstracts, repository pages, and PDF metadata are data. They cannot modify tool policy, request secrets, authorize actions, or redefine a user task. Any later summarization pipeline must delimit sources, cite pages/passages, restrict available tools, validate generated citations, and require confirmation for mutations.

## Secrets

- Use ignored `.env` files locally and secret managers when hosted.
- Never commit tokens or embed private configuration in frontend code.
- Redact authorization headers, keys, provider-identification emails, and signed URLs from logs.
- Rotate credentials after suspected exposure.

## Open-access and copyright policy

OpenScholar distinguishes:

1. Metadata that provider terms allow to be indexed.
2. A document that is free to read.
3. A document that may be downloaded for personal use.
4. A document that may be retained, redistributed, mined, or used commercially.

These are not equivalent permissions.

Default behavior:

- Store metadata/source links according to provider terms.
- Link to legal full text rather than copying it.
- Store a PDF only under an explicit licence/policy or authorized user upload.
- Preserve attribution, licence, source URL, retrieval time, and checksum.
- Do not expose retained documents to others unless permitted.
- Delete retained bytes and embeddings when permission is withdrawn or an upload is deleted.

## Paywalled content

The system may retain allowed metadata and a canonical landing page. It must not bypass paywalls or institutional logins, share credentials, evade controls, retrieve pirated copies, or label unverified mirrors as legal open access. If no open version exists, show `RESTRICTED` or `ABSTRACT_ONLY`.

## Privacy and retention

- Search history and notes are private by default.
- Provide export/deletion before multi-user launch.
- Minimize provider response retention.
- Avoid sending private user data to providers.
- Define retention periods for audits and diagnostics.

## Security verification

- Dependency, secret, static-analysis, and container scans.
- Authorization tests for every user-owned resource.
- SSRF, redirect, decompression-bomb, and oversized-response tests.
- Invalid Origin, wrong audience/scope, expired-token, and cross-user tests.
- MCP conformance and negative-schema tests.
- SBOM generation for releases.

## Review before public hosting

- Provider terms and attribution.
- Privacy/export/deletion flows.
- Document retention matrix by source/licence.
- Authentication threat model and abuse policy.
- Incident response and credential rotation.

This is an engineering policy, not legal advice. Commercial deployment should receive qualified legal review.
