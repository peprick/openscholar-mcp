#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { readFile, rm, stat } from "node:fs/promises";
import { createServer } from "node:http";
import { basename, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const REPOSITORY_ROOT = fileURLToPath(new URL("../", import.meta.url));
const CAPTURE_SCRIPT = fileURLToPath(
  new URL("./capture-europe-pmc-quality.mjs", import.meta.url),
);
const OUTPUT_ROOT = resolve(
  fileURLToPath(new URL("../backend/target/provider-quality/", import.meta.url)),
);
const APPLICATION_TOKEN = "fake-application-token";
const METRICS_TOKEN = "fake-management-token";
const createdOutputDirectories = new Set();

try {
  await validateFrozenQuerySet();
  await rejectUnsafeOriginsWithoutNetworkAccess();
  await exerciseSuccessfulSplitOriginCapture();
  await exercisePartialProviderFailure();
  await rejectRedirects();
  await rejectInvalidAndAmbiguousEvidence();
  await rejectIncompleteTelemetry();
  await enforceResponseBounds();
  process.stdout.write("Provider-quality capture utility fake-server checks passed.\n");
} catch (error) {
  process.stderr.write(
    `test-capture-europe-pmc-quality: ${error instanceof Error ? error.stack : String(error)}\n`,
  );
  process.exitCode = 1;
} finally {
  for (const outputDirectory of createdOutputDirectories) {
    await removeTestOutputDirectory(outputDirectory);
  }
}

async function validateFrozenQuerySet() {
  const result = await runCapture(["--validate-query-set"]);
  assert.equal(result.code, 0, result.stderr);
  const summary = parseProcessJson(result.stdout);
  assert.deepEqual(
    {
      querySetId: summary.querySetId,
      queryCount: summary.queryCount,
      pageSize: summary.pageSize,
    },
    {
      querySetId: "europe-pmc-live-queries-v1",
      queryCount: 8,
      pageSize: 20,
    },
  );
  assert.match(summary.sha256, /^[0-9a-f]{64}$/);
}

async function rejectUnsafeOriginsWithoutNetworkAccess() {
  const cases = [
    {
      arguments: ["--base-url", "not-an-absolute-url"],
      error: /must be a valid absolute URL/,
    },
    {
      arguments: ["--base-url", "ftp://127.0.0.1:8080"],
      error: /must use HTTP or HTTPS/,
    },
    {
      arguments: ["--base-url", "http://example.invalid"],
      error: /accepts loopback targets only/,
    },
    {
      arguments: ["--base-url", "http://example.invalid"],
      environment: { ALLOW_REMOTE_PROVIDER_QUALITY_TARGET: "true" },
      error: /approved remote targets must use HTTPS/,
    },
    {
      arguments: ["--base-url", "http://127.0.0.1:8080/not-an-origin"],
      error: /must be an origin without credentials, path, query, or fragment/,
    },
  ];
  for (const testCase of cases) {
    const result = await runCapture(testCase.arguments, testCase.environment);
    assert.equal(result.code, 1, result.stdout);
    assert.match(result.stderr, testCase.error);
  }
}

async function exerciseSuccessfulSplitOriginCapture() {
  await withFakeServers({}, async ({ applicationOrigin, managementOrigin, state }) => {
    const result = await runCapture(
      [
        "--base-url",
        applicationOrigin,
        "--management-url",
        managementOrigin,
      ],
      authenticatedEnvironment(),
    );
    assert.equal(result.code, 0, result.stderr);
    assert.equal(state.searchRequests, 8);
    assert.equal(state.metricsScrapes, 2);
    assert.deepEqual(state.handlerErrors, []);
    assert.ok(state.applicationAuthorizations.length >= 9);
    assert.ok(
      state.applicationAuthorizations.every(
        (value) => value === `Bearer ${APPLICATION_TOKEN}`,
      ),
    );
    assert.deepEqual(
      state.metricsAuthorizations,
      [`Bearer ${METRICS_TOKEN}`, `Bearer ${METRICS_TOKEN}`],
    );

    const processReport = parseProcessJson(result.stdout);
    assert.equal(processReport.qualityReviewEligible, true);
    assert.deepEqual(processReport.captureIssues, []);
    assert.equal(processReport.queryCount, 8);
    assert.equal(processReport.providerTelemetry.OPENALEX.requests, 8);
    assert.equal(processReport.providerTelemetry.EUROPE_PMC.requests, 8);
    assert.equal(processReport.providerTelemetry.OPENALEX.durationSamples, 8);
    assert.equal(processReport.providerTelemetry.EUROPE_PMC.durationSamples, 8);
    assert.equal(processReport.providerTelemetry.OPENALEX.resultSamples, 8);
    assert.equal(processReport.providerTelemetry.EUROPE_PMC.resultSamples, 8);
    assert.equal(processReport.providerTelemetry.OPENALEX.returnedRecords, 8);
    assert.equal(processReport.providerTelemetry.EUROPE_PMC.returnedRecords, 8);
    const artifacts = await readAndRegisterArtifacts(processReport.outputDirectory);
    assert.equal(artifacts.summary.boundaries.metadataOnly, true);
    assert.equal(artifacts.summary.boundaries.fetchesPdf, false);
    assert.equal(artifacts.summary.boundaries.fetchesFullText, false);
    assert.equal(artifacts.summary.boundaries.qualityReviewEligible, true);
    assert.equal(artifacts.summary.queries.length, 8);
    assert.equal(artifacts.blinded.candidates.length, 8);
    assert.equal(artifacts.provenance.candidates.length, 8);
    assertArtifactSeparation(artifacts.blinded, artifacts.provenance);
    const serializedArtifacts = JSON.stringify(artifacts);
    assert.equal(serializedArtifacts.includes(APPLICATION_TOKEN), false);
    assert.equal(serializedArtifacts.includes(METRICS_TOKEN), false);
    await assertRestrictiveArtifactModes(processReport.outputDirectory);
  });
}

async function exercisePartialProviderFailure() {
  await withFakeServers(
    { partialProviderFailure: true, cacheDisposition: "FORCED_REFRESH" },
    async ({ applicationOrigin, managementOrigin, state }) => {
      const result = await runCapture(
        [
          "--base-url",
          applicationOrigin,
          "--management-url",
          managementOrigin,
        ],
        authenticatedEnvironment(),
      );
      assert.equal(result.code, 2, result.stderr);
      assert.equal(state.searchRequests, 8);
      assert.equal(state.metricsScrapes, 2);
      assert.deepEqual(state.handlerErrors, []);
      const processReport = parseProcessJson(result.stdout);
      assert.equal(processReport.qualityReviewEligible, false);
      assert.equal(processReport.providerTelemetry.EUROPE_PMC.failures, 1);
      assert.equal(processReport.providerTelemetry.EUROPE_PMC.durationSamples, 8);
      assert.equal(processReport.providerTelemetry.EUROPE_PMC.resultSamples, 7);
      assert.equal(processReport.providerTelemetry.EUROPE_PMC.returnedRecords, 7);
      assert.ok(
        processReport.captureIssues.some((issue) => issue.includes("EUROPE_PMC:FAILED")),
      );
      assert.ok(
        processReport.captureIssues.some(
          (issue) => issue === "EUROPE_PMC:TELEMETRY_FAILURES:1",
        ),
      );
      const artifacts = await readAndRegisterArtifacts(processReport.outputDirectory);
      assert.equal(artifacts.summary.boundaries.qualityReviewEligible, false);
      assert.match(artifacts.blinded.instructions, /Do not use this incomplete capture/);
      assert.doesNotMatch(artifacts.blinded.instructions, /Assign one integer/);
    },
  );
}

async function rejectRedirects() {
  for (const redirectEndpoint of ["status", "search", "metrics"]) {
    await withFakeServers(
      { redirectEndpoint },
      async ({ applicationOrigin, managementOrigin, state }) => {
        const result = await runCapture(
          [
            "--base-url",
            applicationOrigin,
            "--management-url",
            managementOrigin,
          ],
          authenticatedEnvironment(),
        );
        assert.equal(result.code, 1, result.stdout);
        assert.match(result.stderr, /fetch failed/);
        assert.equal(state.redirectTargetsReached, 0);
        assert.deepEqual(state.handlerErrors, []);
      },
    );
  }
}

async function rejectInvalidAndAmbiguousEvidence() {
  const cases = [
    {
      options: { invalidResultSchema: true },
      error: /invalid authors or provenance/,
    },
    {
      options: { identifiersArray: true },
      error: /invalid identifiers/,
    },
    {
      options: { responseQueryMismatch: true },
      error: /does not match the requested query text/,
    },
    {
      options: { requestedModeMismatch: true },
      error: /requestedMode must be ONLINE/,
    },
    {
      options: { executionSourceMismatch: true },
      error: /executionSource must be PROVIDER_FETCH/,
    },
    {
      options: { cacheDispositionMismatch: true },
      error: /cacheDisposition must be MISS_FETCHED or FORCED_REFRESH/,
    },
    {
      options: { duplicatePaperId: true },
      error: /repeats canonical paperId/,
    },
    {
      options: { duplicateProviderRecord: true },
      error: /maps OPENALEX record .* to multiple canonical results/,
    },
  ];
  for (const testCase of cases) {
    await withFakeServers(
      testCase.options,
      async ({ applicationOrigin, managementOrigin, state }) => {
        const result = await runCapture(
          [
            "--base-url",
            applicationOrigin,
            "--management-url",
            managementOrigin,
          ],
          authenticatedEnvironment(),
        );
        assert.equal(result.code, 1, result.stdout);
        assert.match(result.stderr, testCase.error);
        assert.deepEqual(state.handlerErrors, []);
      },
    );
  }
}

async function rejectIncompleteTelemetry() {
  const cases = [
    {
      options: { omitDurationCount: true },
      error: /duration samples; expected 8/,
    },
    {
      options: { omitResultCount: true },
      error: /result samples; expected 8/,
    },
    {
      options: { wrongReturnedRecordSum: true },
      error: /returned records; captured coverage contains 8/,
    },
  ];
  for (const testCase of cases) {
    await withFakeServers(
      testCase.options,
      async ({ applicationOrigin, managementOrigin, state }) => {
        const result = await runCapture(
          [
            "--base-url",
            applicationOrigin,
            "--management-url",
            managementOrigin,
          ],
          authenticatedEnvironment(),
        );
        assert.equal(result.code, 1, result.stdout);
        assert.match(result.stderr, testCase.error);
        assert.equal(state.searchRequests, 8);
        assert.equal(state.metricsScrapes, 2);
        assert.deepEqual(state.handlerErrors, []);
      },
    );
  }
}

async function enforceResponseBounds() {
  const cases = [
    { oversizedEndpoint: "status" },
    { oversizedEndpoint: "metrics" },
    { streamedOversizedMetrics: true },
  ];
  for (const options of cases) {
    await withFakeServers(
      options,
      async ({ applicationOrigin, managementOrigin, state }) => {
        const result = await runCapture(
          [
            "--base-url",
            applicationOrigin,
            "--management-url",
            managementOrigin,
          ],
          authenticatedEnvironment(),
        );
        assert.equal(result.code, 1, result.stdout);
        assert.match(result.stderr, /exceeded the \d+-byte response limit/);
        assert.deepEqual(state.handlerErrors, []);
      },
    );
  }
}

function authenticatedEnvironment() {
  return {
    OPENSCHOLAR_PROVIDER_QUALITY_BEARER_TOKEN: APPLICATION_TOKEN,
    OPENSCHOLAR_PROVIDER_QUALITY_METRICS_BEARER_TOKEN: METRICS_TOKEN,
  };
}

async function withFakeServers(options, operation) {
  const state = {
    searchRequests: 0,
    metricsScrapes: 0,
    redirectTargetsReached: 0,
    applicationAuthorizations: [],
    metricsAuthorizations: [],
    handlerErrors: [],
  };
  const application = createServer((request, response) => {
    void handleApplicationRequest(request, response, options, state).catch((error) => {
      state.handlerErrors.push(error instanceof Error ? error.message : String(error));
      if (!response.headersSent) response.writeHead(500, { "content-type": "text/plain" });
      response.end("fake application failure");
    });
  });
  const management = createServer((request, response) => {
    void handleManagementRequest(request, response, options, state).catch((error) => {
      state.handlerErrors.push(error instanceof Error ? error.message : String(error));
      if (!response.headersSent) response.writeHead(500, { "content-type": "text/plain" });
      response.end("fake management failure");
    });
  });
  application.keepAliveTimeout = 1;
  management.keepAliveTimeout = 1;
  try {
    const applicationOrigin = await listenOnLoopback(application);
    const managementOrigin = await listenOnLoopback(management);
    await operation({ applicationOrigin, managementOrigin, state });
  } finally {
    await Promise.all([closeServer(application), closeServer(management)]);
  }
}

async function handleApplicationRequest(request, response, options, state) {
  const url = new URL(request.url ?? "/", "http://fake-application.invalid");
  state.applicationAuthorizations.push(request.headers.authorization);
  if (request.headers.authorization !== `Bearer ${APPLICATION_TOKEN}`) {
    sendJson(response, 401, { code: "UNAUTHORIZED" });
    return;
  }
  if (url.pathname === "/redirect-target") {
    state.redirectTargetsReached += 1;
    sendJson(response, 200, { status: "UP" });
    return;
  }
  if (request.method === "GET" && url.pathname === "/api/v1/system/status") {
    if (options.redirectEndpoint === "status") {
      sendRedirect(response);
      return;
    }
    if (options.oversizedEndpoint === "status") {
      sendDeclaredOversize(response, 16 * 1024 * 1024 + 1, "application/json");
      return;
    }
    sendJson(response, 200, { status: "UP" });
    return;
  }
  if (request.method === "POST" && url.pathname === "/api/v1/searches") {
    if (options.redirectEndpoint === "search") {
      sendRedirect(response);
      return;
    }
    const requestBody = await readRequestJson(request);
    assertSearchRequest(requestBody);
    state.searchRequests += 1;
    sendJson(
      response,
      201,
      fakeSearchResponse(state.searchRequests, requestBody.query, options),
    );
    return;
  }
  sendJson(response, 404, { code: "NOT_FOUND" });
}

async function handleManagementRequest(request, response, options, state) {
  const url = new URL(request.url ?? "/", "http://fake-management.invalid");
  state.metricsAuthorizations.push(request.headers.authorization);
  if (request.headers.authorization !== `Bearer ${METRICS_TOKEN}`) {
    sendJson(response, 401, { code: "UNAUTHORIZED" });
    return;
  }
  if (url.pathname === "/redirect-target") {
    state.redirectTargetsReached += 1;
    response.writeHead(200, { "content-type": "text/plain" });
    response.end(fakeMetrics(state, options));
    return;
  }
  if (request.method === "GET" && url.pathname === "/actuator/prometheus") {
    state.metricsScrapes += 1;
    if (options.redirectEndpoint === "metrics") {
      sendRedirect(response);
      return;
    }
    if (options.oversizedEndpoint === "metrics") {
      sendDeclaredOversize(response, 8 * 1024 * 1024 + 1, "text/plain");
      return;
    }
    if (options.streamedOversizedMetrics) {
      response.writeHead(200, { "content-type": "text/plain" });
      response.end(Buffer.alloc(8 * 1024 * 1024 + 1, 0x78));
      return;
    }
    const body = fakeMetrics(state, options);
    response.writeHead(200, {
      "content-type": "text/plain; version=0.0.4",
      "content-length": Buffer.byteLength(body),
    });
    response.end(body);
    return;
  }
  sendJson(response, 404, { code: "NOT_FOUND" });
}

function fakeSearchResponse(searchNumber, requestedQuery, options) {
  const partialFailure = options.partialProviderFailure && searchNumber === 4;
  const first = fakeResult(searchNumber, 1, partialFailure ? ["OPENALEX"] : undefined);
  const results = [first];
  if (options.invalidResultSchema && searchNumber === 1) delete first.authors;
  if (options.identifiersArray && searchNumber === 1) first.identifiers = [];
  if (options.duplicatePaperId && searchNumber === 1) {
    const duplicate = fakeResult(searchNumber, 2);
    duplicate.paperId = first.paperId;
    results.push(duplicate);
  }
  if (options.duplicateProviderRecord && searchNumber === 1) {
    const duplicate = fakeResult(searchNumber, 2);
    duplicate.provenance[0].providerRecordId = first.provenance[0].providerRecordId;
    results.push(duplicate);
  }
  return {
    searchId: `fake-search-${searchNumber}`,
    query: options.responseQueryMismatch ? `${requestedQuery} mismatch` : requestedQuery,
    requestedMode: options.requestedModeMismatch ? "AUTO" : "ONLINE",
    executionSource: options.executionSourceMismatch ? "EXACT_CACHE" : "PROVIDER_FETCH",
    cacheDisposition: options.cacheDispositionMismatch
      ? "EXACT_HIT"
      : options.cacheDisposition ?? "MISS_FETCHED",
    providerCoverage: [
      {
        provider: "OPENALEX",
        status: "SUCCESS",
        returnedCount: 1,
        totalMatches: 1,
      },
      {
        provider: "EUROPE_PMC",
        status: partialFailure ? "FAILED" : "SUCCESS",
        returnedCount: partialFailure ? 0 : 1,
        totalMatches: partialFailure ? 0 : 1,
      },
    ],
    warnings: partialFailure ? ["EUROPE_PMC synthetic failure"] : [],
    results,
  };
}

function fakeResult(searchNumber, rank, providers = ["OPENALEX", "EUROPE_PMC"]) {
  return {
    rank,
    paperId: `fake-paper-${searchNumber}-${rank}`,
    title: `Synthetic metadata result ${searchNumber}-${rank}`,
    abstractText: "Synthetic abstract text for capture utility verification.",
    authors: [
      {
        name: "Synthetic Author",
        orcid: "https://fixtures.openscholar.test/orcid/capture-author",
        openAlexId: "https://openalex.org/A-FAKE-CAPTURE",
      },
    ],
    publicationDate: "2025-01-01",
    publicationYear: 2025,
    documentType: "ARTICLE",
    language: "en",
    venue: "Synthetic Journal",
    identifiers: {
      doi: `10.5555/capture.${searchNumber}.${rank}`,
      arxiv: null,
      openAlex: `https://openalex.org/W-FAKE-${searchNumber}-${rank}`,
    },
    provenance: providers.map((provider) => ({
      provider,
      providerRecordId: provider === "OPENALEX"
        ? `W-FAKE-${searchNumber}-${rank}`
        : `MED:FAKE-${searchNumber}-${rank}`,
      retrievedAt: "2026-08-26T09:00:00Z",
    })),
  };
}

function fakeMetrics(state, options) {
  const europePmcFailures = options.partialProviderFailure && state.searchRequests >= 4 ? 1 : 0;
  return [
    ...providerMetrics("OPENALEX", state.searchRequests, 0, options),
    ...providerMetrics("EUROPE_PMC", state.searchRequests, europePmcFailures, options),
    "",
  ].join("\n");
}

function providerMetrics(provider, requests, failures, options) {
  const successes = requests - failures;
  const durationSeconds = (requests * 0.1).toFixed(1);
  const returnedRecords = options.wrongReturnedRecordSum ? 0 : successes;
  return [
    `openscholar_provider_requests_total{provider="${provider}",outcome="success",retryable="false"} ${successes}`,
    `openscholar_provider_requests_total{provider="${provider}",outcome="failure",retryable="true"} ${failures}`,
    `openscholar_provider_request_duration_seconds_sum{provider="${provider}"} ${durationSeconds}`,
    ...options.omitDurationCount
      ? []
      : [`openscholar_provider_request_duration_seconds_count{provider="${provider}"} ${requests}`],
    `openscholar_provider_results_records_sum{provider="${provider}"} ${returnedRecords}`,
    ...options.omitResultCount
      ? []
      : [`openscholar_provider_results_records_count{provider="${provider}"} ${successes}`],
  ];
}

function assertSearchRequest(value) {
  assert.equal(value?.pageSize, 20);
  assert.equal(value?.forceRefresh, true);
  assert.equal(value?.mode, "ONLINE");
  assert.deepEqual(value?.filters?.documentTypes, ["ARTICLE"]);
  assert.equal(value?.filters?.openAccessOnly, false);
  assert.equal(value?.filters?.minimumCitations, 0);
  assert.deepEqual(value?.filters?.languages, []);
  assert.equal(typeof value?.query, "string");
  assert.ok(value.query.length >= 3);
}

async function readRequestJson(request) {
  const chunks = [];
  let receivedBytes = 0;
  for await (const chunk of request) {
    receivedBytes += chunk.length;
    if (receivedBytes > 1024 * 1024) throw new Error("fake request exceeded 1 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function sendJson(response, status, value) {
  const body = `${JSON.stringify(value)}\n`;
  response.writeHead(status, {
    "content-type": "application/json",
    "content-length": Buffer.byteLength(body),
  });
  response.end(body);
}

function sendRedirect(response) {
  response.writeHead(302, { location: "/redirect-target" });
  response.end();
}

function sendDeclaredOversize(response, bytes, contentType) {
  response.writeHead(200, {
    "content-type": contentType,
    "content-length": String(bytes),
    connection: "close",
  });
  response.end();
}

async function listenOnLoopback(server) {
  await new Promise((resolveListening, rejectListening) => {
    server.once("error", rejectListening);
    server.listen(0, "127.0.0.1", resolveListening);
  });
  const address = server.address();
  assert.ok(address && typeof address === "object");
  return `http://127.0.0.1:${address.port}`;
}

async function closeServer(server) {
  if (!server.listening) return;
  await new Promise((resolveClose, rejectClose) => {
    server.close((error) => (error ? rejectClose(error) : resolveClose()));
  });
}

async function runCapture(argumentsList, environmentOverrides = {}) {
  const environment = { ...process.env };
  delete environment.ALLOW_REMOTE_PROVIDER_QUALITY_TARGET;
  delete environment.OPENSCHOLAR_PROVIDER_QUALITY_BEARER_TOKEN;
  delete environment.OPENSCHOLAR_PROVIDER_QUALITY_METRICS_BEARER_TOKEN;
  Object.assign(environment, environmentOverrides ?? {});

  const child = spawn(process.execPath, [CAPTURE_SCRIPT, ...argumentsList], {
    cwd: REPOSITORY_ROOT,
    env: environment,
    stdio: ["ignore", "pipe", "pipe"],
  });
  const stdout = [];
  const stderr = [];
  child.stdout.on("data", (chunk) => stdout.push(chunk));
  child.stderr.on("data", (chunk) => stderr.push(chunk));
  let timedOut = false;
  const timeout = setTimeout(() => {
    timedOut = true;
    child.kill("SIGKILL");
  }, 45_000);
  const completion = await new Promise((resolveCompletion, rejectCompletion) => {
    child.once("error", rejectCompletion);
    child.once("close", (code, signal) => resolveCompletion({ code, signal }));
  });
  clearTimeout(timeout);
  assert.equal(timedOut, false, "capture process exceeded 45 seconds");
  assert.equal(completion.signal, null, `capture process ended by ${completion.signal}`);
  return {
    code: completion.code,
    stdout: Buffer.concat(stdout).toString("utf8"),
    stderr: Buffer.concat(stderr).toString("utf8"),
  };
}

function parseProcessJson(text) {
  assert.ok(text.trim().length > 0, "capture process produced no JSON report");
  return JSON.parse(text);
}

async function readAndRegisterArtifacts(outputDirectory) {
  const resolvedOutput = resolve(outputDirectory);
  assert.equal(dirname(resolvedOutput), OUTPUT_ROOT);
  assert.match(basename(resolvedOutput), /^europe-pmc-live-[0-9TZ-]+$/);
  createdOutputDirectories.add(resolvedOutput);
  const [summary, blinded, provenance] = await Promise.all([
    readJson(resolve(resolvedOutput, "summary.json")),
    readJson(resolve(resolvedOutput, "blinded-candidates.json")),
    readJson(resolve(resolvedOutput, "provenance-map.json")),
  ]);
  assert.equal(summary.captureId, blinded.captureId);
  assert.equal(summary.captureId, provenance.captureId);
  return { summary, blinded, provenance };
}

async function readJson(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

function assertArtifactSeparation(blinded, provenance) {
  const blindedKeys = blinded.candidates.map((candidate) => candidate.reviewKey);
  const provenanceKeys = provenance.candidates.map((candidate) => candidate.reviewKey);
  assert.equal(new Set(blindedKeys).size, blindedKeys.length);
  assert.equal(new Set(provenanceKeys).size, provenanceKeys.length);
  assert.deepEqual([...blindedKeys].sort(), [...provenanceKeys].sort());
  for (const candidate of blinded.candidates) {
    assert.equal("paperId" in candidate, false);
    assert.equal("identifiers" in candidate, false);
    assert.equal("provenance" in candidate, false);
    assert.ok(candidate.authors.every((author) => !("openAlexId" in author)));
  }
  for (const candidate of provenance.candidates) {
    assert.equal("title" in candidate, false);
    assert.equal("abstractText" in candidate, false);
    assert.equal("authors" in candidate, false);
    assert.ok(Array.isArray(candidate.provenance));
    assert.ok(candidate.identifiers && typeof candidate.identifiers === "object");
  }
}

async function assertRestrictiveArtifactModes(outputDirectory) {
  const directoryMode = (await stat(outputDirectory)).mode & 0o777;
  assert.equal(directoryMode, 0o700);
  for (const filename of [
    "summary.json",
    "blinded-candidates.json",
    "provenance-map.json",
  ]) {
    const fileMode = (await stat(resolve(outputDirectory, filename))).mode & 0o777;
    assert.equal(fileMode, 0o600);
  }
}

async function removeTestOutputDirectory(outputDirectory) {
  const resolvedOutput = resolve(outputDirectory);
  if (dirname(resolvedOutput) !== OUTPUT_ROOT
      || !/^europe-pmc-live-[0-9TZ-]+$/.test(basename(resolvedOutput))) {
    throw new Error(`refusing to remove unexpected test output: ${resolvedOutput}`);
  }
  await rm(resolvedOutput, { recursive: true, force: true });
}
