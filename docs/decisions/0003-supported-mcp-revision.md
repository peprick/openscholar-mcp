# ADR 0003: Advertise MCP revision 2025-11-25 initially

- Status: Accepted
- Date: 2026-08-16

## Context

Spring AI 2.0 and official MCP Java SDK 2.0 support MCP revision `2025-11-25`. A newer MCP specification exists, but the Java SDK has not yet announced equivalent support for its newer capabilities.

## Decision

Pin the supported revision to `2025-11-25`, run conformance tests for that revision, and rely on normal protocol negotiation with clients. Do not manually implement newer Tasks or MCP Apps APIs.

## Consequences

- The project makes an accurate compatibility claim.
- Newer clients must negotiate a compatible revision.
- Upgrading requires official Java/Spring support, migration review, conformance evidence, and release notes.
