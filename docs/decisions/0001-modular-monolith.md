# ADR 0001: Begin with a modular monolith

- Status: Accepted
- Date: 2026-08-16

## Context

OpenScholar requires REST, MCP, provider integrations, normalization, persistence, access policy, and scheduled refresh. Early networked services would add deployment, consistency, tracing, and development costs before load characteristics are known.

## Decision

Use one Spring Boot deployable organized into domain modules with Java interfaces and ArchUnit dependency rules. Run Next.js separately. PostgreSQL is the system of record.

## Consequences

- Search normalization and persistence share transactions.
- Local development/deployment remain approachable.
- Module boundaries require deliberate enforcement.
- Provider/job modules can be extracted when measured workload requires independent scaling.

## Extraction triggers

Consider a worker service when background work harms interactive latency, needs a different deployment cadence, or requires materially different scaling—not merely to claim microservices experience.
