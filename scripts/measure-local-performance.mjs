#!/usr/bin/env node

import process from "node:process";

const argumentsMap = parseArguments(process.argv.slice(2));
const baseUrl = new URL(argumentsMap.get("base-url") ?? "http://127.0.0.1:8080");
const topic = argumentsMap.get("topic");
const samples = integerArgument(argumentsMap.get("samples") ?? "40", "samples", 5, 500);

if (!topic || topic.trim().length < 3 || topic.trim().length > 500) {
  fail("--topic must contain 3 to 500 characters");
}
if (!isLoopback(baseUrl.hostname) && process.env.ALLOW_REMOTE_PERFORMANCE_TARGET !== "true") {
  fail(
    "The performance harness accepts loopback targets only. Set ALLOW_REMOTE_PERFORMANCE_TARGET=true only for an approved private target.",
  );
}
if (baseUrl.protocol !== "http:" && baseUrl.protocol !== "https:") {
  fail("--base-url must use http or https");
}

const searchBody = {
  query: topic.trim(),
  filters: {
    documentTypes: [],
    openAccessOnly: false,
    minimumCitations: 0,
    languages: [],
  },
  pageSize: 5,
  forceRefresh: false,
};

try {
  await requireHealthyBackend();
  const metricsBefore = await readProviderMetrics();

  const cold = await timedJson("/api/v1/searches", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ...searchBody, forceRefresh: true }),
  });
  requireSearch(cold, "cold search");

  const cachedDurations = [];
  const cachedDispositions = [];
  let cachedSearchId;
  for (let index = 0; index < samples; index += 1) {
    const response = await timedJson("/api/v1/searches", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(searchBody),
    });
    requireSearch(response, `cached search sample ${index + 1}`);
    cachedDurations.push(response.durationMs);
    cachedDispositions.push(response.body.cacheDisposition);
    cachedSearchId ??= response.body.searchId;
  }

  const paperId = cold.body.results?.[0]?.paperId;
  if (!paperId) {
    fail("The cold search returned no papers, so paper-detail p95 cannot be measured");
  }
  const paperDurations = [];
  for (let index = 0; index < samples; index += 1) {
    const response = await timedJson(`/api/v1/papers/${encodeURIComponent(paperId)}`);
    if (response.status !== 200 || response.body?.paperId !== paperId) {
      fail(`paper detail sample ${index + 1} failed with HTTP ${response.status}`);
    }
    paperDurations.push(response.durationMs);
  }

  const metricsAfter = await readProviderMetrics();
  const providerRequests = metricsAfter.total - metricsBefore.total;
  const providerFailures = metricsAfter.failures - metricsBefore.failures;
  const exactHits = cachedDispositions.filter((value) => value === "EXACT_HIT").length;
  const result = {
    schemaVersion: 1,
    measuredAt: new Date().toISOString(),
    target: baseUrl.origin,
    topic: topic.trim(),
    samples,
    coldSearch: {
      httpStatus: cold.status,
      cacheDisposition: cold.body.cacheDisposition,
      durationMs: round(cold.durationMs),
      resultCount: cold.body.results.length,
      searchId: cold.body.searchId,
    },
    cachedSearch: summary(cachedDurations, {
      exactHits,
      cacheHitRatio: round(exactHits / samples),
      searchId: cachedSearchId,
    }),
    paperDetail: summary(paperDurations, { paperId }),
    provider: {
      requests: providerRequests,
      failures: providerFailures,
      errorRate: providerRequests === 0 ? 0 : round(providerFailures / providerRequests),
    },
    thresholds: {
      cachedSearchP95Ms: 500,
      paperDetailP95Ms: 300,
    },
    passed: percentile(cachedDurations, 0.95) < 500 && percentile(paperDurations, 0.95) < 300,
  };
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  if (!result.passed || exactHits !== samples) {
    process.exitCode = 1;
  }
} catch (error) {
  fail(error instanceof Error ? error.message : String(error));
}

async function requireHealthyBackend() {
  const response = await timedJson("/api/v1/system/status");
  if (response.status !== 200 || response.body?.status !== "UP") {
    fail(`backend status check failed with HTTP ${response.status}`);
  }
}

async function readProviderMetrics() {
  const response = await fetch(new URL("/actuator/prometheus", baseUrl), {
    headers: { accept: "text/plain" },
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) {
    fail(`Prometheus scrape failed with HTTP ${response.status}`);
  }
  const text = await response.text();
  let total = 0;
  let failures = 0;
  for (const line of text.split("\n")) {
    if (!line.startsWith("openscholar_provider_requests_total")) continue;
    const value = Number(line.slice(line.lastIndexOf(" ") + 1));
    if (!Number.isFinite(value)) continue;
    total += value;
    const labels = labelsFromMetric(line);
    if (labels.get("outcome") === "failure") failures += value;
  }
  return { total, failures };
}

async function timedJson(path, options = {}) {
  const startedAt = performance.now();
  const response = await fetch(new URL(path, baseUrl), {
    ...options,
    headers: { accept: "application/json", ...(options.headers ?? {}) },
    signal: AbortSignal.timeout(30_000),
  });
  const durationMs = performance.now() - startedAt;
  const text = await response.text();
  let body;
  try {
    body = text.length === 0 ? null : JSON.parse(text);
  } catch {
    fail(`${path} returned non-JSON HTTP ${response.status}`);
  }
  return { status: response.status, body, durationMs };
}

function requireSearch(response, label) {
  if (
    (response.status !== 200 && response.status !== 201) ||
    !response.body?.searchId ||
    !Array.isArray(response.body?.results)
  ) {
    const code = response.body?.code ? ` (${response.body.code})` : "";
    fail(`${label} failed with HTTP ${response.status}${code}`);
  }
}

function summary(values, extra) {
  return {
    ...extra,
    minimumMs: round(Math.min(...values)),
    medianMs: round(percentile(values, 0.5)),
    p95Ms: round(percentile(values, 0.95)),
    maximumMs: round(Math.max(...values)),
  };
}

function percentile(values, quantile) {
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.max(0, Math.ceil(quantile * sorted.length) - 1)];
}

function labelsFromMetric(line) {
  const labels = new Map();
  const opening = line.indexOf("{");
  const closing = line.indexOf("}");
  if (opening < 0 || closing < opening) return labels;
  const pattern = /([a-zA-Z_][a-zA-Z0-9_]*)="((?:\\.|[^"\\])*)"/g;
  for (const match of line.slice(opening + 1, closing).matchAll(pattern)) {
    labels.set(match[1], match[2]);
  }
  return labels;
}

function parseArguments(values) {
  const result = new Map();
  for (let index = 0; index < values.length; index += 2) {
    const name = values[index];
    const value = values[index + 1];
    if (!name?.startsWith("--") || value === undefined || value.startsWith("--")) {
      fail(`invalid argument near ${name ?? "<end>"}`);
    }
    result.set(name.slice(2), value);
  }
  return result;
}

function integerArgument(value, name, minimum, maximum) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
    fail(`--${name} must be an integer from ${minimum} to ${maximum}`);
  }
  return parsed;
}

function isLoopback(hostname) {
  return hostname === "127.0.0.1" || hostname === "localhost" || hostname === "::1";
}

function round(value) {
  return Math.round(value * 1_000) / 1_000;
}

function fail(message) {
  process.stderr.write(`measure-local-performance: ${message}\n`);
  process.exit(1);
}
