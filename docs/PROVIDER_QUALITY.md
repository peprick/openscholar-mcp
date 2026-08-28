# Provider Quality Evaluation

## Purpose and decision boundary

Provider-quality evaluation is engineering evidence for deciding whether an optional discovery adapter improves OpenScholar's search mechanics and coverage. It is not a product analytics feature: no quality scores, evaluation controls, or operational metrics are shown in the reader UI or exposed through REST or MCP.

Europe PMC remains disabled by default. Passing an evaluation does not change `EUROPE_PMC_ENABLED`, authorize new routes, establish article rights, or make the adapter a default source. The evaluation has six deliberately separate evidence layers:

| Layer | Execution | What it can show | What it cannot show |
|---|---|---|---|
| Synthetic mechanics gate | Deterministic, PR-gated Spring Boot/Testcontainers test with no live scholarly-provider or external API calls | Real catalog/snapshot persistence, exact-signal reconciliation, fusion, metric calculation, and regression stability | Live relevance, current provider coverage, schema stability, quota behavior, or audience usefulness |
| Fused-page live diagnostic | Explicit operator opt-in against a running backend | Time-stamped fused first-page metadata and provider-request diagnostics for a reviewed query set | Isolated provider quality, raw pre-reconciliation false-merge auditing, durable quality, or permission to enable the provider by default |
| Comparative raw-candidate capture | Explicit operator opt-in in an isolated Testcontainers evaluation context | One live metadata fetch per provider/query, identical-result replay through isolated and fused production persistence, and every raw-to-canonical decision before page truncation | Ground-truth relevance or identity, representative audience usefulness, document access, reuse rights, durable quality, or permission to enable the provider by default |
| Blinded review packet and worksheet | Explicit local projection from a verified comparative capture, followed by independent human review and strict compilation | A provenance-free, digest-bound labeling surface and a canonical judgment packet for the scorer | Independent judgments when the reviewer saw hidden evidence, a disjoint holdout by itself, any quality score, or permission to enable the provider by default |
| Offline comparative scoring | Explicit operator opt-in over a verified capture and independently supplied judgments | Frozen cluster-aware ranking, reconciliation, expected-field, and provider-unique-coverage measurements bound to exact input digests | Representative judgments when the packet is not a disjoint holdout, durable quality, a product metric, or permission to enable the provider by default |
| Longitudinal retained-run comparison | Explicit local opt-in over two through sixteen fully verified and semantically replayed compatible run seals | Exact chronological snapshots and adjacent changes without averaging away reversals or undefined values | A trusted timestamp, statistical trend, improvement/regression label, pass/fail result, reviewer-independence proof, or permission to enable the provider by default |

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

## Opt-in comparative raw-candidate capture

`EuropePmcComparativeLiveEvaluationTests` closes the structural gap in the fused-page diagnostic without adding a production route or a reader-facing metric. It loads the same SHA-256-bound eight-query set, selects exactly the OpenAlex and Europe PMC provider beans, and calls each provider once per query. It then replays those same immutable metadata results—not three new live searches—through rollback-only OpenAlex-only, Europe-PMC-only, and fused `SearchSnapshotStore` transactions.

The evaluation trace is recorded immediately after canonical upsert and before same-provider contribution collapse, ranking, or page-size truncation. It therefore includes every bounded raw candidate, including a record merged into another canonical paper or omitted from the displayed first page. Generated database UUIDs never become evidence identifiers: query-set/query-scoped SHA-256 review keys identify raw records consistently across time-separated captures, while stable cluster keys are derived from the sorted raw keys assigned to each scenario cluster.

Run it only from a fresh or disposable clean committed checkout with Docker available. The runner resolves `HEAD`, requires the revision variable to match it exactly, and refuses any unignored worktree change. Use `clean test` so ignored output from an older checkout cannot supply evaluator bytecode; because Maven `clean` removes earlier captures below `backend/target/`, move any approved evidence to its controlled retention location before another run:

```bash
cd backend
RUN_PROVIDER_QUALITY_COMPARATIVE_CAPTURE=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeLiveEvaluationTests \
  clean test
```

The gated test starts a disposable PostgreSQL container, explicitly enables Europe PMC, keeps other optional discovery providers disabled, and never starts a web server. Both provider calls for a query finish before that query's persistence replay begins. Every scenario runs in a new rollback-only transaction, and the runner refuses a nonempty evaluation catalog before or after capture. Normal Maven verification skips the class before Spring context creation, so pull requests and CI make no live scholarly-provider call.

The runner uses only the provider adapters' bounded first-page search methods. It never invokes legal-access verification, dereferences source or PDF links, downloads documents, requests Europe PMC `fullTextXML`, fetches supplementary material, or calls bulk routes. The retained raw projection is a closed bibliographic allowlist: it omits PDF URLs, the untrusted landing-page field, raw provider fragments, credentials, exception causes, and database UUIDs. It retains a bounded, absolute, host-bearing provider source URL only when it uses HTTP(S) without credentials, query, or fragment; that provenance URL may be the same public article page a provider also reports as its landing page. The live test pins both official HTTPS provider endpoints, disables the OpenAlex API key, and records the non-secret endpoint/cap configuration in `summary.json`. Output is capped at 64 MiB and written with create-new semantics below ignored `backend/target/provider-quality/`; directories use mode `0700` and files `0600` where POSIX permissions are available.

Each successful run writes `summary.json`, `blinded-candidates.json`, `provenance-map.json`, `reconciliation-trace.json`, and a per-file SHA-256 `manifest.json`. The four payload documents use schema version `2`; ranked scenario results contain only the presence or absence of bounded canonical metadata fields, never the corresponding canonical values. The manifest envelope remains schema version `1`. An earlier payload shape is not silently upgraded for scoring. The separate packet generator below projects the review-safe subset; do not hand a reviewer any raw capture file. A provider failure still produces a diagnostic artifact with `qualityReviewEligible=false`, after which the test fails so it cannot be mistaken for review-ready evidence.

The comparative capture supplies candidates and candidate decisions, not labels or scores. An independent reviewer must grade relevance and separately adjudicate duplicate clusters, must-separate pairs, and metadata expectations before offline comparisons can be computed. The repository contains no real judgment packet and does not claim an independent provider holdout; this workflow must not author one after seeing provenance or scenario results.

## Blinded review packet and worksheet

Generate the independent-review files only from an approved, review-ready comparative capture. Keep the capture outside `backend/target/` before this command: Maven `clean` deletes that directory, including previous captures, review packets, worksheets, judgments, and score reports.

```bash
cd backend
RUN_PROVIDER_QUALITY_COMPARATIVE_REVIEW_PACKET=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_EVIDENCE=/absolute/evidence-dir \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeReviewPacketGenerationTests \
  clean test
```

`EuropePmcComparativeReviewPacketGenerationTests` runs the scorer's full evidence preflight, verifies the capture revision, and then writes a private, ignored `review-packet.json` and incomplete `review-worksheet.json` below `backend/target/provider-quality/`. The packet contains one opaque session binding, safe query text, neutral packet-local candidate keys, and the blinded bibliographic metadata needed for review. Author entries expose only display names, and language values are normalized to a canonical ISO-639-3 form. It exposes neither the stable internal review keys nor the campaign-bearing evidence/query-set/policy identifiers and digests. It also omits candidate-to-provider assignments, raw provider-specific author positions, source-specific corresponding-author flags, provider rank, identifiers, citation count, open-access values, URLs, provenance, reconciliation decisions, and scenario results.

Give the independent reviewer **only** `review-packet.json` and `review-worksheet.json`. Never give that reviewer `provenance-map.json`, `reconciliation-trace.json`, scenario results, or another file from the raw capture. Treat `review-packet.json` as immutable and preserve its exact bytes for compilation and scoring. The reviewer completes the worksheet without changing its packet binding, query order, candidate order, query keys, or packet-local `candidateKey` values:

1. Replace every candidate `goldPaperKey`, `relevanceGrade`, and `expectedFields` null. Use the same safe lowercase `goldPaperKey` for rows judged to be the same work; rows in that group must use the same `0..3` relevance grade and the same expected fields.
2. Replace each `expectedFields` null with a JSON array. `[]` means the reviewer explicitly expects none. Otherwise use only `ABSTRACT`, `AUTHORS`, `CITATION_COUNT`, `DOCUMENT_TYPE`, `DOI`, `ISSN`, `LANGUAGE`, `ORCID`, `PMCID`, `PMID`, `PUBLICATION_YEAR`, `SOURCE_URL`, `TITLE`, and `VENUE`, sorted lexicographically with no duplicate.
3. Record must-separate pairs only within their query and between different gold works. Put the lexically smaller candidate key in `leftCandidateKey` and the other in `rightCandidateKey`; keep both each pair and the pair array in canonical ascending order, with no duplicate. Use a safe uppercase `reasonCode`, for example `DISTINCT_WORKS`.
4. Set every query's `mustSeparateReviewComplete` to `true`, including when `mustSeparatePairs` is empty.
5. Replace the root attestation null with `AUTHORED_WITHOUT_PROVENANCE_OR_SCENARIO_OUTPUT` only when that statement is true. If it is not true, discard the review rather than making the attestation.

Compile the completed worksheet in the same clean committed revision. Preserve the verified evidence directory, exact immutable review packet, and worksheet outside `backend/target/` before running `clean test`:

```bash
cd backend
RUN_PROVIDER_QUALITY_COMPARATIVE_REVIEW_COMPILE=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_EVIDENCE=/absolute/evidence-dir \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_REVIEW_PACKET=/absolute/review-packet.json \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_WORKSHEET=/absolute/review-worksheet.json \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeReviewWorksheetCompilationTests \
  clean test
```

`EuropePmcComparativeReviewWorksheetCompilationTests` reruns the full evidence preflight, regenerates the expected packet, verifies the supplied packet's exact bytes, and rejects incomplete, reordered, malformed, duplicate, or mismatched worksheet content. It translates the neutral candidate keys back to hidden scorer keys only while producing a canonical `provider-quality-independent-judgments-v2` `judgments.json`; that file retains the exact review-packet SHA-256 and evidence/query-set/policy bindings. Retain it in the approved external evidence location and pass its absolute path to the existing scorer. Packet generation and worksheet compilation start no Spring context, Docker container, web server, or provider request; they use no network or PDF and add no UI metric or default-enablement behavior. No completed worksheet, real label, or real `judgments.json` belongs in Git.

## Manual offline comparative scoring

`EuropePmcComparativeOfflineScoringTests` is a manual, local evaluator rather than an application test path. It does not start Spring, PostgreSQL, Testcontainers, Docker, or a web server; it makes no network call, reads no PDF, and adds no UI, REST, MCP, Actuator, or provider-default surface. Ordinary verification skips it unless the explicit scoring gate is set. Its score-report protocol is version `2`: the report identity uses a versioned derivation domain, and `score-summary.json` carries the capture's canonical `captureMeasuredAt` value alongside the capture manifest and repository revision.

The first input boundary accepts exactly five regular, non-symlink files: `manifest.json`, `summary.json`, `blinded-candidates.json`, `provenance-map.json`, and `reconciliation-trace.json`. The strict verifier enforces the 64 MiB aggregate payload bound, the bounded manifest, declared byte counts and per-file SHA-256 digests, strict JSON parsing, payload schema version `2`, the common evidence ID, the manifest's schema version `1`, and consistent review-ready status. The scorer additionally verifies the exact reviewer-visible projection and the evidence-scoped shuffled candidate order, so provider-batched ordering cannot silently unblind a review. Extra, missing, substituted, oversized, malformed, digest-mismatched, reordered, or cross-evidence files fail before scoring.

The separately supplied judgment packet is also strict and bounded. It names the exact evidence ID and manifest SHA-256, query-set ID and SHA-256, and frozen scoring-policy ID and SHA-256. Its required independence attestation states that the judgments were authored without the provenance map or scenario output. Eligible evidence must carry the exact blinded-review instruction that forbids consulting the provenance map or reconciliation trace. Query keys and candidate review keys must form the expected unique partition, review keys are recomputed from their query-set/provider identity, and relevance grades, duplicate groups, must-separate pairs, expected metadata fields, and hidden provenance values must satisfy the closed bounded schema. The checked-in tests use only inline synthetic packets; the repository supplies no real labels.

The frozen `provider-comparative-scoring-policy-v1` measures each OpenAlex-only, Europe-PMC-only, and fused scenario as follows:

| Measurement | Frozen interpretation |
|---|---|
| Recall@20, nDCG@10, Precision@5, MRR@20 | A predicted cluster receives credit for at most one previously uncredited adjudicated work: the highest-grade eligible work, with ascending gold-paper key as the frozen tie-break. This prevents one false merge from earning multiple ranking credits and prevents split results from repeatedly earning the same credit. Recall, nDCG, and MRR are explicitly undefined when a query has no relevant judged candidate and are excluded from those relevance macros with the excluded-query count reported; Precision@5 remains `0` and remains in its macro. |
| Pairwise deduplication | Precision, recall, and F1 are measured within each query from adjudicated review-key pairs and then aggregated; unrelated candidates from different queries do not become artificial true negatives. Counts are always reported. Precision is undefined when `TP + FP = 0`, recall is undefined when `TP + FN = 0`, and F1 is undefined when either input rate is undefined. This makes zero-pair and all-negative pair sets explicit rather than reporting invented perfect rates. |
| Must-separate violations | An adjudicated must-separate pair is a violation when both candidates occur in a scenario and the scenario places them in the same predicted cluster. |
| Expected-field recovery | For a credited work, the scorer compares the independently expected bounded fields with the capture's presence bits. It does not recover, serialize, or judge canonical metadata values. |
| Europe-PMC-unique relevant-query coverage | Counts judged-relevant query groups with a Europe PMC candidate for an adjudicated work and no OpenAlex candidate for that work. |

Retain the approved capture in its controlled external evidence location before running the command below. Maven `clean` deliberately removes all earlier ignored captures and reports under `backend/target/`:

```bash
cd backend
RUN_PROVIDER_QUALITY_COMPARATIVE_SCORING=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_EVIDENCE=/absolute/evidence-dir \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_REVIEW_PACKET=/absolute/review-packet.json \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_JUDGMENTS=/absolute/judgments.json \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeOfflineScoringTests \
  clean test
```

With the same required inputs, set `OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_SCORE_REPORT` to replay-verify a retained report without creating another one. The runner rejects lexical and resolved aliases into `backend/target/`; the report directory must be an existing absolute path outside that tree because the required `clean` removes it before the test starts:

```bash
cd backend
RUN_PROVIDER_QUALITY_COMPARATIVE_SCORING=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_EVIDENCE=/absolute/evidence-dir \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_REVIEW_PACKET=/absolute/review-packet.json \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_JUDGMENTS=/absolute/judgments.json \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_SCORE_REPORT=/absolute/external/report-directory \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeOfflineScoringTests \
  clean test
```

The scorer keeps successful empty queries and all-irrelevant adjudications visible through the explicit undefined/count semantics above; they are not silently discarded after labeling. Before scoring it repeats the full evidence preflight, regenerates the expected review projection, verifies the supplied packet's exact bytes, and rejects a judgment whose `reviewPacketSha256` does not match that verified packet. The runner also rejects a dirty checkout, a claimed revision different from `HEAD`, scorer code at a revision different from the capture revision, or a query-set ID, SHA-256, or ordered key list that differs from the digest-checked frozen resource at that revision.

Generation publishes a private ignored report from a create-new staging directory below `backend/target/provider-quality/`, using an atomic move where the filesystem supports one, and then reopens it through the same verifier used for replay before printing success. The verifier accepts exactly three real, non-symlink files—`manifest.json`, `query-scores.json`, and `score-summary.json`—within the 8 MiB total bound. It requires the canonical manifest schema and bytes, exact sizes and SHA-256 digests, strict JSON without duplicate or trailing content, the recomputed v2 report identity, and byte-for-byte equality with freshly regenerated score artifacts. Replay performs that same no-write check on a separately retained directory. A failed post-publication verification leaves the ignored directory available for diagnosis and prints no success record.

Replay is a local integrity check, not a hostile-filesystem isolation boundary. Use only an operator-controlled directory whose files and ancestors cannot be replaced or modified concurrently; the verifier rereads accepted bytes but cannot lock an entire path against an adversarial rename race. The success record deliberately omits the environment-supplied path and includes only bounded integrity handles.

The score-report boundary alone does not retain its separately governed evidence, immutable review packet, completed worksheet, and judgment inputs. The optional run-seal promotion below copies those exact verified bytes into one local integrity-linked bundle. One score report or run seal still does not authenticate an operator, enforce external custody or retention, compare time-separated runs by itself, or make an enablement decision. The separate longitudinal workflow can compare a compatible cohort, but none of these artifacts is a reader-facing metric or an automatic pass/fail or default-enablement decision. Maintainers must still review the evidence alongside the independent-holdout, time-separation, provider-policy, privacy, and legal requirements below.

## Local run-seal promotion

The existing `EuropePmcComparativeOfflineScoringTests` runner has a local, file-only promotion mode. It is selected only when both of the following are supplied; either variable on its own is rejected:

```bash
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_WORKSHEET=/absolute/completed-worksheet.json
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_RUN_SEAL_ROOT=/absolute/operator-controlled-root
```

Add these variables to the existing scoring command and its existing evidence, reviewed-packet, judgment, revision, and optional retained-score-report inputs. When the retained-score-report variable is absent, the runner generates and verifies the score report first and promotes it in that same invocation, before a later Maven `clean` can remove it.

The run-seal root must already exist, be absolute, be a real non-symlink directory, and resolve outside the repository. The promoter does not create or change the policy of that parent. On POSIX filesystems the root must already grant all owner directory permissions and no group or other permissions—normally `chmod 700 /absolute/operator-controlled-root`. Published directories are `0700` and files are `0600`, and verification rejects a less-private POSIX tree. The workflow assumes an operator-controlled filesystem on which the root, ancestors, staging directory, and final directory cannot be replaced or modified concurrently.

Under that assumption the promoter creates a private create-new staging directory and files, requires an atomic move to publish the final run directory, and fails without publication when the filesystem cannot provide that atomic boundary. It never replaces an existing run directory. A retry whose deterministic run ID already exists succeeds idempotently only after exact verification proves that the existing directory is the expected bundle; any mismatch fails.

The canonical v1 bundle layout is exact:

```text
<runId>/
  run-seal.json
  capture/<evidenceId>/
    manifest.json
    summary.json
    blinded-candidates.json
    provenance-map.json
    reconciliation-trace.json
  review/
    review-packet.json
    completed-worksheet.json
    judgments.json
  score/<reportId>/
    manifest.json
    query-scores.json
    score-summary.json
```

The 11 retained payload files are bounded to 153,157,632 bytes in total, `run-seal.json` is bounded to 65,536 bytes, and the complete bundle is bounded to 153,223,168 bytes. Existing component limits still apply: the four capture payloads share 64 MiB and the capture manifest has 64 KiB; the review packet has 72 MiB; the completed worksheet and judgments have 1 MiB each; and all three score files together have 8 MiB.

The canonical `provider-quality-comparative-run-seal-v1` document uses a versioned deterministic derivation domain and binds the repository revision and capture time; evidence, query-set, scoring-policy, review-packet, exact completed-worksheet, canonical-judgment, and score-report identities; and a sorted relative-path, byte-count, and SHA-256 inventory. Before promotion, the runner compiles the exact completed worksheet and requires its canonical judgment bytes to equal the supplied judgments. After the publisher atomically publishes and exactly verifies the final bundle, the runner performs the full semantic replay from the promoted bytes: strict capture verification and preflight, review-packet regeneration and exact comparison, worksheet compilation, exact judgment comparison and loading, rescoring to the same result, exact score-report verification, and final run-seal verification. Success is printed only after that replay and contains only the scoring mode, deterministic run-seal ID, run-seal SHA-256, and report ID; it contains no filesystem path, metric, query, label, or candidate value.

The promoted bundle is operator-only and combines hidden provenance, completed labels, judgments, and scores. Never give it to the independent reviewer; that reviewer continues to receive only `review-packet.json` and the worksheet. The local seal is an integrity relationship, not proof of who created it: SHA-256 is not authentication, a signature, a trusted timestamp, WORM or immutable storage, retention enforcement, access-control history, or confidentiality. The approved external location must independently provide any required encryption, access controls, signing, versioning/object lock, retention, deletion, and audit history. Promotion itself adds no cloud service, database, Docker requirement, UI, REST/MCP endpoint, PDF handling, longitudinal conclusion, reviewer-independence proof, or provider-enablement decision.

## Standalone retained run-seal verification

The standalone verifier reopens one promoted run from an operator-controlled external directory and performs a no-provider-quality-write exact and semantic replay:

```bash
cd backend
RUN_PROVIDER_QUALITY_COMPARATIVE_RUN_SEAL_VERIFY=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_RUN_SEAL_DIRECTORY=/absolute/external/provider-quality-comparative-run-seal-v1-... \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeRunSealVerificationTests \
  clean test
```

Exactly one retained run directory is supplied through `OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_RUN_SEAL_DIRECTORY`. It must be an existing absolute real, non-symlink directory that resolves outside the repository; the exact bundle layout and private-permission contract still apply. The checkout must be clean, `OPENSCHOLAR_PROVIDER_QUALITY_REVISION` must equal committed `HEAD`, and the capture revision bound by the run seal must equal that revision. Verification assumes that the directory, its files, and its ancestors are operator-controlled and cannot be replaced or mutated concurrently.

The runner first verifies the exact canonical bundle, including its layout, limits, identities, sizes, SHA-256 inventory, and final seal. It then loads the frozen query set and scoring policy from the checked-out capture revision and repeats strict capture verification and preflight, review-packet regeneration and exact verification, completed-worksheet compilation, exact canonical-judgment comparison and loading, rescoring, exact score-report verification, recomputation of all seal bindings, and final run-seal verification.

The verifier itself creates no provider-quality artifact or bundle bytes and does not alter the retained directory. Its required Maven `clean test` invocation can create normal ignored build and test output under `backend/target/`. It uses no Spring context, Docker, network, PDF, UI, REST, or MCP path. Its only success output has this shape:

```text
provider-quality-comparative-run-seal-v1 mode=verified run-seal-id=<id> run-seal-sha256=<sha256> report-id=<id>
```

The record contains only the protocol, verification mode, run-seal ID, run-seal SHA-256, and report ID. It contains no path, revision, metrics, queries, labels, or candidate material. This result proves only local integrity and semantic replay under the stated filesystem assumption; it is not authentication, a signature, a trusted timestamp, WORM or immutable storage, confidentiality, retention or deletion enforcement, access history, or audit evidence. Never send the retained bundle to the independent reviewer, because it contains hidden provenance, completed labels, judgments, and scores.

## Local longitudinal comparison of retained runs

The longitudinal runner compares between two and sixteen independently retained run seals without contacting a provider or averaging observations into a trend. Every selected run must belong to one exact comparable cohort: the same clean repository revision, report schema, frozen query-set ID and SHA-256, ordered query keys, query count, scoring-policy ID and SHA-256, and scenario set. Run-seal, evidence, report, and canonical capture-time identities must be distinct. Judgments may and normally will differ between captures because each time-separated review is independent.

Create a private selection file outside the repository. Its exact schema is:

```json
{
  "schemaVersion": 1,
  "protocolId": "provider-quality-comparative-longitudinal-selection-v1",
  "runSealDirectories": [
    "/absolute/operator-controlled/run-seal-a",
    "/absolute/operator-controlled/run-seal-b"
  ]
}
```

The selection file must be an absolute real, non-symlink regular file and, on POSIX filesystems, have mode `0600`. Each entry must be an absolute real, non-symlink directory outside the repository. Resolved duplicates, ancestor/descendant pairs, and repository aliases are rejected. The input is bounded to 16 KiB and the selected directories are read without modification. Run the comparator from the exact clean revision bound by every seal:

```bash
cd backend
RUN_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_SELECTION=/absolute/private-selection.json \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeLongitudinalComparisonTests \
  clean test
```

For every selected directory, the runner first verifies the exact run-seal layout, identities, limits, permissions, digests, and inventory. It then repeats the complete semantic replay from the retained bytes using the frozen query set and policy at that revision, and performs a final exact seal verification. A report is published only after every run passes.

The deterministic comparison identity is derived from the chronologically ordered run-seal ID and run-seal SHA-256 pairs, never caller ordering or filesystem paths. Chronology uses each seal's canonical content-bound `captureMeasuredAt` value. That ordering is reproducible, but the declared value is not an externally trusted timestamp. Equal capture instants are rejected.

The private ignored output has exactly this layout below `backend/target/provider-quality/<comparisonId>/`:

```text
manifest.json
longitudinal-report.json
```

The complete bundle is bounded to 8 MiB, uses `0700`/`0600` permissions where POSIX permissions are available, and is reopened for strict JSON, exact layout, canonical-byte, size, digest, manifest, identity, and expected-content verification before success is printed. Maven `clean` deletes this directory, so move an approved report to its governed retention location before another clean run.

The report preserves each exact run snapshot and every adjacent transition, including denominator/context counts, undefined rates, aggregate scenario values, and per-query changes. It does not average captures, collapse reversals into one trend, calculate statistical significance, call a change an improvement or regression, apply a pass/fail threshold, or decide whether Europe PMC should be enabled. It contains operator-only metrics, integrity handles, and frozen evaluation query keys, but no end-user query, paper title, candidate metadata, completed label, provenance map, source-document location, PDF, credential, or filesystem path.

On success the runner prints only this bounded record:

```text
provider-quality-comparative-longitudinal-v1 mode=generated comparison-id=<id> manifest-sha256=<sha256> runs=<count>
```

Neither the report nor its metrics are exposed through the reader UI, REST API, MCP server, database, Spring application, Docker service, or ordinary application logs. Never send the report or any selected run seal to the independent reviewer. This local workflow uses no Spring context, Docker, network, scholarly-provider call, PDF/document handling, or runtime endpoint. Like the individual seals, it assumes operator-controlled non-concurrently-mutated paths and supplies no signature, trusted time, confidentiality, immutable retention, deletion enforcement, access history, or reviewer-independence proof.

## Standalone retained longitudinal-report verification

Move an approved generated report out of ignored `backend/target/provider-quality/` before another Maven clean, preserving its comparison-ID directory name and exact two-file layout. Keep the private selection separately. Then run the no-provider-quality-write verifier from the exact clean revision bound by every selected seal:

```bash
cd backend
RUN_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_REPORT_VERIFY=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_SELECTION=/absolute/private-selection.json \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_REPORT_DIRECTORY=/absolute/external/provider-quality-comparative-longitudinal-v1-... \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeLongitudinalReportVerificationTests \
  clean test
```

The selection retains the bounded, owner-private, real-file rules described above. The report variable must name an existing absolute real, final-component non-symlink directory that resolves outside the repository and does not contain it. Its basename must equal the comparison ID reconstructed from the selected runs; its exact `manifest.json` and `longitudinal-report.json` layout, 8 MiB bound, canonical bytes, identities, sizes, SHA-256 digests, and POSIX-private modes remain mandatory. Both inputs must already be external because `clean` deletes the ignored generated copy.

The verifier does not trust fields read from the report to choose an expected run set. It requires a clean committed checkout and the explicit exact revision, strict-loads the private selection, verifies every selected run-seal bundle, requires each capture revision to match the checkout, and repeats the complete semantic replay for every run. It constructs the canonical chronological comparison from those replayed results and passes only that trusted in-memory result to exact retained-report verification. A self-consistent report for a different, omitted, added, or changed cohort therefore cannot verify.

The verifier creates no provider-quality artifact or bundle bytes and does not alter the selection, any run seal, or the retained report. Its Maven invocation may still create ordinary ignored compiler and test output below `backend/target/`. No Spring context, Docker service, database, network, provider, PDF/document, UI, REST, or MCP path is involved. Success has only this privacy-allowlisted shape:

```text
provider-quality-comparative-longitudinal-v1 mode=verified comparison-id=<id> manifest-sha256=<sha256> runs=<count>
```

The success record contains no path, revision, capture time, individual seal/evidence/report identity, query, metric, label, candidate data, or byte count. Verification assumes locally operator-controlled files and ancestors with no concurrent replacement or mutation; path resolution and repeated reads do not isolate a hostile filesystem. It proves deterministic local integrity and semantic replay, not who created the artifacts, trusted chronology, signing, confidentiality, immutable or versioned retention, deletion enforcement, access history, audit evidence, reviewer independence, statistical significance, pass/fail status, or permission to enable a provider. Never send the retained report or selected seals to the independent reviewer.

## Default-enablement evidence

Neither the development fixture, the fused-page diagnostic, nor an unlabelled comparative capture is sufficient to enable Europe PMC by default. A default change requires all of the following before the normal configuration is reconsidered:

1. A clean artifact from the checked-in bounded evaluator containing isolated OpenAlex, isolated Europe PMC, fused results, and complete raw candidate/reconciliation evidence without fetching documents.
2. An independently authored, disjoint holdout with queries, relevance judgments, duplicate clusters, must-separate cases, and metadata expectations frozen before that evaluator scores it.
3. Passing results on the holdout without tuning the candidate against its labels.
4. Multiple time-separated clean live diagnostics over a reviewed, audience-relevant query set, with changes in returned coverage, fused-page relevance, provider contribution, latency, failures, and provider behavior reviewed rather than averaged away.
5. Reconfirmation of provider terms, privacy disclosure, quotas, attribution, and the metadata-only/no-document boundary for the intended deployment.
6. An explicit maintainer decision that records the evidence and changes the default separately.

Until those conditions are met, Europe PMC remains an optional, default-off adapter even when the synthetic gate passes.
