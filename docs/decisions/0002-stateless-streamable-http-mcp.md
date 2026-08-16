# ADR 0002: Use stateless Streamable HTTP for MCP

- Status: Accepted
- Date: 2026-08-16

## Context

The MCP server must work with remote-capable clients and coexist with the Spring Boot API. Current Spring AI/Java SDK guidance prefers Streamable HTTP and deprecates legacy HTTP+SSE. The initial research tools are bounded request/response operations and do not require server-to-client elicitation.

## Decision

Use `spring-ai-starter-mcp-server-webmvc` with `protocol: STATELESS`, synchronous handlers, and Java 21 virtual threads. Serve `/mcp`. Model long research work as owned jobs. Add STDIO only as an optional local profile.

## Consequences

- REST/MCP share security, observability, deployment, and services.
- Horizontal scaling does not depend on MCP session affinity.
- Stateful progress/elicitation is unavailable initially.
- A later switch to stateful Streamable HTTP requires an explicit use case and ADR.
