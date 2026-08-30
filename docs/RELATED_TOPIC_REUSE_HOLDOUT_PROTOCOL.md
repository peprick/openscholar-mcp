# Related-topic reuse blind-holdout protocol

## Status and purpose

This protocol preregisters the input shape and decision rules for a future
relevance decision on the experimental owner-scoped related-topic reuse
candidate. It freezes the candidate-policy and development-fixture bindings,
holdout shape, metric formulas, comparison rules, and acceptance gates before any
real holdout is accepted or scored. It does not add the candidate to REST, MCP,
the web UI, or a production search path.

The candidate remains evaluation-only. Its visible synthetic `DEVELOPMENT` labels
were used while choosing its seed rule, comparator bounds, and rank fusion. Those
results are useful regression evidence but cannot validate the choice that they
helped produce. The separate 100,000-paper scale diagnostic has no relevance
labels and cannot fill that gap.

Policy v1 binds the frozen candidate-policy resource and development fixture at
repository revision `6b22f6185d9c14a3dd0bf0a80a4b08c045396bff`. Source-seal and
canonical-report primitives were subsequently committed, but that intermediate
revision is not the evaluator freeze: it did not yet contain a canonical source
inventory, clean-checkout collector, durable ledger, or operator workflow. The
collector and ledger described below, plus the future operator, must be reviewed
and committed before the final runnable revision is frozen. The expected
revision and source SHA-256 values must then be calculated and retained
independently outside the tree they bind; embedding them in that same commit would
be self-referential. The collector also does not prove that evaluator bytecode was
built reproducibly from those sources. The external freeze must be checked from
its exact clean checkout before a custodian releases a real bundle. Until then,
external-bundle acceptance and custody release are explicitly unauthorized. Once
those prerequisites exist,
the first eligible external run is final for this policy version. A failure may
lead to a new development cycle and newly versioned holdout; it must not lead to
tuning against this holdout and rescoring it as though it were still blind.

## External bundle

No real holdout is checked into this repository. Confidential external custody is
an operator requirement, not a permission, ownership, encryption, or access-history
property enforced by this code. A future eligible input must remain in one absolute
directory containing exactly:

- `manifest.json` — bundle identity, frozen-policy binding, required procedural
  declarations, exact payload sizes, and SHA-256 digests;
- `holdout-corpus.json` — independently authored synthetic metadata, owner
  lineages, queries, and filters, with no judgments or candidate-output oracle
  fields; and
- `judgments.json` — independently authored `0..3` relevance grades and declared
  adversarial boundary annotations, bound to the exact corpus and policy.

The whole directory is limited to 1,048,576 bytes: `manifest.json` to 65,536,
`holdout-corpus.json` to 786,432, and `judgments.json` to 196,608 bytes. The
manifest `files` array must list `holdout-corpus.json` and `judgments.json`, in
that order, with their exact byte counts and lowercase SHA-256 digests. The
directory basename must equal `bundleId`.

Every document uses `schemaVersion: 1`, the protocol ID
`related-topic-reuse-holdout-bundle-v1`, the frozen policy ID and digest, the same
safe lowercase-hyphenated `bundleId`, and the same `corpusId`. The exact remaining
schema is:

- manifest: `payloadBytes`, ordered `files[{filename,bytes,sha256}]`, and
  `declarations{corpusAuthorship,judgmentAuthorship,firstRunRule,noRetuningRule,
  externalCustodyRule,evaluatorFreezeRule,limitations}`;
- corpus: `split`, `labelUnit`, `sourcePolicy`,
  `lineages[{key,kind}]`,
  `candidates[{key,lineageKey,title,abstractText,venueName,publicationYear,
  documentType,language,citationCount,reportedOpenAccess,authors}]`, and
  `queries[{key,text,kind,cutoff,filters}]`;
- each filter: `yearFrom`, `yearTo`, `documentTypes`, `openAccessOnly`,
  `minimumCitations`, and `languages`; nullable values must be explicit JSON
  `null` and empty dimensions must be explicit arrays; and
- judgments: `labelUnit` and
  `queries[{queryKey,grades,adversaries[{candidateKey,kind,reason}]}]`, where
  `grades` is an object from candidate key to integer grade.

Unknown or omitted fields fail. The accepted lineage kinds are
`TARGET_OWNER_SEARCH`, `TARGET_OWNER_COLLECTION`, `OTHER_OWNER_SEARCH`,
`OTHER_OWNER_COLLECTION`, and `CATALOG_ONLY`. Query kinds are
`LEXICAL_BRIDGE_OPPORTUNITY`, `FILTERED_LEXICAL_BRIDGE_OPPORTUNITY`,
`AUTHOR_NO_RELATED_SIGNAL_CONTROL`, and `NO_SEED_FALLBACK_CONTROL`.
Keys are 3–100 lowercase ASCII letters/digits separated by single hyphens;
languages are 2–8 lowercase ASCII letters. Text must be stripped NFC without
control characters. Titles are 3–300 characters, abstracts are null or 3–4,000,
venues are null or 2–300, and each candidate has 0–10 unique 2–200 character
authors. Years are null or `1000..9999`; citation counts are null or non-negative.
Document types are `ARTICLE`, `PREPRINT`, `CONFERENCE_PAPER`, `THESIS`,
`DISSERTATION`, `BOOK`, `BOOK_CHAPTER`, `REPORT`, `DATASET`, or `OTHER`.

The corpus must contain 8–20 queries and 40–200 candidates, including at least 30
target-visible candidates, five other-owner candidates, and five catalog-only
candidates. It must represent target search and collection history, other-owner
search and collection history, catalog-only records, opportunity and control
queries, a fully filtered query, and a no-seed control. Every declared lineage key
must be used by at least one candidate, and all five lineage kinds must be present.
Candidate keys, query keys, normalized query text, and normalized titles must also
be unique within the holdout. At least four queries must be opportunities and at
least three must be controls; all four query kinds must be represented, including
at least one fully filtered opportunity and one no-seed control. Every query must
judge exactly every target-visible candidate. Normalized comparisons apply NFKC,
locale-independent lowercase, whitespace-run collapse to one space, and stripping.

Grades are integers `0..3`. Every query needs at least one annotation with a
10–300 character reason. Accepted adversary kinds are `OWNER_VISIBLE_TOPIC_DRIFT`,
`OTHER_OWNER_TOPIC_MATCH`, `CATALOG_ONLY_TOPIC_MATCH`, `FILTER_VIOLATION`, and
`AUTHOR_SUBSTRING_COLLISION`; all five kinds must occur somewhere in the bundle.
Each opportunity needs at least two relevant candidates, including a grade `3`
and a separate grade `1` or `2`. Each author control has exactly one relevant
candidate at grade `3`; every no-seed control grades every target-visible candidate
`0`. A filtered opportunity must exercise all six filter dimensions and provide
six distinct target-visible grade-zero adversary annotations. Each annotated
candidate must fail exactly one dimension, satisfy the other five, and together
the six candidates must cover all six dimensions.

The preregistered declarations state that corpus and judgments were authored
without candidate outputs or development labels, that the first eligible run is
final, that failure cannot trigger same-holdout retuning, that the evaluator must
be frozen before custody release, and that real files stay external and
uncommitted. Candidate-output-independent annotations may identify
owner-visible topic drift, other-owner or catalog-only topic matches, filter
violations, and author-substring collisions. They may not encode an observed
candidate rank or score. Except for mechanically isolated filter violations and
lineage/grade/query-kind boundaries, the truth of those free-form annotations is
declared by the author and is not established by the verifier.

## Fail-closed intake

The test-only intake boundary rejects a bundle unless all of the following hold:

- the verifier can derive the actual Git worktree root from its current working
  directory, and the input resolves to a distinct tree with neither containing
  the other;
- the directory and its three files are real, bounded, non-symlink filesystem
  objects with the exact expected names;
- all JSON is strict: duplicate keys, trailing tokens, unknown fields, wrong
  types, unsafe identifiers, invalid ranges, and oversized documents fail;
- manifest sizes and SHA-256 values match the exact corpus and judgment bytes;
- bundle, corpus, and judgment identities agree with the internally loaded frozen
  holdout policy, candidate-policy resource, and development fixture;
- required declarations, lineage and query kinds, count bounds, full judgment
  coverage, filter boundaries, isolated one-dimension filter adversaries, and
  declared adversary boundary relationships hold; and
- candidate and query keys, normalized query text, and normalized candidate titles
  are literally disjoint from the frozen development fixture.

The verifier is read-only. It does not start Spring, PostgreSQL, Docker, a
provider client, or a PDF path, and it does not write a report or copy the input
into the repository. Corpus intake now has an explicit staged boundary:
`verifyCorpus` validates the external layout, manifest, corpus digest, corpus
schema, and label-independent semantics without opening or parsing
`judgments.json`. Its ranker-facing immutable `RankingCorpus` exposes the bundle,
corpus, policy, corpus digest, and metadata only—no path, manifest digest, payload
size, judgment digest, judgment size, or label object. The coordinator necessarily
parses the combined manifest's file table and privately retains its commitment for
the post-ranking recheck; the ranker receives only `RankingCorpus`, not the
coordinator's staged object.

`RelatedTopicReuseHoldoutRankingSnapshot` freezes the exact ordered query
partition; control pool and top ten; eligible seeds; per-seed feedback; candidate
top ten; consecutive repeat; per-query other-owner and catalog-only hidden
perturbation observation; provider-call and experimental-snapshot counters; and
every score as raw IEEE-754 bits. The ranking callback receives only
`RankingCorpus` and returns a label-free observation. After that callback returns,
the coordinator stamps the immutable snapshot with the bundle, corpus digest,
holdout policy digest, frozen candidate revision, exact manifest digest, and the
judgment payload's digest and byte count. It then mints a private
`CompletedRanking` capability bound by object identity to that exact staged corpus.
A versioned canonical byte encoding covers every stamped identity, ordered list,
ranked key, raw score bit pattern, hidden observation, and structural counter in
that snapshot. The in-memory score identity includes the SHA-256 digest of that
encoding, so a score is bound to the exact ranking evidence rather than only to
the corpus and judgments. This digest is an integrity commitment, not an
evaluator-source identity, signature, or proof of how the callback produced the
snapshot.

A raw observation or snapshot is insufficient to enter judgment loading. The
post-ranking stage accepts only that coordinator-issued capability, rejects an
identity, commitment, cutoff, query-order, or corpus-key mismatch, and then re-reads
and revalidates the manifest and corpus before it opens and validates the judgment
file. Successful post-ranking verification mints a second opaque
`VerifiedScoringInputs` capability that binds those exact revalidated corpus and
judgment objects, the sealed snapshot, and the frozen policy. The scorer accepts
only that capability, preventing ordinary callers from mixing rankings and labels
from different bundles. Judgment release is an atomic one-shot transition on that
specific `VerifiedCorpus` instance: once the release is claimed, the same staged
instance cannot open judgments for another completed ranking, even when judgment
reading or validation subsequently fails. A caller can still invoke
`verifyCorpus` again and obtain a fresh instance for the same external files. The
PostgreSQL first-run ledger supplies the durable cross-process guard: the supported
`completeRanking` coordinator requires the exact opaque `CommittedFirstRun`
capability minted only after that ledger commits, and consumes it before invoking
the ranking callback. The object-local judgment flag remains a separate later-stage
defense.

The test-only `RelatedTopicReuseHoldoutPostgresRanker` implements the
label-free callback. A deterministic fixture derives namespace IDs from the
corpus digest, stages synthetic metadata and owner visibility in ephemeral
PostgreSQL, executes the production LOCAL control plus owner-scoped feedback and
frozen RRF paths in fresh read-only ranking transactions, repeats every query,
injects per-query other-owner and catalog-only hidden maximum matches, records
provider and snapshot counters, and removes its staged rows on close. Its API
receives only `RankingCorpus` and returns `Observation`; it has no external path,
staged capability, judgment, runtime, REST, MCP, or UI entry point.

The test-only `RelatedTopicReuseHoldoutScorer` consumes the opaque post-ranking
capability and applies the frozen binary64 formulas, no-relevant-query semantics,
candidate-minus-control deltas, epsilon comparisons, and all 22 policy gates. It
returns a bounded immutable in-memory result containing ordered per-query metrics,
macro summaries, structural failure counts, and a complete fixed-order gate list.
Quality failures return the complete failed result instead of throwing or hiding
later gates. Owner and filter counts use unique query/candidate pairs across the
union of the control pool, every feedback pool, and candidate final top ten; repeated
stability still compares raw score bits, while exact no-feedback fallback compares
keys and order rather than unlike lexical and baseline-RRF scores. The result
explicitly keeps reader exposure, external acceptance, custody release, and product
activation unauthorized.

`RelatedTopicReuseHoldoutEvaluatorSeal` is the pure source-commitment boundary for
that future freeze. It validates externally gathered clean-HEAD, untracked-aware
status, candidate-ancestor, and candidate-footprint facts; binds evaluator and
candidate roles, revisions, Git modes, sorted safe repository-relative paths,
byte counts, and exact raw source bytes with a versioned domain-separated SHA-256;
and requires both aggregate digests to match an independently retained freeze
record. The returned source inventory contains per-file byte counts and SHA-256
commitments but no absolute paths. This pure class deliberately does not run Git,
discover a source inventory, or authenticate supplied repository-state facts.

`RelatedTopicReuseHoldoutGitCollector` supplies that missing collection boundary.
Its only accepting operation requires an independently retained freeze record with
the exact inventory identity, evaluator revision and source digest, and frozen
candidate revision and source digest. The candidate inventory recursively covers
`backend/src/main` plus the exact evaluation policy, fixture, rank-fusion, local
adapter, and contract sources used by the candidate. The evaluator inventory
recursively covers `backend/src` and `backend/.mvn`, plus the Maven launchers, POM,
repository attributes/ignore rules, and this protocol. It reads the committed tree
and blobs in bounded NUL-safe and length-framed Git batches, requires the candidate
tree at the evaluator revision to equal the frozen candidate tree, and compares the
evaluator checkout with the sealed committed bytes. The required CRLF projection
for `mvnw.cmd` is checked explicitly rather than skipped.

Collection requires a standalone clean clone with a real `.git` directory, rejects
replacement objects and repository-local grafts, disables commit-graph,
filesystem-monitor, untracked-cache, and submodule recursion behavior, checks both
ordinary and ignored source status, and brackets collection with repeated HEAD and
status checks. Git must be provided as an operator-configured canonical absolute
executable via `openscholar.holdout.git-executable` or
`OPENSCHOLAR_HOLDOUT_GIT_EXECUTABLE`; the collector never resolves a
repository-controlled relative `PATH`. The returned opaque seal still grants
neither external bundle acceptance nor custody release.

The collector now wraps that pure seal in a collector-only
`VerifiedCleanCheckout` capability. The ledger accepts this
capability rather than a bare caller-assembled source seal, retaining the external
freeze schema and inventory identity alongside the evaluator and candidate source
commitments.

`RelatedTopicReuseHoldoutPostgresFirstRunLedger` supplies the durable finality
boundary. More precisely, it provides durable runtime-append-only cross-process
finality under the database trust assumptions below; it does not make storage
immutable to an owner, administrator, or infrastructure operator. It is a
package-private operator utility, not a Spring bean, and its
versioned SQL lives in the isolated `db/holdout-ledger` resource location rather
than application Flyway. Provisioning requires the fixed dedicated
`openscholar_holdout_ledger_v1` database, a fixed schema, a no-login owner, an
INSERT-only runtime role, and a SELECT-only auditor. The runtime has no table reads,
updates, deletes, truncation, schema/database creation, or temporary-object
privilege in the ledger database. Production provisioning must also ensure that its
cluster-level login cannot connect to the application or any other database, either
by using a separate PostgreSQL cluster or by explicitly revoking and auditing
cross-database access. The verifier does not inspect other databases and therefore
does not yet prove the required separation. The migration assumes that the
dedicated database and roles already exist; a reviewed production runbook for
creating those principals, distributing credentials, applying the isolated
migration, and validating both local and cross-database grants is still pending.

After clean-checkout verification and label-free corpus intake—but before any
ranking callback—the ledger derives a domain-separated, length-prefixed SHA-256
run key over the policy, bundle, manifest, corpus, judgment commitment, source
inventory, evaluator, and candidate identities. The runtime-append-only row is
final on
`(evaluationProtocolId, policyId)`, so changing a bundle, evaluator, or candidate
cannot manufacture another attempt under policy v1. Claiming uses one targetless
`INSERT ... ON CONFLICT DO NOTHING RETURNING 1` transaction, which preserves the
runtime role's column-level INSERT-only privilege. The ledger requires PostgreSQL
17, `fsync=on`, `synchronous_commit=on`, the exact permanent table/column/constraint
shape, fixed role/database identity, and no user trigger or rewrite rule. It mints
an opaque one-shot ranking capability only after `commit()` acknowledges success.
There is no read, retry, reset, delete, update, lease, expiry, heartbeat, status, or
completion API. A committed claim remains final if the process crashes or any later
ranking, judgment, scoring, reporting, or publication step fails. A lost commit
acknowledgment returns no capability and must never be retried automatically.

The catalog verifier deliberately compares canonical PostgreSQL-rendered defaults,
constraints, and index definitions exactly. The mechanics tests use the
repository's digest-pinned PostgreSQL 17 image. A production operator must likewise
pin and validate one exact server image/build; an unreviewed minor-version
formatting change is expected to fail closed until the catalog contract is reviewed
and updated.

The ledger validates the connected database catalog and session, but the supplied
`DataSource` remains an operator trust boundary. Catalog identity does not
authenticate the remote endpoint, DNS or socket route, TLS peer, server
certificate, administrator, or underlying storage. The future operator must pin
and authenticate the intended endpoint and TLS/server identity, protect the owner
and administrative authorities, and use separately governed persistent storage.
The ledger sets and verifies a 15-second JDBC network timeout after it acquires a
connection, in addition to transaction-local statement and lock limits. It cannot
bound pool acquisition or initial connection setup, so the operator must still
configure bounded pool-acquisition, connect, and socket timeouts and verify the
selected driver/pool behavior under a stalled endpoint.

`RelatedTopicReuseHoldoutEvidenceReport` accepts only that opaque verified seal,
the exact ranking snapshot, and its matching scoring result. It explicitly
projects the source inventory, complete snapshot, and complete score into bounded
canonical newline-terminated `evaluator-source.json`, `ranking-snapshot.json`,
`scoring-result.json`, and `evidence-report.json` artifacts. Every ranking score and metric is
encoded as its 16-character lowercase raw binary64 bit pattern (or explicit
`null`), so serialization cannot round values or erase negative zero. A versioned
scoring-result commitment and content-addressed report ID bind the evaluator and
candidate revisions/source digests, policy, bundle, corpus, manifest, judgments,
snapshot, result, artifact hashes, and byte counts. An exact in-memory verifier
regenerates all four artifacts and rejects missing, reordered, altered, or
noncanonical bytes. All four authorization flags remain false.

`RelatedTopicReuseHoldoutEvidenceReportBundle` adds a test-only, read-only exact
verification boundary around those already verified bytes. The closed directory
is named by the report ID and contains one exact canonical manifest plus the four
fixed artifacts. Verification accepts only the opaque expected-artifact capability;
there is deliberately no expectation-free path that trusts a self-consistent
retained directory. The directory must be outside the repository and have
enforceable owner-private POSIX permissions plus a Unix link-count view: `0700`
for the report directory, `0600` for every file, and exactly one hard link per
file. Stable failures contain no filesystem path, and all
authorization flags remain false.

Filesystem publication is deliberately deferred. Portable Java NIO does not offer
an atomic directory install that is also guaranteed to reject an existing target;
`ATOMIC_MOVE` replacement behavior is provider-specific. A future publisher must
use a reviewed native exclusive-rename boundary plus descriptor-relative creation,
verification, synchronization, and cleanup. The repository does not retain a
publisher that claims stronger no-clobber or hostile-filesystem safety than Java can
provide.

There is intentionally no operator command yet. The verifier, coordinator,
ranking evidence, ranker, fixture, and scorer are package-private test utilities
exercised with generated synthetic data. The coordinator capability proves the
intended in-process call order, but cannot authenticate which injected callback ran or
isolate same-process code. The in-memory serialized report schema feeds the
read-only exact retained-bundle verifier described above. The clean-checkout
collector and durable ledger are package-private mechanics, not an operator entry
point. The `completeRanking` coordinator now requires and consumes the exact
committed ledger capability before invoking its ranking callback. There is still no
real external bundle; no operator command that composes standalone clean-checkout
collection, ledger claim, build, ranking, and scoring in one mandatory workflow; no
reviewed dedicated database/role provisioning runbook; no frozen evaluator
revision/source digest; and no custody-authorized publisher.
The checked-in ranker tests are mechanics evidence, not an external holdout result.
These schema details are for review, not an invitation to release a real bundle;
direct operator validation arrives only after the remaining pieces are implemented
and frozen.

Run the staged-boundary, immutable-evidence, policy, and PostgreSQL ranking
contracts from `backend/` with Docker available:

```bash
./mvnw -Dtest=RelatedTopicReuseHoldoutGitCollectorTests,RelatedTopicReuseHoldoutFirstRunIdentityTests,RelatedTopicReuseHoldoutPostgresFirstRunLedgerTests,RelatedTopicReuseHoldoutPostgresRankerTests,RelatedTopicReuseHoldoutBundleTests,RelatedTopicReuseHoldoutRankingSnapshotTests,RelatedTopicReuseHoldoutScoringResultTests,RelatedTopicReuseHoldoutEvaluatorSealTests,RelatedTopicReuseHoldoutEvidenceReportTests,RelatedTopicReuseHoldoutEvidenceReportBundleTests,RelatedTopicReuseHoldoutPolicyContractTests test
```

## Preregistered decision boundary

Policy v1 defines relevance as grade `>= 1`. Recall@10 divides unique relevant
top-ten results by every relevant target-visible candidate. nDCG@10 is DCG divided
by ideal DCG: each one-based ranked contribution is
`(2^grade - 1) / log2(rank + 1)`, and the ideal ranking is the descending positive
target-visible grades truncated at ten. Precision@1 is binary, and
MRR@10 uses the first relevant result. Queries with no relevant judgment are
excluded from Recall, nDCG, and MRR macros; their zero Precision@1 remains in that
macro. Each macro is an unweighted mean over applicable queries. Deltas are
candidate minus control. Arithmetic uses unrounded IEEE-754 binary64 values and a
`1e-12` comparison epsilon: a pairwise gain is above `+epsilon`, a regression is
below `-epsilon`, and anything between is a tie. A floating minimum passes when
`observed + epsilon >= threshold`; a floating maximum passes when
`observed <= threshold + epsilon`. Integer minima and maxima are exact. Novel
relevant results count query/candidate pairs present in the candidate top ten and
absent from the control top ten.

Adversaries are inspected in both final top tens. Rank-one irrelevance is counted
only at the candidate's final first position across all queries. Owner/filter violations are
inspected across the control pool, feedback pools, and candidate top ten. Labels
must be loaded only after both rankings are frozen. Stability means two consecutive
runs with identical keys and IEEE-754 score bits. The hidden-candidate perturbation
adds one maximum-match other-owner record and one maximum-match catalog-only record,
then requires identical visible feedback and candidate top ten.

Policy v1 fixes cutoff 10 and requires, among other gates, macro nDCG@10 improvement
of at least `0.03`; no macro Recall@10, Precision@1, or MRR@10 regression; no
per-query recall regression; tightly bounded nDCG regression; opportunity gains;
zero rank-one irrelevant results; stable repeated output; exact no-feedback
fallback; and zero owner leaks, filter violations, provider calls, or experimental
snapshot writes. The author control must retain its single relevant baseline hit
and produce zero eligible seeds and feedback. For this gate, a baseline hit means
that the author control's sole grade-`3` relevant paper appears anywhere in the
control final top ten; it need not be at rank one. Precision@1, the rank-one
irrelevance gate, and no-control-regression remain separate checks, so this
definition does not excuse a poor first result. The no-seed control must also
produce zero eligible seeds and feedback, rather than passing merely because two
empty or equally wrong rankings match. No-control-regression applies to both
author and no-seed controls and to every applicable Recall@10, nDCG@10,
Precision@1, and MRR@10 value.

This slice now establishes staged corpus-to-judgment isolation,
commitment-bound ranking evidence, deterministic label-blind PostgreSQL ranking
mechanics, complete in-memory scoring over the sealed post-ranking capability,
source-commitment validation, canonical evidence projection, and read-only exact
replay for already verified synthetic report bytes. It also establishes a
fail-closed clean-checkout collector for an externally frozen source inventory and
an INSERT-only durable PostgreSQL first-run claim whose one-shot capability is
mandatory at the ranking coordinator. It does not load a real external bundle or
provide the isolated operator that must compose collection, claim, build, and run
from the same verified standalone clean checkout. The dedicated database/role
provisioning runbook and bounded pool-acquisition/connect/socket configuration also remain
pending. The next safe sequence is to add those operator controls; commit and
review the complete runnable evaluator; then independently retain its
exact revision, inventory identity, and source digests outside the repository. A
later native exclusive publisher or separately reviewed external custody handoff
is still required before custody release. Until that frozen evaluator exists and a
genuinely external first run is reviewed, there is no holdout result. Even a
passing holdout would still require qualified
target-deployment performance evidence and a separately reviewed product design.

## Evidence limits

Local validation can establish byte integrity, schema compliance, declared
process, and literal disjointness. It cannot prove independent authorship,
authenticity, trusted time, confidentiality, immutable retention, or semantic
disjointness. SHA-256 is an integrity binding, not a signature. Procedural
declarations can be false. External custody, access history, reviewer identity,
and the absence of prior disclosure require controls outside this repository.
Collector verification is a bounded sequence of checks, not an atomic or hostile
filesystem snapshot. A checkout that changes from one state and back between
checks, post-verification mutation, and build/run substitution remain possible
without operator isolation. Supplying a canonical absolute Git path avoids relative
`PATH` capture but does not authenticate the executable; the operator must pin the
Git, JDK, Maven distribution, dependencies, settings, toolchains, environment, and
network/cache inputs. The staged types create an in-process API capability boundary,
not an OS process, module,
reflection, or memory-isolation boundary. A future operator runner must preserve
that call graph and must not pass the private staged coordinator object or external
path to ranking code. The atomic release flag belongs to one `VerifiedCorpus` object;
fresh intake calls or another process can create another flag. The PostgreSQL ledger
provides durable runtime-append-only cross-process finality through the mandatory
committed capability, but only for callers that preserve the supported composition
and database trust boundary. Its `fsync` and synchronous-commit checks establish
local WAL acknowledgment, not endpoint or TLS/server identity, trusted time,
administrator integrity, storage honesty, immutable retention, backup durability,
or disaster recovery. An owner, superuser, or storage administrator remains capable
of altering or removing the evidence. Testcontainers proves mechanics only; a real
first run must use separately governed persistent storage, authenticated server
configuration, bounded pool-acquisition/connect/socket timeouts, and the pending reviewed
provisioning and clean-checkout operator runbooks, never the ephemeral test
container.

Inline synthetic bundles in ordinary tests are parser and invariant fixtures
only. They are never holdout evidence, must never be reported as scores, and do
not authorize product activation.
