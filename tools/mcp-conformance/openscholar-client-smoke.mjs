import assert from "node:assert/strict";

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

const requestOptions = { timeout: 10_000 };
const expectedTools = [
  "export_citations",
  "get_legal_full_text",
  "get_paper_details",
  "resolve_paper_identifier",
  "search_research",
  "search_saved_library",
];
const expectedTemplates = [
  "openscholar://collections/{collectionId}",
  "openscholar://papers/{paperId}",
  "openscholar://searches/{searchId}",
];
const canonicalUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function requireLocalUrl(value, label, expectedPath) {
  const url = new URL(value);
  const loopbackHosts = new Set(["127.0.0.1", "localhost", "[::1]"]);

  assert.equal(url.protocol, "http:", `${label} must use loopback HTTP`);
  assert.ok(loopbackHosts.has(url.hostname), `${label} must use a loopback host`);
  assert.equal(url.username, "", `${label} must not contain credentials`);
  assert.equal(url.password, "", `${label} must not contain credentials`);
  assert.equal(url.search, "", `${label} must not contain a query string`);
  assert.equal(url.hash, "", `${label} must not contain a fragment`);
  assert.equal(url.pathname, expectedPath, `${label} must use ${expectedPath}`);

  return url;
}

function requireApiKey() {
  const apiKey = process.env.MCP_LOCAL_API_KEY?.trim();
  assert.ok(apiKey, "MCP_LOCAL_API_KEY is required");
  return apiKey;
}

async function requestCollection(apiOrigin, path, init, expectedStatus) {
  const response = await fetch(new URL(path, apiOrigin), {
    redirect: "error",
    signal: AbortSignal.timeout(requestOptions.timeout),
    ...init,
  });

  assert.equal(
    response.status,
    expectedStatus,
    `Unexpected collection API status for ${init.method} ${path}`,
  );
  return response;
}

function collectionIdFromLocation(location) {
  if (location === null) return undefined;
  const match = /^\/api\/v1\/collections\/([^/]+)$/.exec(location);
  return match !== null && canonicalUuid.test(match[1]) ? match[1] : undefined;
}

async function createCollection(apiOrigin, rememberCollectionId) {
  const response = await requestCollection(
    apiOrigin,
    "/api/v1/collections",
    {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        name: `MCP SDK smoke ${process.pid}-${Date.now()}`,
        description: "Disposable collection created by the official MCP SDK smoke test.",
      }),
    },
    201,
  );
  const locationCollectionId = collectionIdFromLocation(
    response.headers.get("location"),
  );
  if (locationCollectionId !== undefined) {
    rememberCollectionId(locationCollectionId);
  }

  const collection = await response.json();
  const bodyCollectionId =
    typeof collection.collectionId === "string" &&
    canonicalUuid.test(collection.collectionId)
      ? collection.collectionId
      : undefined;
  if (locationCollectionId === undefined && bodyCollectionId !== undefined) {
    rememberCollectionId(bodyCollectionId);
  }

  assert.ok(
    locationCollectionId,
    "Collection response must have a canonical Location header",
  );
  assert.equal(bodyCollectionId, locationCollectionId);
  assert.equal(collection.paperCount, 0);
  return collection;
}

async function deleteCollection(apiOrigin, collectionId) {
  await requestCollection(
    apiOrigin,
    `/api/v1/collections/${encodeURIComponent(collectionId)}`,
    { method: "DELETE" },
    204,
  );
}

function assertResourceCapability(client) {
  const capabilities = client.getServerCapabilities();
  assert.ok(capabilities?.resources, "Server did not advertise MCP resources");
  assert.equal(Object.hasOwn(capabilities.resources, "subscribe"), false);
  assert.equal(Object.hasOwn(capabilities.resources, "listChanged"), false);
}

async function assertCollectionResource(client, collectionId) {
  const uri = `openscholar://collections/${collectionId}`;
  const result = await client.readResource({ uri }, requestOptions);

  assert.equal(result.contents.length, 1);
  const [content] = result.contents;
  assert.equal(content.uri, uri);
  assert.equal(content.mimeType, "application/json");
  assert.ok("text" in content, "Collection resource must contain JSON text");

  const payload = JSON.parse(content.text);
  assert.equal(payload.schemaVersion, 1);
  assert.equal(payload.collectionId, collectionId);
  assert.equal(payload.paperCount, 0);
  assert.deepEqual(payload.papers.items, []);
  assert.equal(payload.papers.page, 0);
  assert.equal(payload.papers.size, 25);
  assert.equal(payload.papers.totalElements, 0);
}

async function run() {
  const mcpUrl = requireLocalUrl(
    process.env.OPENSCHOLAR_SMOKE_MCP_URL ?? "http://127.0.0.1:8080/mcp",
    "OPENSCHOLAR_SMOKE_MCP_URL",
    "/mcp",
  );
  const apiOrigin = requireLocalUrl(
    process.env.OPENSCHOLAR_SMOKE_API_ORIGIN ?? "http://127.0.0.1:8080/",
    "OPENSCHOLAR_SMOKE_API_ORIGIN",
    "/",
  );
  const apiKey = requireApiKey();
  const client = new Client(
    { name: "openscholar-sdk-smoke", version: "1.0.0" },
    { capabilities: {} },
  );
  const transport = new StreamableHTTPClientTransport(mcpUrl, {
    requestInit: {
      headers: { Authorization: `Bearer ${apiKey}` },
    },
  });

  let collectionId;
  let primaryError;
  const cleanupErrors = [];

  try {
    await client.connect(transport, requestOptions);
    assert.equal(client.getServerVersion()?.name, "openscholar-mcp");
    assertResourceCapability(client);
    await client.ping(requestOptions);

    const toolResult = await client.listTools(undefined, requestOptions);
    assert.equal(toolResult.nextCursor, undefined);
    assert.deepEqual(
      toolResult.tools.map((tool) => tool.name).sort(),
      expectedTools,
    );

    const templateResult = await client.listResourceTemplates(
      undefined,
      requestOptions,
    );
    assert.equal(templateResult.nextCursor, undefined);
    assert.deepEqual(
      templateResult.resourceTemplates
        .map((template) => template.uriTemplate)
        .sort(),
      expectedTemplates,
    );

    const resourceResult = await client.listResources(undefined, requestOptions);
    assert.equal(resourceResult.nextCursor, undefined);
    assert.deepEqual(resourceResult.resources, []);

    await createCollection(apiOrigin, (createdCollectionId) => {
      collectionId = createdCollectionId;
    });
    await assertCollectionResource(client, collectionId);
  } catch (error) {
    primaryError = error;
  } finally {
    if (collectionId !== undefined) {
      try {
        await deleteCollection(apiOrigin, collectionId);
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    try {
      await client.close();
    } catch (error) {
      cleanupErrors.push(error);
    }
  }

  const failures = [
    ...(primaryError === undefined ? [] : [primaryError]),
    ...cleanupErrors,
  ];
  if (failures.length === 1) throw failures[0];
  if (failures.length > 1) {
    throw new AggregateError(failures, "MCP SDK smoke and cleanup failed");
  }

  process.stdout.write(
    "Official MCP SDK smoke passed: negotiation, ping, tools, templates, empty listing, and collection read.\n",
  );
}

await run();
