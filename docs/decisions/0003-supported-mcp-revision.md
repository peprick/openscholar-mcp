# ADR 0003: Test MCP through revision 2025-11-25 initially

- Status: Accepted
- Date: 2026-08-16

## Context

Spring AI 2.0 and official MCP Java SDK 2.0 support MCP revision `2025-11-25` while retaining normal backward negotiation with legacy revisions. A newer MCP specification exists, but the Java SDK has not yet announced equivalent support for its newer capabilities.

## Decision

Treat `2025-11-25` as the maximum tested revision, run conformance tests for that revision, and rely on the SDK's normal backward protocol negotiation with clients. Do not manually implement newer Tasks or MCP Apps APIs.

## Consequences

- The project distinguishes a tested maximum revision from exclusive version pinning.
- Newer clients must negotiate a compatible revision.
- Upgrading requires official Java/Spring support, migration review, conformance evidence, and release notes.
