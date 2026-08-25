# Operations Runbook

## Scope

This runbook covers the single-host deployment template after its image-release gate has cleared. It does not replace a cloud-provider recovery procedure or an organization incident-response plan. Commands assume an operator has selected the intended host and uses a reviewed `deploy/production.env` containing published, rescanned, signed/attested `tag@sha256` references for the project-owned backend, frontend, Caddy, and blackbox-exporter images. The checked-in `replace-me` values intentionally make these commands fail before release artifacts exist.

Use the checked-in production wrapper so every command selects the production file/environment and validates the fully resolved image set against the reviewed policy first:

```bash
scripts/production-compose.sh deploy/production.env ps
```

Run `scripts/production-compose.sh deploy/production.env --check` before an update; the wrapper repeats its fail-closed image preflight before every delegated command. Never paste secret values into tickets or log commands.

## Routine checks

- Confirm all expected services are running and PostgreSQL is healthy.
- Probe the public HTTPS root and `/api/v1/system/status` from outside the host.
- Review firing and pending Prometheus alerts through an authenticated tunnel.
- Confirm Caddy renewal logs contain no sustained ACME errors.
- Review disk capacity for PostgreSQL, Caddy, monitoring, and backup filesystems.
- Confirm the last backup checksum and independent restore drill.
- Review the private application-meter scrape for search/cache, provider, refresh-job, MCP, and HTTP signals, then use sanitized logs for bounded diagnosis. Never include credentials or private queries in either evidence source.
- Patch only through reviewed image updates; do not install packages interactively in containers.

Useful read-only commands:

```bash
scripts/production-compose.sh deploy/production.env ps
scripts/production-compose.sh deploy/production.env \
  logs --since 30m --no-color backend frontend proxy
scripts/production-compose.sh deploy/production.env exec -T postgres \
  sh -eu -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Logs can contain search topics, document titles, URLs, request IDs, and operational metadata. Share the minimum needed excerpt and redact tokens, signed URLs, contact emails, and user content.

## Alert procedures

### Endpoint down

1. Determine whether only the public probe, an internal component, or all probes failed.
2. Check DNS/TLS from an independent network, then the wrapper's `ps` output and bounded recent logs.
3. If Caddy is healthy but backend readiness fails, check PostgreSQL before restarting anything.
4. If a new release triggered the failure, stop rollout and follow the recorded schema-compatible rollback plan.
5. Preserve logs and timestamps. Do not disable authentication, SSRF controls, or payload limits to restore service.

### Slow or saturated service

1. Compare public and internal probe duration to separate edge/network latency from application latency.
2. Check host CPU, memory pressure, disk latency/capacity, PostgreSQL connections, and provider timeouts.
3. Disable optional scheduled refresh or a newly enabled provider before reducing interactive safeguards.
4. Capture a sanitized incident timeline and validate recovery with both readiness and a representative read flow.

### Privacy export rejected or interrupted

A `429 PRIVACY_EXPORT_BUSY` means the per-instance global or per-principal gate rejected the request before an export transaction or attachment opened. Honor `Retry-After`; do not create a tight retry loop. Check concurrent exports and database connection pressure without trying to infer which limit rejected a specific user.

1. A `422 PRIVACY_EXPORT_TOO_LARGE` is a deliberate pre-commit boundary, not a partial export. Confirm whether the authenticated owner's searches, collections, and saved memberships exceed 100,000 combined rows or 128 MiB of exact UTF-8 JSON; do not increase either limit during incident response.
2. For a timeout, distinguish the backend 120-second transaction/query timeout, the frontend's 140-second backend response-header deadline, its separate 30-second non-success-body deadline, Caddy's five-second dial and 150-second privacy response-header deadlines, and the edge's 340-second request-to-final-byte write deadline. The database timeout does not interrupt a blocked servlet write. The frontend header timer must be cleared after backend headers; generic routes must remain at their shorter response-header timeout.
3. Check PostgreSQL connection pressure and long-lived repeatable-read transactions, then reproduce with an authorized synthetic account. Never copy a user's export into logs, monitoring, or an incident ticket.
4. An accepted response must be no-store, no-transform, nosniff, an attachment, carry an exact `Content-Length`, and have no `Content-Encoding`. Confirm that the BFF requested identity encoding and rejected an encoded backend response. Treat a truncated stream or length mismatch as a failed download; the user should retry only after the underlying capacity or connectivity issue is understood.
5. The 340-second Caddy write timeout begins after request headers and is listener-wide, not a privacy-route override or a post-response-header allowance. It budgets the privacy route's five-second dial, 150-second upstream-header ceiling, roughly 180 seconds for the body, and a small handler margin. If slow-client pressure affects other routes, preserve the 30-second generic upstream header waits and bounded application bodies, then add deployment-level connection/rate controls rather than silently extending the listener timeout again.

### Monitoring pipeline down

1. Check Prometheus target status, blackbox-exporter status, and target-file JSON syntax.
2. Validate the candidate Prometheus/alert files before reload.
3. Keep the last known-good configuration. Monitoring failure does not prove the application is down, so use independent probes.
4. Restore a tested notification route; the repository's placeholder Alertmanager receiver intentionally sends nothing.

### TLS renewal failure

1. Check certificate expiry, public DNS answers, inbound 80/443 reachability, ACME rate-limit responses, and Caddy data-directory writability.
2. Do not delete Caddy state during diagnosis; it contains account and certificate material.
3. If rotating to a manually issued emergency certificate, use an approved secret path and document removal after automatic issuance recovers.

### Provider outage or rate limit

1. Identify the isolated provider and distinguish timeout, rate limit, credentials, schema change, and legal/operator-disable states.
2. Leave the provider disabled when authorization or terms are uncertain.
3. Preserve partial results and cache behavior; never compensate by scraping, bypassing a paywall, or retrying without a bound.
4. Rotate a credential only after checking for exposure and quota impact. Test with the minimum supported request.

### Refresh job failures or backlog

1. Inspect the failed job through the scoped refresh-job API/UI and record its type, target, attempt count, safe error code, and lease timestamps without copying private query data into an incident channel.
2. Distinguish a terminal provider/configuration failure from expired-lease recovery, worker shutdown, database saturation, and an intentionally disabled worker or scheduler.
3. Retry only a terminal job whose cause is understood and retryable. Do not repeatedly retry legal, licence, target-validation, or permanent provider failures.
4. If queued work grows, pause scheduled enqueue, preserve interactive capacity, review provider quotas and database health, and resume only with an explicit bounded catch-up plan.

### MCP authentication or rate-limit failures

1. Inspect the bounded-cardinality rejection reason and request-rate metrics, then correlate only by the generated MCP request ID; never log or paste bearer values.
2. Distinguish a missing/duplicate credential, invalid Origin, wrong issuer/audience/scope, oversized request, and per-client rate limit before changing configuration.
3. For hosted failures, verify the external issuer/JWKS and client grant from an approved network. Do not weaken audience, scope, Origin, or token-expiry checks to restore access.
4. Treat sustained identity churn or rate-limit pressure as an abuse/edge-control incident; the in-process limiter is not an aggregate cluster budget.

### Suspected credential exposure

1. Restrict ingress if the database password, browser session-sealing secret, OIDC client secret, local-development MCP key, or provider key may be exposed.
2. Revoke/rotate at the issuing system first, then materialize the replacement secret and recreate only affected services.
3. Search sanitized audit sources for use of the old credential; do not place the old value in the search command.
4. Record scope, timing, affected data, notification duties, and follow-up controls.

Rotating the session-sealing secret invalidates every browser session. Rotate/revoke the OIDC confidential-client secret at the authorization server and coordinate access-token signing-key incidents with that provider; OpenScholar does not own the provider's signing keys. Production does not mount the local MCP key.

## Durable refresh jobs

`SEARCH_METADATA` and `PAPER_ACCESS` jobs are durable operational records. The worker and stale-target scheduler are both default-off; scheduled enqueue is invalid unless the worker is enabled. Enable them only after provider quotas, database capacity, attempt/backoff settings, and operator ownership are reviewed.

Jobs claim work with expiring tokened leases and recover abandoned `RUNNING` rows after lease expiry. Inspect recent `FAILED` rows and safe error codes before manual retry; do not repeatedly retry legal/configuration failures or compensate with unbounded provider calls. The queue deduplicates active type+target work globally. Its list/get/retry API is protected by `openscholar.jobs`: search-refresh visibility follows snapshot ownership, but shared-catalog `PAPER_ACCESS` rows are visible/retryable to all holders. Grant the scope with that mixed visibility in mind.

## Backup

`scripts/postgres-backup.sh` produces a PostgreSQL custom-format dump, validates its archive listing, optionally encrypts it with `age`, writes a SHA-256 sidecar, and performs no deletion or upload. The backup directory must be an absolute path and is forced to mode `0700`.

Run `scripts/production-compose.sh deploy/production.env --check` immediately before the backup. The backup script itself performs only `ps`/`exec` operations against the already running PostgreSQL service and does not create or pull a container.

Encrypted example:

```bash
BACKUP_DIR=/srv/openscholar-backups \
BACKUP_AGE_RECIPIENT='age1replacewithapprovedrecipient' \
COMPOSE_FILE="$PWD/deploy/compose.production.yaml" \
scripts/postgres-backup.sh
```

An unencrypted dump is permitted only with `ALLOW_UNENCRYPTED_BACKUP=true`; use that solely on storage already encrypted with an approved key. A database dump contains personal library/search metadata and must be treated as sensitive even though PDFs are not retained.

The SHA-256 sidecar detects accidental change; it is not a signature. `age` protects confidentiality and ciphertext integrity but its normal recipient mode does not establish which operator created a replacement archive. Keep the backup location write-restricted and add an approved signing/immutable-storage control when adversarial replacement is in scope. Restore only archives whose origin and custody are trusted.

Schedule the script through the host's supervised scheduler with a minimal service account. Alert on missed/failed runs. Configure remote encrypted replication and retention in the backup system, not by a broad deletion command in this repository. Record the chosen RPO/RTO and maintain at least one failure-domain-independent copy.

## Restore drill and disaster recovery

Restore is destructive. Prefer a new isolated PostgreSQL instance for routine drills. Validate the checksum, archive listing, Flyway history, row-level invariants, application readiness, representative searches/library reads, and access-link behavior before declaring the backup usable.

For an intentional in-place restore:

1. Confirm the exact host, Compose project, backup timestamp, checksum, and incident/change record.
2. Stop backend and frontend, leaving PostgreSQL running. Verify there are no other database clients.
3. Make an additional current backup when the database is readable.
4. Run the restore with the exact confirmation phrase:

```bash
scripts/production-compose.sh deploy/production.env stop backend frontend
RESTORE_FILE=/srv/openscholar-backups/openscholar-YYYYMMDDTHHMMSSZ.dump.age \
CONFIRM_RESTORE=restore-openscholar \
COMPOSE_FILE="$PWD/deploy/compose.production.yaml" \
scripts/postgres-restore.sh
```

The script verifies the checksum, decrypts to a mode-`0600` temporary file when needed, validates the archive, refuses to proceed while backend/frontend run, and uses `dropdb` without force. Active database connections therefore stop the restore instead of being terminated automatically. It creates a fresh database, restores with `--exit-on-error`, performs a basic SQL check, and leaves application services stopped.

5. Review `flyway_schema_history` and application compatibility before restart.
6. Run `scripts/production-compose.sh deploy/production.env up -d backend frontend proxy`, wait for readiness, and complete the smoke checks.
7. If restore fails after the database has been recreated, keep the application stopped and restore the last verified backup; do not run Flyway repair without a migration-specific investigation.

The script never deletes the source backup and never starts services.

## Release, rollback, and database migration

- Record old/new image digests and migration versions.
- Back up and restore-test before schema change.
- Deploy backend before declaring the release healthy; a running process is not equivalent to readiness.
- Roll back an image only when its schema compatibility is known.
- Never edit an applied Flyway migration. Use a new forward migration and a reviewed recovery plan.
- Keep optional providers, hybrid ranking, and background workers default-off until separately verified in the target environment.
- For every offline shell, reader-runtime, worker, or cache-policy change, bump the matching reader revision in all five frontend literals: the worker, HTML shell, public runtime, runtime loader, and runtime type contract. Never publish changed worker behavior under an active cache revision; installation fails closed when that revision is already owned by the incumbent.
- For a frontend release or schema-compatible rollback, verify `/sw.js` revalidation, network-first fixed install assets, the active cache version, the cache-only required neutral fallback/reader pair, complete update installation under a new reader revision, the 96-entry `/_next/static/` bound, a fresh browser profile, and an already-controlled profile. Publish rollback behavior as another uniquely versioned forward worker when possible. A coherent candidate must remain waiting while an incumbent tab is open, activate after every controlled client closes, and control the reopened client without deleting unrelated browser caches or the encrypted IndexedDB pack. Do not force `skipWaiting`.
- The checked-in release fixture proves those lifecycle and current-schema compatibility invariants with synthetic revisions. It does not prove that a historical production image, JavaScript bundle, or database/schema combination can be rolled back; retain and test the exact proposed artifacts before approving a real rollback.
- Non-production cleanup may unregister only the exact same-origin OpenScholar `/sw.js` registration and delete only `openscholar-shell-*` caches. Never use blanket service-worker or CacheStorage deletion as a troubleshooting step on a shared origin.

## Evidence to retain

Retain sanitized release manifests, image/SBOM digests, vulnerability decisions, backup checksums, restore-drill results, alert tests, uptime evidence, incident timelines, and credential-rotation timestamps according to the approved retention policy. Do not retain raw secrets, authorization headers, full provider payloads, or document bytes in operational evidence.
