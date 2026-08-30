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
repository revision `6b22f6185d9c14a3dd0bf0a80a4b08c045396bff`. The current
slice does not prove that later evaluator bytecode was built from that revision.
An evaluator revision and source SHA-256 must be frozen and checked before an
external custodian releases a real bundle. Until then, external-bundle acceptance
and custody release are explicitly unauthorized. Once those prerequisites exist,
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
into the repository. It retains only immutable parsed values and the manifest
digest, not a source path that downstream code could re-read.

There is intentionally no operator command yet. The verifier is a package-private
test utility exercised with generated temporary fixtures. These schema details are
for review, not an invitation to release a real bundle; direct operator validation
arrives with the separately frozen evaluator.

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
and produce zero eligible seeds and feedback. The no-seed control must also produce
zero eligible seeds and feedback, rather than passing merely because two empty or
equally wrong rankings match. No-control-regression applies to both author and
no-seed controls and to every applicable Recall@10, nDCG@10, Precision@1, and
MRR@10 value.

This slice preregisters and validates inputs only. The next slice must add an
opt-in PostgreSQL evaluator that capability-separates corpus/ranking from
judgments/scoring, applies this exact metric contract, checks the candidate
implementation revision, and freezes its revision and source digest before custody
release.
Until that evaluator exists and a genuinely external first run is reviewed, there
is no holdout result. Even a passing holdout would still require qualified
target-deployment performance evidence and a separately reviewed product design.

## Evidence limits

Local validation can establish byte integrity, schema compliance, declared
process, and literal disjointness. It cannot prove independent authorship,
authenticity, trusted time, confidentiality, immutable retention, or semantic
disjointness. SHA-256 is an integrity binding, not a signature. Procedural
declarations can be false. External custody, access history, reviewer identity,
and the absence of prior disclosure require controls outside this repository.
Verification also assumes an operator-controlled, non-concurrently-mutated
filesystem; it is not a hostile-filesystem snapshot, hard-link detector, or
protection against an ancestor or file being swapped during intake.

Inline synthetic bundles in ordinary tests are parser and invariant fixtures
only. They are never holdout evidence, must never be reported as scores, and do
not authorize product activation.
