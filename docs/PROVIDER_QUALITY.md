# Provider Quality Evaluation

## Purpose and decision boundary

Provider-quality evaluation is engineering evidence for deciding whether an optional discovery adapter improves OpenScholar's search mechanics and coverage. It is not a product analytics feature: no quality scores, evaluation controls, or operational metrics are shown in the reader UI or exposed through REST or MCP.

Europe PMC remains disabled by default. Passing an evaluation does not change `EUROPE_PMC_ENABLED`, authorize new routes, establish article rights, or make the adapter a default source. The evaluation has two deliberately separate evidence layers:

| Layer | Execution | What it can show | What it cannot show |
|---|---|---|---|
| Synthetic mechanics gate | Deterministic, PR-gated Spring Boot/Testcontainers test with no live scholarly-provider or external API calls | Real catalog/snapshot persistence, exact-signal reconciliation, fusion, metric calculation, and regression stability | Live relevance, current provider coverage, schema stability, quota behavior, or audience usefulness |
| Live metadata capture | Explicit operator opt-in against a running backend | Time-stamped fused first-page metadata and provider-request diagnostics for a reviewed query set | Isolated OpenAlex-versus-Europe-PMC-versus-fused quality metrics, raw pre-reconciliation false-merge auditing, a representative corpus, durable quality, document access, reuse rights, or permission to enable the provider by default |

## Deterministic pull-request gate

`EuropePmcProviderQualityEvaluationTests` runs the versioned synthetic provider-quality fixture through the actual `PaperCatalog` and `SearchSnapshotStore` persistence paths. It compares the frozen OpenAlex-only baseline with the OpenAlex-plus-Europe-PMC candidate, including the same exact-identifier reconciliation and reciprocal-rank-fusion mechanics used by application search. Frozen synthetic records, judgments, timestamps, provider ranks, and policy thresholds make the result repeatable; the evaluation makes no live scholarly-provider or other external API call and contains no copied paper text or PDF. It still runs PostgreSQL through Testcontainers and Docker networking, and environment bootstrap may download Maven dependencies or a container image.

`backend/src/test/resources/search/provider-quality/provider-fusion-development-v1.json` is a varied, bounded synthetic development corpus. Its query groups differ in provider result counts, relevance distribution, overlap shape, and metadata completeness while covering provider-unique relevant records, hard negatives, exact DOI/PMID/PMCID overlaps, and must-separate pairs with graded `0..3` judgments. DOI examples use Crossref's test prefix; PMID/PMCID values use a loader-enforced high fixture range; and author/serial placeholders use unique URLs below the reserved `fixtures.openscholar.test` host, so the corpus never attributes fictional works to live registry records. The SHA-256-bound `provider-fusion-policy-v1.json` freezes the evaluation-only source policy, maximum of ten records per provider/query, reciprocal-rank-fusion constant `k=60`, deterministic tie-break, cutoffs, metadata fields, and regression gates. Changing the fixture therefore requires an explicit policy and digest review rather than silently moving the gate.

Run only this gate from the repository root:

```bash
cd backend && ./mvnw --batch-mode --no-transfer-progress -Dtest=EuropePmcProviderQualityEvaluationTests test
```

The gate records and checks:

| Metric | Meaning in this evaluation |
|---|---|
| Recall@20 | Fraction of judged-relevant canonical works present in the first 20 results |
| nDCG@10 | Graded relevance and ordering quality in the first 10 results |
| Precision@5 | Fraction of the first five canonical results judged relevant |
| MRR | Reciprocal rank of the first relevant result, evaluated within the first 20 results |
| Europe-PMC-unique relevant query coverage | Number of fixture query groups containing at least one judged-relevant canonical work present in the Europe PMC input but absent from the OpenAlex input |
| Exact-signal deduplication | Pairwise precision/recall/F1 for records that should reconcile through exact DOI, PMID, or PMCID evidence, with must-separate cases retained |
| Metadata completeness | Per-field and aggregate presence for the bounded bibliographic fields declared by the fixture; more filled fields do not override identity or relevance judgments |

The gate also requires a positive candidate coverage gain for every frozen optional enrichment field. Populated metadata must survive for provider-unique records; for reconciled records, author identity comes from one canonical provider record, and the evaluator does not infer that same-name authors across providers are the same person.

The fixture policy owns the numeric regression floors and exact comparison rules. Tests should fail when those frozen mechanics regress, not when an external provider changes. Provider HTTP-contract tests separately prove Europe PMC's `/search`-only, `SRC:MED` journal-article, PMC-held, metadata-only boundary.

Synthetic data is intentionally small and authored to exercise known cases. A green PR gate proves deterministic integration mechanics; it cannot prove that Europe PMC helps real readers, topics, languages, or disciplines.

## Opt-in live metadata capture

Live evaluation against real services is a manual, non-gating evidence-gathering step. Pull requests and the clean-clone verifier instead exercise the capture utility end to end against loopback-only fake application and management servers; that test makes no live scholarly-provider or other external API call and retains no report. To gather real evidence, start an explicitly enabled backend using `EUROPE_PMC_ENABLED=true`, then run from the repository root:

```bash
node scripts/capture-europe-pmc-quality.mjs --base-url http://127.0.0.1:8080
```

When application and management traffic use different private origins, pass both explicitly. The second origin must expose only the private Actuator endpoint to the approved operator; `9091` below may instead be a loopback-bound port-forward or tunnel:

```bash
node scripts/capture-europe-pmc-quality.mjs \
  --base-url http://127.0.0.1:8080 \
  --management-url http://127.0.0.1:9091
```

The default query set is `backend/src/test/resources/search/provider-quality/europe-pmc-live-queries-v1.json`: eight author-written, initially unjudged topics with a page size of 20. The capture makes one forced ONLINE first-page search per topic and uses search/metadata flows only. It does not call access verification, request or download PDFs, call `fullTextXML`, fetch supplementary files, or use Europe PMC bulk-download routes. Europe PMC's open-access field remains an unverified provider hint.

Run the capture against an isolated evaluation backend with exactly OpenAlex and Europe PMC active; disable DataCite, DOAJ, and CORE, and do not send concurrent search traffic. Every response must repeat the requested query text, report `requestedMode=ONLINE` and `executionSource=PROVIDER_FETCH`, and use the valid forced-fetch disposition `MISS_FETCHED` or `FORCED_REFRESH`. The script rejects provider coverage other than exactly `OPENALEX` plus `EUROPE_PMC`, and it rejects telemetry unless each provider made exactly eight requests—one for each frozen query. Failure counts must match response coverage, timer samples must match requests, result samples must match successful requests, and returned-record totals must match the summed coverage entries. This prevents cached or mismatched pages, another enabled adapter, an incomplete metrics scrape, or overlapping workload from contaminating the evidence.

Search JSON and Prometheus bodies are streamed with 16 MiB and 8 MiB ceilings respectively, and retained strings and arrays have separate semantic bounds. A query is also rejected if it repeats a canonical paper ID or maps the same provider record to multiple canonical results, because either case would make blinded labels ambiguous. Recorded search latency covers response headers, bounded body consumption, UTF-8 decoding, and JSON parsing rather than stopping when headers arrive.

The script accepts loopback application and management targets by default. Pointing either origin at a remote service requires HTTPS, the deliberate `ALLOW_REMOTE_PROVIDER_QUALITY_TARGET=true` override, and a separate privacy, authorization, and environment review. An authenticated application origin may receive a bearer token through `OPENSCHOLAR_PROVIDER_QUALITY_BEARER_TOKEN`; a separately authenticated management origin may use `OPENSCHOLAR_PROVIDER_QUALITY_METRICS_BEARER_TOKEN`. Keep both tokens out of command history and captured evidence.

Production Compose keeps Actuator on the private, un-published `backend:9091` listener, and Caddy deliberately has no route to it. Never publish or proxy Actuator merely to run this capture. Use a loopback-bound private tunnel or port-forward from an approved environment, run the capture within the private monitoring boundary, or use a purpose-built isolated evaluation backend. Enabling the remote-target override does not enable Europe PMC on the target and does not relax the adapter's route or document-handling policy.

Each capture writes `summary.json`, `blinded-candidates.json`, and `provenance-map.json` below a time-stamped, ignored `backend/target/provider-quality/<timestamp>/` directory. The summary contains aggregate provider contribution, exact-DOI collision, latency, failure, and returned-record telemetry deltas gathered from the private engineering surface. Candidate metadata is separated from provenance so relevance can be graded `0..3` without showing the reviewer which provider contributed a record. Keep the provenance map hidden until that labeling is complete.

Every search response is the already fused, canonical first page. Consequently, this capture cannot compute isolated OpenAlex-only, Europe-PMC-only, and fused Recall/nDCG/precision deltas, and it cannot inspect raw provider candidates that were merged or discarded before the response. Shared provenance and repeated-DOI counts are useful diagnostics, not a false-merge audit. Blinded labels on the fused page do not close either evidence gap.

A partial provider failure still produces diagnostic evidence, sets `qualityReviewEligible=false`, records bounded `captureIssues`, removes the relevance-labeling instruction, and exits with status `2`. Only a clean capture with both providers successful for all eight queries is eligible for blinded relevance review; repeat an incomplete capture instead of labeling or averaging it into the evidence set.

These reports are local engineering artifacts, not reader-facing product metrics or files to commit automatically. Reviewers should retain only approved, minimized evidence according to the intended deployment's provider, privacy, and retention policy.

## Default-enablement evidence

Neither the development fixture nor the current fused-page live capture is sufficient to enable Europe PMC by default. A default change requires all of the following before the normal configuration is reconsidered:

1. A future bounded evaluator that captures isolated OpenAlex, isolated Europe PMC, fused results, and the raw candidate/reconciliation evidence needed to measure comparative retrieval quality and audit false merges without fetching documents.
2. An independently authored, disjoint holdout with queries, relevance judgments, duplicate clusters, must-separate cases, and metadata expectations frozen before that evaluator scores it.
3. Passing results on the holdout without tuning the candidate against its labels.
4. Multiple time-separated clean live diagnostics over a reviewed, audience-relevant query set, with changes in returned coverage, fused-page relevance, provider contribution, latency, failures, and provider behavior reviewed rather than averaged away.
5. Reconfirmation of provider terms, privacy disclosure, quotas, attribution, and the metadata-only/no-document boundary for the intended deployment.
6. An explicit maintainer decision that records the evidence and changes the default separately.

Until those conditions are met, Europe PMC remains an optional, default-off adapter even when the synthetic gate passes.
