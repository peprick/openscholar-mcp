# ADR 0009: Return versioned safe MCP tool errors outside success schemas

- Status: accepted
- Date: 2026-08-24

## Context

OpenScholar's six MCP tools advertise generated output schemas for successful typed
results. The default Spring AI annotation callback converts thrown exceptions to
text, but that text can include framework wrappers and the deepest exception
message. SDK input validation also completes before the annotation callback. Those
paths cannot provide a stable machine-readable descriptor and are unsafe places to
trust provider, persistence, or argument-conversion messages.

MCP revision `2025-11-25` represents a tool-execution failure with `isError=true`.
It also requires structured output to conform to an advertised output schema. An
error object in `structuredContent` would therefore conflict with every current
success-only schema unless all six contracts became explicit success/error unions.
That would enlarge and destabilize otherwise useful generated schemas.

## Decision

OpenScholar owns the synchronous stateless tool-registration and callback boundary.
Spring's annotation provider still generates tool names, descriptions, input
schemas, output schemas, and safety annotations. OpenScholar closes those input
schemas to unknown properties. The project callback validates arguments against
the closed generated JSON Schema before conversion and application dispatch,
validates successful structured output before returning it, then maps expected
exception classes to a closed error catalog.

Every mapped tool failure returns:

- HTTP 200 with a JSON-RPC result rather than a protocol error;
- `isError=true`;
- exactly one fixed, safe text item for models and hosts that hide metadata;
- `_meta["com.openscholar/error"]` containing schema version 1, a stable code,
  category, fixed message, retryability, action, and an optional bounded positive
  retry delay; and
- no `structuredContent`.

Messages and metadata never copy submitted values, identifiers, ownership facts,
provider bodies/URLs/headers, exception messages or causes, SQL/JDBC details,
credentials, or stack traces. Retry delays are emitted only for retryable errors,
round fractional seconds up, and are omitted when absent, non-positive, or greater
than one day. Missing and other-owner objects use the same complete descriptor.

Unknown tools and malformed JSON-RPC envelopes remain protocol errors. MCP
authentication, authorization, Origin, HTTP payload-size, and request-rate failures
remain transport responses. Partial provider success, stale fallback, restricted
access, and no verified full-text location remain successful domain results.

## Consequences

- Agents always receive actionable safe text; metadata-aware clients can branch on
  stable fields without parsing prose.
- Successful structured results and generated output schemas remain backward
  compatible and protocol-conformant.
- OpenScholar replaces the starter's stateless server assembly so SDK input
  validation can run inside the safe callback instead of returning framework text.
- The custom registration is active only for the supported enabled, non-STDIO,
  `STATELESS` + `SYNC` server profile; other MCP server modes do not register these
  tools.
- Spring AI or MCP Java SDK upgrades must recheck tool registration, closed input
  schemas, result metadata serialization, and success-output validation.
- New tool errors require a catalog entry and wire tests; arbitrary detail maps are
  not accepted.

## Validation

Tests must prove exactly six unique tools, closed generated input schemas, unchanged
output schemas and annotations, unchanged successful structured results, safe
schema/conversion/domain failures, retry-delay normalization, absence of error
`structuredContent`, provider and exception non-disclosure, and JSON-RPC separation
for unknown tools. Partial provider, stale, and restricted-access cases must remain
successful results.
