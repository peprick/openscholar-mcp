import http from "node:http";

const apiKey = process.env.MCP_LOCAL_API_KEY;
const target = new URL(
  process.env.MCP_CONFORMANCE_TARGET_ORIGIN ?? "http://127.0.0.1:8080",
);
const listenPort = Number.parseInt(
  process.env.MCP_CONFORMANCE_PROXY_PORT ?? "6277",
  10,
);

const loopbackHosts = new Set(["127.0.0.1", "localhost", "[::1]"]);

if (!apiKey) {
  throw new Error("MCP_LOCAL_API_KEY is required");
}
if (
  target.protocol !== "http:" ||
  !loopbackHosts.has(target.hostname) ||
  target.username ||
  target.password ||
  target.pathname !== "/" ||
  target.search ||
  target.hash
) {
  throw new Error(
    "MCP_CONFORMANCE_TARGET_ORIGIN must be a credential-free loopback HTTP origin",
  );
}
if (!Number.isInteger(listenPort) || listenPort < 1024 || listenPort > 65535) {
  throw new Error("MCP_CONFORMANCE_PROXY_PORT must be between 1024 and 65535");
}

const server = http.createServer((incoming, outgoing) => {
  const incomingUrl = new URL(incoming.url ?? "/", "http://127.0.0.1");
  if (incomingUrl.pathname !== "/mcp") {
    outgoing.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    outgoing.end("Not found");
    return;
  }

  const headers = {
    ...incoming.headers,
    authorization: `Bearer ${apiKey}`,
  };

  const upstream = http.request(
    {
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port,
      method: incoming.method,
      path: `${incomingUrl.pathname}${incomingUrl.search}`,
      headers,
    },
    (response) => {
      outgoing.writeHead(response.statusCode ?? 502, response.headers);
      response.pipe(outgoing);
    },
  );

  upstream.on("error", () => {
    if (!outgoing.headersSent) {
      outgoing.writeHead(502, { "content-type": "text/plain; charset=utf-8" });
    }
    outgoing.end("MCP upstream unavailable");
  });
  incoming.pipe(upstream);
});

server.listen(listenPort, "127.0.0.1", () => {
  process.stdout.write(
    `MCP conformance proxy listening on http://127.0.0.1:${listenPort}/mcp\n`,
  );
});

const close = () => server.close(() => process.exit(0));
process.on("SIGINT", close);
process.on("SIGTERM", close);
