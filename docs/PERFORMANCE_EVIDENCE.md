# Local performance evidence

This page records a reproducible development-stack measurement. It is evidence for the local performance targets, not a production capacity claim or a substitute for deployment-specific load testing.

## Reference run

The run used the isolated Docker Compose E2E stack: Next.js and Spring Boot containers, PostgreSQL/pgvector, and the deterministic metadata-only OpenAlex fixture. The backend was addressed over loopback. One forced cold search was followed by 40 exact cached searches and 40 reads of the first canonical paper. Provider request/failure counters were read from `/actuator/prometheus` before and after the run.

Measured at `2026-08-21T21:52:28.770Z`:

| Signal | Result | Local target | Outcome |
|---|---:|---:|---|
| Forced cold search | 60.651 ms, `201/MISS_FETCHED`, 2 canonical results | Informational in this fixture | Recorded |
| Cached search p95 | 6.944 ms | < 500 ms | Pass |
| Cached search hit ratio | 40/40 (`1.000`) | Every repeated exact query | Pass |
| Paper-detail p95 | 7.305 ms | < 300 ms | Pass |
| Provider error rate | 0/1 (`0.000`) | Informational for deterministic run | Recorded |

The cold provider returned three records. Exact DOI reconciliation produced two canonical results, so the same run also exercises the provider-record-to-canonical-record boundary. Deduplication quality is evaluated independently in [Search quality](SEARCH_QUALITY.md); the count difference here is not itself a quality score.

## Reproduce

Start a backend on a loopback address with at least one deterministic or approved provider, then run:

```bash
node scripts/measure-local-performance.mjs \
  --base-url http://127.0.0.1:8080 \
  --topic "a unique performance topic" \
  --samples 40
```

The harness accepts 5–500 samples, emits versioned JSON, and exits nonzero if either local p95 threshold fails or any repeated request is not an exact cache hit. It rejects non-loopback targets unless `ALLOW_REMOTE_PERFORMANCE_TARGET=true` is explicitly set for an approved private environment. Use a unique topic when an unequivocal first `MISS_FETCHED` observation is required.

## Interpretation limits

- The fixture is synthetic and has no Internet latency, provider throttling, or large result corpus.
- This is a single-client latency check, not a throughput, saturation, soak, or capacity test.
- Host allocation was not normalized to a release benchmark shape.
- Real hosted acceptance still requires target-environment load testing with approved providers, privacy-safe telemetry, and documented resource limits.
