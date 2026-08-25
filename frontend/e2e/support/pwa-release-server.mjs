import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const host = "127.0.0.1";
const port = Number(process.env.PLAYWRIGHT_PWA_RELEASE_PORT ?? 5_100);
if (!Number.isInteger(port) || port < 1 || port > 65_535) {
  throw new Error(
    "PLAYWRIGHT_PWA_RELEASE_PORT must be an integer from 1 to 65535.",
  );
}

const supportRoot = dirname(fileURLToPath(import.meta.url));
const frontendRoot = join(supportRoot, "..", "..");
const publicRoot = join(frontendRoot, "public");
const currentWorker = readFileSync(join(publicRoot, "sw.js"), "utf8");
const currentOfflineShell = readFileSync(
  join(publicRoot, "offline.html"),
  "utf8",
);
const currentOfflineRuntime = readFileSync(
  join(publicRoot, "offline-pack.js"),
  "utf8",
);

function extractRevision(source, pattern, label) {
  const matches = [...source.matchAll(pattern)];
  if (matches.length !== 1 || matches[0][1] === undefined) {
    throw new Error(`${label} must contain exactly one reader revision marker.`);
  }
  return matches[0][1];
}

const workerRevision = extractRevision(
  currentWorker,
  /const OFFLINE_READER_REVISION = "([A-Za-z0-9._-]{1,128})";/gu,
  "public/sw.js",
);
const shellRevision = extractRevision(
  currentOfflineShell,
  /data-offline-reader-revision="([A-Za-z0-9._-]{1,128})"/gu,
  "public/offline.html",
);
const runtimeRevision = extractRevision(
  currentOfflineRuntime,
  /const READER_REVISION = "([A-Za-z0-9._-]{1,128})";/gu,
  "public/offline-pack.js",
);
if (workerRevision !== shellRevision || workerRevision !== runtimeRevision) {
  throw new Error(
    "The checked-in service worker, offline shell, and offline runtime must be coherent.",
  );
}

const currentRevision = workerRevision;
const numberedRevision = /^(.*-r)(\d+)$/u.exec(currentRevision);
const previousRevision =
  numberedRevision !== null && BigInt(numberedRevision[2]) > 0n
    ? `${numberedRevision[1]}${BigInt(numberedRevision[2]) - 1n}`
    : `${currentRevision}-e2e-previous`;
const rollbackRevision =
  numberedRevision !== null
    ? `${numberedRevision[1]}${BigInt(numberedRevision[2]) + 1n}`
    : `${currentRevision}-e2e-forward-rollback`;

function replaceRevision(source, expected, replacement, label) {
  const pieces = source.split(expected);
  if (pieces.length !== 2) {
    throw new Error(`${label} must contain its reader revision exactly once.`);
  }
  return `${pieces[0]}${replacement}${pieces[1]}`;
}

const previousWorker = replaceRevision(
  currentWorker,
  currentRevision,
  previousRevision,
  "public/sw.js",
);
const previousOfflineShell = replaceRevision(
  currentOfflineShell,
  currentRevision,
  previousRevision,
  "public/offline.html",
);
const previousOfflineRuntime = replaceRevision(
  currentOfflineRuntime,
  currentRevision,
  previousRevision,
  "public/offline-pack.js",
);
const rollbackWorker = replaceRevision(
  currentWorker,
  currentRevision,
  rollbackRevision,
  "public/sw.js",
);
const rollbackOfflineShell = replaceRevision(
  currentOfflineShell,
  currentRevision,
  rollbackRevision,
  "public/offline.html",
);
const rollbackOfflineRuntime = replaceRevision(
  currentOfflineRuntime,
  currentRevision,
  rollbackRevision,
  "public/offline-pack.js",
);

const releaseFiles = Object.freeze({
  previous: Object.freeze({
    worker: previousWorker,
    shell: previousOfflineShell,
    runtime: previousOfflineRuntime,
    workerRevision: previousRevision,
    assetRevision: previousRevision,
  }),
  current: Object.freeze({
    worker: currentWorker,
    shell: currentOfflineShell,
    runtime: currentOfflineRuntime,
    workerRevision: currentRevision,
    assetRevision: currentRevision,
  }),
  incoherent: Object.freeze({
    worker: currentWorker,
    shell: previousOfflineShell,
    runtime: previousOfflineRuntime,
    workerRevision: currentRevision,
    assetRevision: previousRevision,
  }),
  rollback: Object.freeze({
    worker: rollbackWorker,
    shell: rollbackOfflineShell,
    runtime: rollbackOfflineRuntime,
    workerRevision: rollbackRevision,
    assetRevision: rollbackRevision,
  }),
});

const manifest = JSON.stringify({
  id: "/",
  name: "OpenScholar PWA lifecycle fixture",
  short_name: "OpenScholar fixture",
  start_url: "/",
  scope: "/",
  display: "standalone",
  background_color: "#f4f1e8",
  theme_color: "#155c47",
  icons: [
    { src: "/icon-192.png", sizes: "192x192", type: "image/png" },
    { src: "/icon-512.png", sizes: "512x512", type: "image/png" },
    { src: "/icon.svg", sizes: "any", type: "image/svg+xml" },
  ],
});
const staticFiles = new Map([
  [
    "/manifest.webmanifest",
    { body: manifest, contentType: "application/manifest+json; charset=utf-8" },
  ],
  [
    "/icon.svg",
    {
      body: readFileSync(join(frontendRoot, "src", "app", "icon.svg")),
      contentType: "image/svg+xml",
    },
  ],
  [
    "/icon-192.png",
    {
      body: readFileSync(join(publicRoot, "icon-192.png")),
      contentType: "image/png",
    },
  ],
  [
    "/icon-512.png",
    {
      body: readFileSync(join(publicRoot, "icon-512.png")),
      contentType: "image/png",
    },
  ],
  [
    "/apple-touch-icon.png",
    {
      body: readFileSync(join(publicRoot, "apple-touch-icon.png")),
      contentType: "image/png",
    },
  ],
]);

// Static-looking traps exercise response policy; the final three exercise
// request-path exclusions even when their responses claim to be public.
const decoyRoutes = Object.freeze({
  cacheable: "/_next/static/pwa-e2e/cacheable.js",
  redirected: "/_next/static/pwa-e2e/redirected.js",
  credentialDependent: "/_next/static/pwa-e2e/credential-dependent.js",
  privateResponse: "/_next/static/pwa-e2e/private.js",
  noStoreResponse: "/_next/static/pwa-e2e/no-store.js",
  varyStarResponse: "/_next/static/pwa-e2e/vary-star.js",
  authorizationRequest: "/_next/static/pwa-e2e/authorization.js",
  rangeRequest: "/_next/static/pwa-e2e/range.js",
  queryVariant: "/_next/static/pwa-e2e/query.js",
  api: "/api/pwa-e2e-private",
  auth: "/auth/pwa-e2e-private",
  document: "/files/pwa-e2e-private.pdf",
});
const privateQuery = "?owner=private-researcher";
const credentialCookie = "pwa_e2e_private=credentialed";
const javascriptDecoy = (marker) =>
  `globalThis.__OPENSCHOLAR_PWA_E2E_DECOY__ = ${JSON.stringify(marker)};\n`;
const rangeDecoy = Buffer.from("RANGE_PRIVATE_DECOY", "utf8");
const documentDecoy = Buffer.from(
  "%PDF-1.4\n% OpenScholar DOCUMENT_PRIVATE_DECOY\n%%EOF\n",
  "utf8",
);

let selectedRelease = "previous";
let generation = 0;
const authority = `${host}:${port}`;
const origin = `http://${authority}`;
const maximumControlBodyBytes = 1_024;

function releaseState() {
  const selected = releaseFiles[selectedRelease];
  return {
    ok: true,
    release: selectedRelease,
    generation,
    currentRevision,
    previousRevision,
    rollbackRevision,
    workerRevision: selected.workerRevision,
    assetRevision: selected.assetRevision,
    workerUrl: `/sw.js?reader=${encodeURIComponent(selected.workerRevision)}`,
  };
}

function commonHeaders(contentType, cacheControl = "no-store") {
  return {
    "Cache-Control": cacheControl,
    "Content-Type": contentType,
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
  };
}

function send(request, response, status, body, headers = {}) {
  const payload = Buffer.isBuffer(body) ? body : Buffer.from(body, "utf8");
  response.writeHead(status, {
    ...headers,
    "Content-Length": String(payload.byteLength),
  });
  response.end(request.method === "HEAD" ? undefined : payload);
}

function sendJson(request, response, status, value, headers = {}) {
  send(request, response, status, JSON.stringify(value), {
    ...commonHeaders("application/json; charset=utf-8"),
    ...headers,
  });
}

function sendProblem(request, response, status, error, headers = {}) {
  sendJson(request, response, status, { ok: false, error }, headers);
}

function methodNotAllowed(request, response, allowed) {
  sendProblem(request, response, 405, "METHOD_NOT_ALLOWED", {
    Allow: allowed.join(", "),
  });
}

async function readControlBody(request) {
  const declaredLength = request.headers["content-length"];
  if (declaredLength !== undefined && !/^\d+$/u.test(declaredLength)) {
    request.resume();
    return { error: "INVALID_CONTENT_LENGTH" };
  }
  if (
    declaredLength !== undefined &&
    Number(declaredLength) > maximumControlBodyBytes
  ) {
    request.resume();
    return { error: "CONTROL_BODY_TOO_LARGE" };
  }

  const chunks = [];
  let byteLength = 0;
  let tooLarge = false;
  for await (const chunk of request) {
    byteLength += chunk.byteLength;
    if (byteLength > maximumControlBodyBytes) {
      tooLarge = true;
      chunks.length = 0;
    } else if (!tooLarge) {
      chunks.push(chunk);
    }
  }
  if (tooLarge) return { error: "CONTROL_BODY_TOO_LARGE" };

  let text;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(
      Buffer.concat(chunks),
    );
  } catch {
    return { error: "INVALID_UTF8" };
  }

  try {
    return { value: JSON.parse(text) };
  } catch {
    return { error: "INVALID_JSON" };
  }
}

function isValidReleaseCommand(value) {
  return (
    value !== null &&
    typeof value === "object" &&
    !Array.isArray(value) &&
    Object.keys(value).length === 1 &&
    Object.hasOwn(value, "release") &&
    typeof value.release === "string" &&
    Object.hasOwn(releaseFiles, value.release)
  );
}

function fixturePage() {
  const state = releaseState();
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="robots" content="noindex, nofollow">
    <title>OpenScholar PWA lifecycle fixture</title>
  </head>
  <body data-release="${state.release}" data-generation="${state.generation}">
    <main>
      <h1>OpenScholar PWA lifecycle fixture</h1>
      <p id="release-state">${state.release}: worker ${state.workerRevision}, assets ${state.assetRevision}</p>
      <output id="registration-state" aria-live="polite">registering</output>
    </main>
    <script nonce="openscholar-pwa-fixture">
      navigator.serviceWorker.register(${JSON.stringify(state.workerUrl)}, {
        scope: "/",
        updateViaCache: "none",
      }).then(
        () => { document.querySelector("#registration-state").value = "registered"; },
        () => { document.querySelector("#registration-state").value = "failed"; },
      );
    </script>
  </body>
</html>`;
}

async function handleRequest(request, response) {
  if (request.headers.host !== authority) {
    sendProblem(request, response, 403, "INVALID_HOST");
    return;
  }

  const requestOrigin = request.headers.origin;
  if (requestOrigin !== undefined && requestOrigin !== origin) {
    sendProblem(request, response, 403, "INVALID_ORIGIN");
    return;
  }

  let url;
  try {
    url = new URL(request.url ?? "", origin);
  } catch {
    sendProblem(request, response, 400, "INVALID_URL");
    return;
  }

  if (url.pathname === "/__pwa/health" || url.pathname === "/__pwa/state") {
    if (!(["GET", "HEAD"].includes(request.method ?? ""))) {
      methodNotAllowed(request, response, ["GET", "HEAD"]);
      return;
    }
    if (url.search !== "") {
      sendProblem(request, response, 400, "QUERY_NOT_ALLOWED");
      return;
    }
    sendJson(request, response, 200, releaseState());
    return;
  }

  if (url.pathname === "/__pwa/release") {
    if (request.method !== "POST") {
      methodNotAllowed(request, response, ["POST"]);
      return;
    }
    if (url.search !== "") {
      sendProblem(request, response, 400, "QUERY_NOT_ALLOWED");
      return;
    }
    if (
      (request.headers["content-type"] ?? "")
        .split(";", 1)[0]
        .trim()
        .toLowerCase() !== "application/json"
    ) {
      sendProblem(request, response, 415, "JSON_REQUIRED");
      return;
    }

    const command = await readControlBody(request);
    if (command.error !== undefined) {
      sendProblem(
        request,
        response,
        command.error === "CONTROL_BODY_TOO_LARGE" ? 413 : 400,
        command.error,
      );
      return;
    }
    if (!isValidReleaseCommand(command.value)) {
      sendProblem(request, response, 422, "INVALID_RELEASE_COMMAND");
      return;
    }

    const changed = selectedRelease !== command.value.release;
    selectedRelease = command.value.release;
    generation += 1;
    sendJson(request, response, 200, { ...releaseState(), changed });
    return;
  }

  if (!(["GET", "HEAD"].includes(request.method ?? ""))) {
    methodNotAllowed(request, response, ["GET", "HEAD"]);
    return;
  }

  if (url.pathname === "/") {
    send(request, response, 200, fixturePage(), {
      ...commonHeaders("text/html; charset=utf-8"),
      "Content-Security-Policy":
        "default-src 'none'; script-src 'nonce-openscholar-pwa-fixture'; worker-src 'self'; connect-src 'self'; manifest-src 'self'",
    });
    return;
  }

  if (url.pathname === decoyRoutes.queryVariant) {
    if (url.search !== privateQuery) {
      sendProblem(request, response, 400, "EXPECTED_PRIVATE_QUERY");
      return;
    }
    send(request, response, 200, javascriptDecoy("QUERY_PRIVATE_DECOY"), {
      ...commonHeaders(
        "text/javascript; charset=utf-8",
        "public, max-age=300",
      ),
    });
    return;
  }

  if (Object.values(decoyRoutes).includes(url.pathname) && url.search !== "") {
    sendProblem(request, response, 400, "QUERY_NOT_ALLOWED");
    return;
  }

  if (url.pathname === decoyRoutes.cacheable) {
    send(request, response, 200, javascriptDecoy("PUBLIC_CACHEABLE_DECOY"), {
      ...commonHeaders(
        "text/javascript; charset=utf-8",
        "public, max-age=300",
      ),
    });
    return;
  }

  if (url.pathname === decoyRoutes.redirected) {
    send(request, response, 302, "Redirecting to the API decoy.", {
      ...commonHeaders("text/plain; charset=utf-8", "public, max-age=300"),
      Location: decoyRoutes.api,
    });
    return;
  }

  if (url.pathname === decoyRoutes.credentialDependent) {
    const cookie = request.headers.cookie ?? "";
    const marker = cookie
      .split(";")
      .map((value) => value.trim())
      .includes(credentialCookie)
      ? "COOKIE_PRIVATE_DECOY"
      : "COOKIE_ANONYMOUS_DECOY";
    send(request, response, 200, javascriptDecoy(marker), {
      ...commonHeaders(
        "text/javascript; charset=utf-8",
        "public, max-age=300",
      ),
      "Set-Cookie": `${credentialCookie}; Path=/; HttpOnly; SameSite=Strict`,
      Vary: "Cookie",
    });
    return;
  }

  if (url.pathname === decoyRoutes.privateResponse) {
    send(request, response, 200, javascriptDecoy("PRIVATE_RESPONSE_DECOY"), {
      ...commonHeaders(
        "text/javascript; charset=utf-8",
        "private, max-age=300",
      ),
    });
    return;
  }

  if (url.pathname === decoyRoutes.noStoreResponse) {
    send(request, response, 200, javascriptDecoy("NO_STORE_RESPONSE_DECOY"), {
      ...commonHeaders("text/javascript; charset=utf-8"),
    });
    return;
  }

  if (url.pathname === decoyRoutes.varyStarResponse) {
    send(request, response, 200, javascriptDecoy("VARY_STAR_RESPONSE_DECOY"), {
      ...commonHeaders(
        "text/javascript; charset=utf-8",
        "public, max-age=300",
      ),
      Vary: "*",
    });
    return;
  }

  if (url.pathname === decoyRoutes.authorizationRequest) {
    if (request.headers.authorization !== "Bearer pwa-e2e-private") {
      sendProblem(request, response, 401, "AUTHORIZATION_REQUIRED", {
        "WWW-Authenticate": 'Bearer realm="pwa-e2e"',
      });
      return;
    }
    send(
      request,
      response,
      200,
      javascriptDecoy("AUTHORIZATION_PRIVATE_DECOY"),
      {
        ...commonHeaders(
          "text/javascript; charset=utf-8",
          "public, max-age=300",
        ),
      },
    );
    return;
  }

  if (url.pathname === decoyRoutes.rangeRequest) {
    const range = request.headers.range;
    if (range === undefined) {
      send(request, response, 200, rangeDecoy, {
        ...commonHeaders(
          "application/octet-stream",
          "public, max-age=300",
        ),
        "Accept-Ranges": "bytes",
      });
      return;
    }

    const match = /^bytes=(\d+)-(\d*)$/u.exec(range);
    const start = match === null ? Number.NaN : Number(match[1]);
    const requestedEnd =
      match === null || match[2] === "" ? rangeDecoy.length - 1 : Number(match[2]);
    if (
      match === null ||
      !Number.isSafeInteger(start) ||
      !Number.isSafeInteger(requestedEnd) ||
      start < 0 ||
      start >= rangeDecoy.length ||
      requestedEnd < start
    ) {
      send(request, response, 416, "", {
        ...commonHeaders("text/plain; charset=utf-8"),
        "Content-Range": `bytes */${rangeDecoy.length}`,
      });
      return;
    }
    const end = Math.min(requestedEnd, rangeDecoy.length - 1);
    send(request, response, 206, rangeDecoy.subarray(start, end + 1), {
      ...commonHeaders(
        "application/octet-stream",
        "public, max-age=300",
      ),
      "Accept-Ranges": "bytes",
      "Content-Range": `bytes ${start}-${end}/${rangeDecoy.length}`,
    });
    return;
  }

  if (url.pathname === decoyRoutes.api) {
    sendJson(
      request,
      response,
      200,
      { ok: true, marker: "API_PRIVATE_DECOY" },
      { "Cache-Control": "public, max-age=300" },
    );
    return;
  }

  if (url.pathname === decoyRoutes.auth) {
    sendJson(
      request,
      response,
      200,
      { ok: true, marker: "AUTH_PATH_PRIVATE_DECOY" },
      {
        "Cache-Control": "public, max-age=300",
        "Set-Cookie": `${credentialCookie}; Path=/; HttpOnly; SameSite=Strict`,
      },
    );
    return;
  }

  if (url.pathname === decoyRoutes.document) {
    send(request, response, 200, documentDecoy, {
      ...commonHeaders("application/pdf", "public, max-age=300"),
    });
    return;
  }

  const selected = releaseFiles[selectedRelease];
  if (url.pathname === "/sw.js") {
    if (
      url.search !==
      `?reader=${encodeURIComponent(selected.workerRevision)}`
    ) {
      sendProblem(request, response, 409, "WORKER_REVISION_MISMATCH");
      return;
    }
    send(request, response, 200, selected.worker, {
      ...commonHeaders("text/javascript; charset=utf-8"),
      "Service-Worker-Allowed": "/",
    });
    return;
  }
  if (url.pathname === "/offline.html") {
    send(request, response, 200, selected.shell, {
      ...commonHeaders(
        "text/html; charset=utf-8",
        "no-cache, max-age=0, must-revalidate",
      ),
      "Content-Security-Policy":
        "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; img-src 'self'; connect-src 'none'; form-action 'none'; frame-ancestors 'none'",
    });
    return;
  }
  if (url.pathname === "/offline-pack.js") {
    send(request, response, 200, selected.runtime, {
      ...commonHeaders(
        "text/javascript; charset=utf-8",
        "no-cache, max-age=0, must-revalidate",
      ),
    });
    return;
  }

  const staticFile = staticFiles.get(url.pathname);
  if (staticFile !== undefined) {
    send(request, response, 200, staticFile.body, {
      ...commonHeaders(
        staticFile.contentType,
        "public, max-age=0, must-revalidate",
      ),
    });
    return;
  }

  sendProblem(request, response, 404, "NOT_FOUND");
}

const server = createServer((request, response) => {
  void handleRequest(request, response).catch(() => {
    if (!response.headersSent) {
      sendProblem(request, response, 500, "INTERNAL_ERROR");
    } else {
      response.destroy();
    }
  });
});
server.maxHeadersCount = 64;
server.headersTimeout = 5_000;
server.requestTimeout = 10_000;
server.keepAliveTimeout = 5_000;
server.on("clientError", (_error, socket) => {
  if (socket.writable) {
    socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
  }
});
server.listen(port, host, () => {
  process.stdout.write(
    `OpenScholar PWA release fixture listening on ${origin} (${previousRevision} -> ${currentRevision} -> ${rollbackRevision}).\n`,
  );
});

function stop() {
  server.close((error) => {
    if (error !== undefined) {
      process.stderr.write(`${error.message}\n`);
      process.exitCode = 1;
    }
  });
}

process.once("SIGINT", stop);
process.once("SIGTERM", stop);
