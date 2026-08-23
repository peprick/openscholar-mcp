# Security policy

## Supported versions

OpenScholar has not published a stable release yet. Security fixes are applied to the latest revision of `main`; older commits and local forks are not supported versions.

## Report a vulnerability privately

Do not open a public issue for a suspected vulnerability.

Use [GitHub private vulnerability reporting](https://github.com/peprick/openscholar-mcp/security/advisories/new) to share the report with the repository owner. Include:

- the affected component and revision;
- a clear reproduction or proof of concept;
- the expected and observed security impact;
- any known mitigations;
- whether the issue has been disclosed elsewhere.

Avoid including real credentials, personal data, copyrighted research documents, or destructive payloads. Use synthetic data and the smallest safe reproduction possible.

The maintainer will confirm receipt, investigate, and coordinate remediation and disclosure through the private advisory. Response timing depends on severity and maintainer availability; please allow a reasonable private remediation window before public disclosure.

## Scope

Particularly relevant areas include authentication and owner isolation, MCP authorization, server-side request forgery, untrusted document links, provider credential leakage, privacy deletion/export, dependency or container supply chain, and deployment-boundary mistakes.

Provider availability, missing metadata, publisher access restrictions, and unsupported cross-origin PDF rendering are usually product limitations rather than vulnerabilities unless they create a security boundary bypass.
