#!/usr/bin/env node

import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const MAX_JSON_RESPONSE_BYTES = 16 * 1024 * 1024;
const MAX_METRICS_RESPONSE_BYTES = 8 * 1024 * 1024;
const MAX_ABSTRACT_CHARACTERS = 200_000;
const MAX_AUTHORS_PER_RESULT = 1_000;
const EXPECTED_PROVIDERS = ["EUROPE_PMC", "OPENALEX"];

const argumentsMap = parseArguments(process.argv.slice(2));
if (argumentsMap.has("help")) {
  usage();
  process.exit(0);
}
const baseUrl = validatedOrigin(
  argumentsMap.get("base-url") ?? "http://127.0.0.1:8080",
  "--base-url",
);
const managementUrl = validatedOrigin(
  argumentsMap.get("management-url") ?? baseUrl.origin,
  "--management-url",
);
const queryFile = fileURLToPath(
  new URL(
    "../backend/src/test/resources/search/provider-quality/europe-pmc-live-queries-v1.json",
    import.meta.url,
  ),
);
const bearerToken = process.env.OPENSCHOLAR_PROVIDER_QUALITY_BEARER_TOKEN?.trim();
const metricsBearerToken =
  process.env.OPENSCHOLAR_PROVIDER_QUALITY_METRICS_BEARER_TOKEN?.trim()
  || (managementUrl.origin === baseUrl.origin ? bearerToken : undefined);
const requestHeaders = {
  accept: "application/json",
  ...(bearerToken ? { authorization: `Bearer ${bearerToken}` } : {}),
};
const metricsRequestHeaders = {
  accept: "text/plain",
  ...(metricsBearerToken ? { authorization: `Bearer ${metricsBearerToken}` } : {}),
};

try {
  const queryBytes = await readFile(queryFile);
  const querySet = validateQuerySet(JSON.parse(queryBytes.toString("utf8")));
  if (argumentsMap.has("validate-query-set")) {
    process.stdout.write(
      `${JSON.stringify({
        querySetId: querySet.querySetId,
        queryCount: querySet.queries.length,
        pageSize: querySet.pageSize,
        sha256: createHash("sha256").update(queryBytes).digest("hex"),
      })}\n`,
    );
    process.exit(0);
  }
  await requireHealthyBackend();
  const metricsBefore = await readProviderMetrics();
  const captures = [];

  for (const query of querySet.queries) {
    captures.push(await captureQuery(querySet, query));
  }

  const metricsAfter = await readProviderMetrics();
  const telemetry = providerTelemetry(metricsBefore, metricsAfter);
  validateTelemetry(telemetry, captures);
  const captureIssues = qualityCaptureIssues(captures, telemetry);
  const artifacts = buildArtifacts(querySet, queryBytes, captures, telemetry, captureIssues);
  const outputDirectory = await writeArtifacts(artifacts);

  process.stdout.write(
    `${JSON.stringify(
      {
        schemaVersion: 1,
        captureId: artifacts.summary.captureId,
        querySetId: querySet.querySetId,
        queryCount: querySet.queries.length,
        outputDirectory,
        qualityReviewEligible: captureIssues.length === 0,
        captureIssues,
        summary: artifacts.summary.aggregate,
        providerTelemetry: telemetry,
        nextStep: captureIssues.length === 0
          ? "Give blinded-candidates.json to a reviewer without provenance-map.json, then retain labels with this immutable capture."
          : "Inspect captureIssues in summary.json, correct the isolated evaluation run, and repeat the capture before relevance labeling.",
      },
      null,
      2,
    )}\n`,
  );
  if (captureIssues.length > 0) process.exitCode = 2;
} catch (error) {
  fail(error instanceof Error ? error.message : String(error));
}

async function captureQuery(querySet, query) {
  const startedAt = performance.now();
  const response = await fetch(new URL("/api/v1/searches", baseUrl), {
    method: "POST",
    headers: { ...requestHeaders, "content-type": "application/json" },
    body: JSON.stringify({
      query: query.query,
      filters: {
        documentTypes: ["ARTICLE"],
        openAccessOnly: false,
        minimumCitations: 0,
        languages: [],
      },
      pageSize: querySet.pageSize,
      forceRefresh: true,
      mode: "ONLINE",
    }),
    redirect: "error",
    signal: AbortSignal.timeout(30_000),
  });
  const body = await responseJson(response, `/api/v1/searches (${query.key})`);
  const durationMs = performance.now() - startedAt;
  if (response.status !== 201 || !body?.searchId) {
    throw new Error(
      `query ${query.key} failed with HTTP ${response.status}${problemCode(body)}`,
    );
  }
  validateSearchExecution(body, query);
  if (!Array.isArray(body.providerCoverage) || !Array.isArray(body.results)) {
    throw new Error(`query ${query.key} returned an invalid search response`);
  }
  const providerCoverage = validateProviderCoverage(
    body.providerCoverage,
    query.key,
    querySet.pageSize,
  );
  const coveredProviders = providerCoverage.map((coverage) => coverage.provider).sort();
  if (JSON.stringify(coveredProviders) !== JSON.stringify(EXPECTED_PROVIDERS)) {
    throw new Error(
      `query ${query.key} must run with exactly OPENALEX and EUROPE_PMC enabled; found ${coveredProviders.join(", ") || "none"}`,
    );
  }
  if (body.results.length > querySet.pageSize) {
    throw new Error(`query ${query.key} returned more canonical results than the bounded page size`);
  }
  const warnings = validateWarnings(body.warnings, query.key);
  const results = body.results.map((result, index) => validateResult(result, query.key, index));
  validateCanonicalResultIdentity(results, query.key);
  return {
    query,
    durationMs: round(durationMs),
    searchId: body.searchId,
    warnings,
    providerCoverage,
    results,
  };
}

function buildArtifacts(querySet, queryBytes, captures, telemetry, captureIssues) {
  const measuredAt = new Date().toISOString();
  const captureId = `europe-pmc-live-${measuredAt.replaceAll(/[:.]/g, "-")}`;
  const blindedCandidates = [];
  const provenanceMap = [];
  const querySummaries = [];
  const reviewKeys = new Set();

  for (const capture of captures) {
    let europePmcOnly = 0;
    let europePmcShared = 0;
    let openAlexOnly = 0;
    const normalizedDois = new Map();

    for (const result of capture.results) {
      const providers = [...new Set(result.provenance.map((item) => item.provider))].sort();
      const hasEuropePmc = providers.includes("EUROPE_PMC");
      const hasOpenAlex = providers.includes("OPENALEX");
      if (hasEuropePmc && providers.length === 1) europePmcOnly += 1;
      if (hasEuropePmc && hasOpenAlex) europePmcShared += 1;
      if (hasOpenAlex && providers.length === 1) openAlexOnly += 1;

      const doi = normalizeDoi(result.identifiers?.doi);
      if (doi) normalizedDois.set(doi, (normalizedDois.get(doi) ?? 0) + 1);

      const reviewKey = createHash("sha256")
        .update(`${querySet.querySetId}\n${capture.query.key}\n${result.paperId}`)
        .digest("hex")
        .slice(0, 24);
      if (reviewKeys.has(reviewKey)) {
        throw new Error(`capture generated duplicate review key ${reviewKey}`);
      }
      reviewKeys.add(reviewKey);
      blindedCandidates.push({
        reviewKey,
        queryKey: capture.query.key,
        query: capture.query.query,
        displayedRank: result.rank,
        title: result.title,
        abstractText: result.abstractText,
        authors: result.authors,
        publicationDate: result.publicationDate,
        publicationYear: result.publicationYear,
        documentType: result.documentType,
        language: result.language,
        venue: result.venue,
      });
      provenanceMap.push({
        reviewKey,
        queryKey: capture.query.key,
        paperId: result.paperId,
        identifiers: result.identifiers,
        provenance: result.provenance,
      });
    }

    querySummaries.push({
      key: capture.query.key,
      durationMs: capture.durationMs,
      resultCount: capture.results.length,
      europePmcOnly,
      europePmcShared,
      openAlexOnly,
      repeatedExactDoiCount: [...normalizedDois.values()].filter((count) => count > 1).length,
      warnings: capture.warnings,
      providerCoverage: capture.providerCoverage,
    });
  }

  const durations = querySummaries.map((query) => query.durationMs);
  const aggregate = {
    queryCount: querySummaries.length,
    canonicalResultCount: sum(querySummaries, "resultCount"),
    europePmcOnlyResultCount: sum(querySummaries, "europePmcOnly"),
    europePmcSharedResultCount: sum(querySummaries, "europePmcShared"),
    openAlexOnlyResultCount: sum(querySummaries, "openAlexOnly"),
    repeatedExactDoiCount: sum(querySummaries, "repeatedExactDoiCount"),
    searchLatencyMs: {
      minimum: round(Math.min(...durations)),
      median: round(percentile(durations, 0.5)),
      p95: round(percentile(durations, 0.95)),
      maximum: round(Math.max(...durations)),
    },
  };

  return {
    summary: {
      schemaVersion: 1,
      evidenceType: "LIVE_UNJUDGED_METADATA_CAPTURE",
      captureId,
      measuredAt,
      target: baseUrl.origin,
      managementTarget: managementUrl.origin,
      querySet: {
        id: querySet.querySetId,
        sha256: createHash("sha256").update(queryBytes).digest("hex"),
        sourcePolicy: querySet.sourcePolicy,
        pageSize: querySet.pageSize,
      },
      boundaries: {
        metadataOnly: true,
        firstPageOnly: true,
        fetchesPdf: false,
        fetchesFullText: false,
        fetchesSupplementaryFiles: false,
        usefulnessClaim: false,
        qualityReviewEligible: captureIssues.length === 0,
      },
      captureIssues,
      aggregate,
      providerTelemetry: telemetry,
      queries: querySummaries,
    },
    blinded: {
      schemaVersion: 1,
      captureId,
      qualityReviewEligible: captureIssues.length === 0,
      labelScale: {
        0: "not relevant",
        1: "marginally relevant",
        2: "relevant",
        3: "highly relevant",
      },
      instructions: captureIssues.length === 0
        ? "Assign one integer relevanceGrade from 0 through 3 to every item without consulting provenance-map.json."
        : "Do not use this incomplete capture for relevance scoring; inspect summary.json captureIssues and repeat the isolated capture.",
      candidates: blindedCandidates,
    },
    provenance: {
      schemaVersion: 1,
      captureId,
      warning: "Keep this file hidden from the relevance reviewer until labeling is complete.",
      candidates: provenanceMap,
    },
  };
}

async function writeArtifacts(artifacts) {
  const root = fileURLToPath(new URL("../backend/target/provider-quality/", import.meta.url));
  const outputDirectory = `${root}${artifacts.summary.captureId}`;
  await mkdir(root, { recursive: true, mode: 0o700 });
  await mkdir(outputDirectory, { recursive: false, mode: 0o700 });
  await Promise.all([
    writeJson(`${outputDirectory}/summary.json`, artifacts.summary),
    writeJson(`${outputDirectory}/blinded-candidates.json`, artifacts.blinded),
    writeJson(`${outputDirectory}/provenance-map.json`, artifacts.provenance),
  ]);
  return outputDirectory;
}

async function writeJson(path, value) {
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, {
    encoding: "utf8",
    flag: "wx",
    mode: 0o600,
  });
}

async function requireHealthyBackend() {
  const response = await fetch(new URL("/api/v1/system/status", baseUrl), {
    headers: requestHeaders,
    redirect: "error",
    signal: AbortSignal.timeout(10_000),
  });
  const body = await responseJson(response, "/api/v1/system/status");
  if (response.status !== 200 || body?.status !== "UP") {
    throw new Error(`backend status check failed with HTTP ${response.status}${problemCode(body)}`);
  }
}

async function readProviderMetrics() {
  const response = await fetch(new URL("/actuator/prometheus", managementUrl), {
    headers: metricsRequestHeaders,
    redirect: "error",
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) {
    throw new Error(`Prometheus scrape failed with HTTP ${response.status}`);
  }
  return parseProviderMetrics(
    await boundedResponseText(response, "/actuator/prometheus", MAX_METRICS_RESPONSE_BYTES),
  );
}

function parseProviderMetrics(text) {
  const providers = new Map();
  for (const line of text.split("\n")) {
    if (line.length === 0 || line.startsWith("#")) continue;
    const match = line.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+([^\s]+)$/);
    if (!match) continue;
    const metric = match[1];
    if (!metric.startsWith("openscholar_provider_")) continue;
    const labels = labelsFromMetric(match[2] ?? "");
    const provider = labels.get("provider");
    const value = Number(match[3]);
    if (!provider || !Number.isFinite(value)) continue;
    const values = providers.get(provider) ?? new Map();
    const key = `${metric}|${labels.get("outcome") ?? ""}|${labels.get("retryable") ?? ""}`;
    values.set(key, (values.get(key) ?? 0) + value);
    providers.set(provider, values);
  }
  return providers;
}

function providerTelemetry(before, after) {
  const providerNames = [...new Set([...before.keys(), ...after.keys()])].sort();
  return Object.fromEntries(
    providerNames.map((provider) => {
      const requestCount = metricDelta(
        before,
        after,
        provider,
        "openscholar_provider_requests_total",
      );
      const failures = metricDelta(
        before,
        after,
        provider,
        "openscholar_provider_requests_total",
        "failure",
      );
      const durationSeconds = metricDelta(
        before,
        after,
        provider,
        "openscholar_provider_request_duration_seconds_sum",
      );
      const durationCount = metricDelta(
        before,
        after,
        provider,
        "openscholar_provider_request_duration_seconds_count",
      );
      const records = metricDelta(
        before,
        after,
        provider,
        "openscholar_provider_results_records_sum",
      );
      const resultCount = metricDelta(
        before,
        after,
        provider,
        "openscholar_provider_results_records_count",
      );
      return [
        provider,
        {
          requests: requestCount,
          failures,
          errorRate: requestCount === 0 ? 0 : round(failures / requestCount),
          durationSamples: durationCount,
          meanRequestDurationMs:
            durationCount === 0 ? null : round((durationSeconds * 1_000) / durationCount),
          resultSamples: resultCount,
          returnedRecords: records,
        },
      ];
    }),
  );
}

function validateTelemetry(telemetry, captures) {
  const expectedRequestCount = captures.length;
  const activeProviders = Object.entries(telemetry)
    .filter(([, values]) => values.requests > 0)
    .map(([provider]) => provider)
    .sort();
  if (JSON.stringify(activeProviders) !== JSON.stringify(EXPECTED_PROVIDERS)) {
    throw new Error(
      `provider telemetry must contain only active OPENALEX and EUROPE_PMC requests; found ${activeProviders.join(", ") || "none"}`,
    );
  }
  for (const provider of activeProviders) {
    const values = telemetry[provider];
    const coverage = captures.map((capture) => capture.providerCoverage.find(
      (item) => item.provider === provider,
    ));
    if (coverage.some((item) => item === undefined)) {
      throw new Error(`${provider} is missing from captured provider coverage`);
    }
    const expectedFailures = coverage.filter((item) => item.status === "FAILED").length;
    const expectedReturnedRecords = coverage.reduce(
      (total, item) => total + item.returnedCount,
      0,
    );
    if (values.requests !== expectedRequestCount) {
      throw new Error(
        `${provider} telemetry contains ${values.requests} requests; expected exactly ${expectedRequestCount} in an isolated capture`,
      );
    }
    if (values.failures !== expectedFailures) {
      throw new Error(
        `${provider} telemetry contains ${values.failures} failures; captured coverage contains ${expectedFailures}`,
      );
    }
    if (values.durationSamples !== values.requests) {
      throw new Error(
        `${provider} telemetry contains ${values.durationSamples} duration samples; expected ${values.requests}`,
      );
    }
    if (values.resultSamples !== values.requests - values.failures) {
      throw new Error(
        `${provider} telemetry contains ${values.resultSamples} result samples; expected ${values.requests - values.failures}`,
      );
    }
    if (values.returnedRecords !== expectedReturnedRecords) {
      throw new Error(
        `${provider} telemetry contains ${values.returnedRecords} returned records; captured coverage contains ${expectedReturnedRecords}`,
      );
    }
  }
}

function qualityCaptureIssues(captures, telemetry) {
  const issues = [];
  for (const capture of captures) {
    for (const coverage of capture.providerCoverage) {
      if (coverage.status !== "SUCCESS") {
        issues.push(`${capture.query.key}:${coverage.provider}:${coverage.status}`);
      }
    }
  }
  for (const provider of EXPECTED_PROVIDERS) {
    if (telemetry[provider].failures > 0) {
      issues.push(`${provider}:TELEMETRY_FAILURES:${telemetry[provider].failures}`);
    }
  }
  return [...new Set(issues)].sort();
}

function metricDelta(before, after, provider, metric, outcome) {
  return Math.max(
    0,
    metricTotal(after.get(provider), metric, outcome) -
      metricTotal(before.get(provider), metric, outcome),
  );
}

function metricTotal(values, metric, outcome) {
  if (!values) return 0;
  let total = 0;
  for (const [key, value] of values) {
    const [candidateMetric, candidateOutcome] = key.split("|");
    if (candidateMetric === metric && (!outcome || candidateOutcome === outcome)) total += value;
  }
  return total;
}

function validateQuerySet(value) {
  requireObject(value, "query set");
  requireExactKeys(value, ["schemaVersion", "querySetId", "sourcePolicy", "pageSize", "queries"], "query set");
  if (value.schemaVersion !== 1) throw new Error("query set schemaVersion must be 1");
  requireBoundedString(value.querySetId, "querySetId", 3, 100);
  requireBoundedString(value.sourcePolicy, "sourcePolicy", 3, 100);
  if (!Number.isInteger(value.pageSize) || value.pageSize < 1 || value.pageSize > 20) {
    throw new Error("query set pageSize must be an integer from 1 through 20");
  }
  if (!Array.isArray(value.queries) || value.queries.length !== 8) {
    throw new Error("query set must contain exactly 8 queries");
  }
  const keys = new Set();
  for (const [index, query] of value.queries.entries()) {
    requireObject(query, `queries[${index}]`);
    requireExactKeys(query, ["key", "query"], `queries[${index}]`);
    requireBoundedString(query.key, `queries[${index}].key`, 3, 80);
    requireBoundedString(query.query, `queries[${index}].query`, 3, 500);
    if (keys.has(query.key)) throw new Error(`duplicate query key: ${query.key}`);
    keys.add(query.key);
  }
  return value;
}

function validateSearchExecution(body, query) {
  if (body.query !== query.query) {
    throw new Error(`query ${query.key} response does not match the requested query text`);
  }
  if (body.requestedMode !== "ONLINE") {
    throw new Error(`query ${query.key} response requestedMode must be ONLINE`);
  }
  if (body.executionSource !== "PROVIDER_FETCH") {
    throw new Error(`query ${query.key} response executionSource must be PROVIDER_FETCH`);
  }
  if (body.cacheDisposition !== "MISS_FETCHED"
      && body.cacheDisposition !== "FORCED_REFRESH") {
    throw new Error(
      `query ${query.key} response cacheDisposition must be MISS_FETCHED or FORCED_REFRESH`,
    );
  }
}

function validateResult(result, queryKey, index) {
  requireObject(result, `${queryKey}.results[${index}]`);
  if (!Number.isInteger(result.rank) || result.rank !== index + 1) {
    throw new Error(`${queryKey} returned non-contiguous result ranks`);
  }
  requireBoundedString(result.paperId, `${queryKey}.results[${index}].paperId`, 1, 100);
  requireBoundedString(result.title, `${queryKey}.results[${index}].title`, 1, 10_000);
  if (!Array.isArray(result.authors) || !Array.isArray(result.provenance)) {
    throw new Error(`${queryKey}.results[${index}] has invalid authors or provenance`);
  }
  if (result.authors.length > MAX_AUTHORS_PER_RESULT || result.provenance.length < 1
      || result.provenance.length > EXPECTED_PROVIDERS.length) {
    throw new Error(`${queryKey}.results[${index}] exceeds author or provenance bounds`);
  }
  if (result.abstractText !== null && result.abstractText !== undefined) {
    requireBoundedString(
      result.abstractText,
      `${queryKey}.results[${index}].abstractText`,
      1,
      MAX_ABSTRACT_CHARACTERS,
    );
  }
  const publicationDate = validatePublicationDate(
    result.publicationDate,
    `${queryKey}.results[${index}].publicationDate`,
  );
  const publicationYear = validatePublicationYear(
    result.publicationYear,
    `${queryKey}.results[${index}].publicationYear`,
  );
  if (publicationDate !== null
      && publicationYear !== null
      && Number(publicationDate.slice(0, 4)) !== publicationYear) {
    throw new Error(
      `${queryKey}.results[${index}] publicationDate and publicationYear must agree`,
    );
  }
  if (result.documentType !== "ARTICLE") {
    throw new Error(`${queryKey}.results[${index}].documentType must be ARTICLE`);
  }
  validateNullableString(
    result.language,
    `${queryKey}.results[${index}].language`,
    100,
  );
  validateNullableString(
    result.venue,
    `${queryKey}.results[${index}].venue`,
    10_000,
  );
  const authors = result.authors.map((author, authorIndex) => {
    requireObject(author, `${queryKey}.results[${index}].authors[${authorIndex}]`);
    requireBoundedString(
      author.name,
      `${queryKey}.results[${index}].authors[${authorIndex}].name`,
      1,
      1_000,
    );
    validateNullableString(
      author.orcid,
      `${queryKey}.results[${index}].authors[${authorIndex}].orcid`,
      100,
    );
    validateNullableString(
      author.openAlexId,
      `${queryKey}.results[${index}].authors[${authorIndex}].openAlexId`,
      200,
    );
    return { name: author.name, orcid: author.orcid ?? null };
  });
  if (!result.identifiers
      || typeof result.identifiers !== "object"
      || Array.isArray(result.identifiers)) {
    throw new Error(`${queryKey}.results[${index}] has invalid identifiers`);
  }
  const identifiers = {};
  for (const key of ["doi", "arxiv", "openAlex"]) {
    validateNullableString(
      result.identifiers[key],
      `${queryKey}.results[${index}].identifiers.${key}`,
      2_048,
    );
    identifiers[key] = result.identifiers[key] ?? null;
  }
  const provenance = result.provenance.map((item, provenanceIndex) => {
    requireObject(item, `${queryKey}.results[${index}].provenance[${provenanceIndex}]`);
    if (!EXPECTED_PROVIDERS.includes(item.provider)) {
      throw new Error(`${queryKey}.results[${index}] has unexpected provenance provider`);
    }
    requireBoundedString(
      item.providerRecordId,
      `${queryKey}.results[${index}].provenance[${provenanceIndex}].providerRecordId`,
      1,
      1_024,
    );
    requireBoundedString(
      item.retrievedAt,
      `${queryKey}.results[${index}].provenance[${provenanceIndex}].retrievedAt`,
      1,
      100,
    );
    if (!Number.isFinite(Date.parse(item.retrievedAt))) {
      throw new Error(`${queryKey}.results[${index}] has invalid provenance timestamp`);
    }
    return {
      provider: item.provider,
      providerRecordId: item.providerRecordId,
      retrievedAt: item.retrievedAt,
    };
  });
  if (new Set(provenance.map((item) => item.provider)).size !== provenance.length) {
    throw new Error(`${queryKey}.results[${index}] repeats a provenance provider`);
  }
  return {
    rank: result.rank,
    paperId: result.paperId,
    title: result.title,
    abstractText: result.abstractText ?? null,
    authors,
    publicationDate,
    publicationYear,
    documentType: result.documentType,
    language: result.language ?? null,
    venue: result.venue ?? null,
    identifiers,
    provenance,
  };
}

function validateProviderCoverage(values, queryKey, pageSize) {
  if (values.length !== EXPECTED_PROVIDERS.length) {
    throw new Error(`${queryKey} must contain exactly two provider-coverage entries`);
  }
  return values.map((coverage, index) => {
    requireObject(coverage, `${queryKey}.providerCoverage[${index}]`);
    if (!EXPECTED_PROVIDERS.includes(coverage.provider)
        || (coverage.status !== "SUCCESS" && coverage.status !== "FAILED")) {
      throw new Error(`${queryKey}.providerCoverage[${index}] has invalid provider or status`);
    }
    if (!Number.isInteger(coverage.returnedCount)
        || coverage.returnedCount < 0
        || coverage.returnedCount > pageSize
        || !Number.isSafeInteger(coverage.totalMatches)
        || coverage.totalMatches < 0) {
      throw new Error(`${queryKey}.providerCoverage[${index}] has invalid counts`);
    }
    return {
      provider: coverage.provider,
      status: coverage.status,
      returnedCount: coverage.returnedCount,
      totalMatches: coverage.totalMatches,
    };
  });
}

function validateCanonicalResultIdentity(results, queryKey) {
  const paperIds = new Set();
  const providerRecords = new Set();
  for (const result of results) {
    if (paperIds.has(result.paperId)) {
      throw new Error(`${queryKey} repeats canonical paperId ${result.paperId}`);
    }
    paperIds.add(result.paperId);
    for (const item of result.provenance) {
      const contributionKey = `${item.provider}\n${item.providerRecordId}`;
      if (providerRecords.has(contributionKey)) {
        throw new Error(
          `${queryKey} maps ${item.provider} record ${item.providerRecordId} to multiple canonical results`,
        );
      }
      providerRecords.add(contributionKey);
    }
  }
}

function validateWarnings(value, queryKey) {
  if (!Array.isArray(value) || value.length > 20) {
    throw new Error(`${queryKey}.warnings must contain at most 20 entries`);
  }
  return value.map((warning, index) => {
    requireBoundedString(warning, `${queryKey}.warnings[${index}]`, 1, 500);
    return warning;
  });
}

function validatedOrigin(value, optionName) {
  let url;
  try {
    url = new URL(value);
  } catch {
    fail(`${optionName} must be a valid absolute URL`);
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    fail(`${optionName} must use HTTP or HTTPS`);
  }
  if (url.username || url.password || url.search || url.hash || url.pathname !== "/") {
    fail(`${optionName} must be an origin without credentials, path, query, or fragment`);
  }
  if (!isLoopback(url.hostname)) {
    if (process.env.ALLOW_REMOTE_PROVIDER_QUALITY_TARGET !== "true") {
      fail(
        "live capture accepts loopback targets only; set ALLOW_REMOTE_PROVIDER_QUALITY_TARGET=true for an approved private target",
      );
    }
    if (url.protocol !== "https:") fail("approved remote targets must use HTTPS");
  }
  return url;
}

async function responseJson(response, label) {
  const text = await boundedResponseText(response, label, MAX_JSON_RESPONSE_BYTES);
  try {
    return text.length === 0 ? null : JSON.parse(text);
  } catch {
    throw new Error(`${label} returned non-JSON HTTP ${response.status}`);
  }
}

async function boundedResponseText(response, label, maximumBytes) {
  const declaredLength = response.headers.get("content-length");
  if (declaredLength !== null) {
    if (!/^\d+$/.test(declaredLength) || Number(declaredLength) > maximumBytes) {
      throw new Error(`${label} exceeded the ${maximumBytes}-byte response limit`);
    }
  }
  if (!response.body) return "";
  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  const parts = [];
  let receivedBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      receivedBytes += value.byteLength;
      if (receivedBytes > maximumBytes) {
        await reader.cancel("response byte limit exceeded");
        throw new Error(`${label} exceeded the ${maximumBytes}-byte response limit`);
      }
      parts.push(decoder.decode(value, { stream: true }));
    }
    parts.push(decoder.decode());
    return parts.join("");
  } catch (error) {
    if (error instanceof TypeError) throw new Error(`${label} returned invalid UTF-8`, { cause: error });
    throw error;
  } finally {
    reader.releaseLock();
  }
}

function labelsFromMetric(text) {
  const labels = new Map();
  const pattern = /([a-zA-Z_][a-zA-Z0-9_]*)="((?:\\.|[^"\\])*)"/g;
  for (const match of text.matchAll(pattern)) labels.set(match[1], match[2]);
  return labels;
}

function parseArguments(values) {
  const result = new Map();
  for (let index = 0; index < values.length; index += 1) {
    const name = values[index];
    if (!name?.startsWith("--")) {
      fail(`invalid argument near ${name ?? "<end>"}`);
    }
    const key = name.slice(2);
    if (result.has(key)) fail(`duplicate argument: ${name}`);
    if (key === "help" || key === "validate-query-set") {
      result.set(key, true);
      continue;
    }
    if (key !== "base-url" && key !== "management-url") {
      fail(`unsupported argument: ${name}`);
    }
    const value = values[index + 1];
    if (value === undefined || value.startsWith("--")) fail(`missing value for ${name}`);
    result.set(key, value);
    index += 1;
  }
  return result;
}

function usage() {
  process.stdout.write(`Usage:
  node scripts/capture-europe-pmc-quality.mjs [--base-url http://127.0.0.1:8080] [--management-url http://127.0.0.1:9091]
  node scripts/capture-europe-pmc-quality.mjs --validate-query-set

The live command requires an explicitly Europe-PMC-enabled backend. It performs
eight bounded metadata-only searches and writes blinded review artifacts beneath
backend/target/provider-quality/. Remote targets require HTTPS and
ALLOW_REMOTE_PROVIDER_QUALITY_TARGET=true. Supply hosted bearer credentials only
through OPENSCHOLAR_PROVIDER_QUALITY_BEARER_TOKEN. A separately authenticated
management endpoint may use OPENSCHOLAR_PROVIDER_QUALITY_METRICS_BEARER_TOKEN.
`);
}

function requireObject(value, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${label} must be an object`);
  }
}

function requireExactKeys(value, expected, label) {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (JSON.stringify(actual) !== JSON.stringify(wanted)) {
    throw new Error(`${label} keys must be exactly: ${wanted.join(", ")}`);
  }
}

function requireBoundedString(value, label, minimum, maximum) {
  if (typeof value !== "string" || value.trim().length < minimum || value.trim().length > maximum) {
    throw new Error(`${label} must contain ${minimum} through ${maximum} characters`);
  }
}

function validateNullableString(value, label, maximum) {
  if (value === null || value === undefined) return;
  requireBoundedString(value, label, 1, maximum);
}

function validatePublicationDate(value, label) {
  if (value === null || value === undefined) return null;
  requireBoundedString(value, label, 10, 10);
  const parsed = new Date(`${value}T00:00:00Z`);
  const maximumYear = new Date().getUTCFullYear() + 1;
  const year = Number(value.slice(0, 4));
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)
      || !Number.isFinite(parsed.valueOf())
      || parsed.toISOString().slice(0, 10) !== value
      || year < 1_000
      || year > maximumYear) {
    throw new Error(
      `${label} must be a valid ISO calendar date from year 1000 through ${maximumYear}`,
    );
  }
  return value;
}

function validatePublicationYear(value, label) {
  if (value === null || value === undefined) return null;
  const maximum = new Date().getUTCFullYear() + 1;
  if (!Number.isInteger(value) || value < 1_000 || value > maximum) {
    throw new Error(`${label} must be an integer from 1000 through ${maximum}`);
  }
  return value;
}

function problemCode(body) {
  return body?.code ? ` (${body.code})` : "";
}

function normalizeDoi(value) {
  if (typeof value !== "string") return null;
  const normalized = value
    .trim()
    .toLowerCase()
    .replace(/^https?:\/\/(?:dx\.)?doi\.org\//, "")
    .replace(/^doi:\s*/, "");
  return normalized.startsWith("10.") && normalized.includes("/") ? normalized : null;
}

function sum(values, key) {
  return values.reduce((total, value) => total + value[key], 0);
}

function percentile(values, quantile) {
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.max(0, Math.ceil(quantile * sorted.length) - 1)];
}

function isLoopback(hostname) {
  return hostname === "127.0.0.1"
    || hostname === "localhost"
    || hostname === "::1"
    || hostname === "[::1]";
}

function round(value) {
  return Math.round(value * 1_000) / 1_000;
}

function fail(message) {
  process.stderr.write(`capture-europe-pmc-quality: ${message}\n`);
  process.exit(1);
}
