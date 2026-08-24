# MCP quickstart

OpenScholar exposes five read-oriented tools at `http://127.0.0.1:8080/mcp` over stateless Streamable HTTP. The MCP adapter and REST controllers call the same Spring application use cases; the adapter does not expose database entities, arbitrary URL fetching, filesystem access, or collection mutations.

## 1. Configure the local key

Generate a private key and place it in the ignored root `.env` file:

```bash
openssl rand -hex 32
```

```dotenv
MCP_LOCAL_API_KEY=replace-with-the-generated-value
```

Compose reads that value from `.env`. For the `curl` and Inspector commands below,
export the same value into the current shell as well:

```bash
export MCP_LOCAL_API_KEY='replace-with-the-generated-value'
```

`MCP_ALLOWED_ORIGINS` is optional and applies only when a client or local proxy sends an `Origin` header. It is a comma-separated list of exact HTTP(S) origins, including the port. Native/local clients that omit `Origin` remain supported. This setting enforces the MCP DNS-rebinding check; it does not by itself enable arbitrary browser CORS access. The default example permits the local MCP Inspector UI origins only:

```dotenv
MCP_ALLOWED_ORIGINS=http://127.0.0.1:6274,http://localhost:6274
```

Start the stack with `docker compose up --build`. The endpoint returns `503 MCP_NOT_CONFIGURED` while the key is empty and `401 MCP_UNAUTHORIZED` for a missing or incorrect bearer value.

Authenticated requests are limited to 120 per minute for each server-observed remote address by default. A rejected request returns `429 MCP_RATE_LIMITED` with `Retry-After`. Local overrides are available through `MCP_RATE_LIMIT_ENABLED`, `MCP_RATE_LIMIT_REQUESTS`, `MCP_RATE_LIMIT_WINDOW`, and `MCP_RATE_LIMIT_MAX_CLIENTS`; keep the limiter enabled anywhere the endpoint is exposed.

## 2. Connect an MCP client

A generic Streamable HTTP client entry looks like this:

```json
{
  "mcpServers": {
    "openscholar": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:8080/mcp",
      "headers": {
        "Authorization": "Bearer replace-with-your-local-key"
      }
    }
  }
}
```

Keep client configuration containing the key outside Git. Client configuration formats differ, so use the equivalent Streamable HTTP URL and Authorization header in your host.

### Hosted OIDC clients

Hosted mode does not use the local key. Connect to the canonical public resource such as `https://research.example.com/mcp` and obtain a JWT access token from the deployment's external authorization server for that configured resource audience and the `openscholar.mcp` scope. A missing or insufficient token receives a bearer challenge pointing to `https://research.example.com/.well-known/oauth-protected-resource/mcp`; that document identifies the resource, authorization server, bearer-header method, and required scope. OpenScholar validates tokens but does not issue them.

Hosted rate-limit identity is a hash of the validated issuer+subject. The limiter is still in-memory and per application instance, so an edge/cluster-wide aggregate control is required for public deployment. Clients that send an `Origin` header must exactly match the hosted allow-list. Real authorization-server registration and interoperability remain deployment gates; the local Inspector/conformance commands below do not prove hosted OAuth interoperability.

The official MCP Inspector can list the tools from its CLI:

```bash
npx -y @modelcontextprotocol/inspector@2.2.0 \
  --cli http://127.0.0.1:8080/mcp \
  --transport http \
  --method tools/list \
  --header "Authorization: Bearer ${MCP_LOCAL_API_KEY}"
```

Version `2.2.0` is the Inspector release used for the documented smoke test; upgrade it deliberately and rerun discovery plus one tool call.

## 3. Available tools

| Tool | Behavior | External provider call |
|---|---|---|
| `search_research` | Searches or reuses a bounded owner-scoped scholarly snapshot; returns canonical IDs, requested mode, actual execution source, ranking rationale, full provider provenance, warnings, and a cursor. | Possible in `AUTO`/`ONLINE`; never in `LOCAL` |
| `get_paper_details` | Reads canonical metadata, identifiers, provenance, freshness, and the full stored access resolution for one OpenScholar UUID. | No |
| `get_legal_full_text` | Reads the stored legal-access resolution and verified links. `NOT_YET_RESOLVED` means the REST/UI verification flow has not run yet. | No |
| `search_saved_library` | Searches the current owner's saved memberships by text, collection, status, and normalized tag. Local mode uses the fixed bootstrap owner; hosted mode derives it from issuer+subject. | No |
| `export_citations` | Returns a bounded ordered BibTeX or CSL-JSON export as structured MCP content. | No |

MCP-specific result and citation batches are capped at 25 items. Citation inputs must be distinct canonical UUIDs. Paper-detail lookup currently accepts the canonical OpenScholar UUID only; DOI, arXiv, and OpenAlex identifier resolution is a later catalog feature.

Search can update internal metadata/search caches, but none of the advertised tools mutates the user's collections or reading state. Write tools are deferred and are not currently advertised or implemented.

## 4. Raw protocol smoke check

MCP clients perform this lifecycle automatically. These commands are useful when debugging the HTTP boundary:

```bash
curl --request POST http://127.0.0.1:8080/mcp \
  --header "Authorization: Bearer ${MCP_LOCAL_API_KEY}" \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json, text/event-stream' \
  --data '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2025-11-25",
      "capabilities": {},
      "clientInfo": {"name": "curl-smoke", "version": "1.0.0"}
    }
  }'
```

Then send the initialized notification and list tools:

```bash
curl --request POST http://127.0.0.1:8080/mcp \
  --header "Authorization: Bearer ${MCP_LOCAL_API_KEY}" \
  --header 'MCP-Protocol-Version: 2025-11-25' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json, text/event-stream' \
  --data '{"jsonrpc":"2.0","method":"notifications/initialized"}'

curl --request POST http://127.0.0.1:8080/mcp \
  --header "Authorization: Bearer ${MCP_LOCAL_API_KEY}" \
  --header 'MCP-Protocol-Version: 2025-11-25' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json, text/event-stream' \
  --data '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

A search call uses the same endpoint:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "search_research",
    "arguments": {
      "topic": "graph neural networks for drug discovery",
      "yearFrom": 2021,
      "yearTo": 2026,
      "documentTypes": ["ARTICLE", "PREPRINT"],
      "openAccessOnly": true,
      "languages": ["en"],
      "limit": 10,
      "mode": "AUTO"
    }
  }
}
```

`mode` is optional and defaults to `AUTO`. Use `LOCAL` for a database-only search over metadata previously visible to the current owner, or `ONLINE` to forbid local-catalog fallback. `forceRefresh=true` is incompatible with `LOCAL`. Read `executionSource` (`PROVIDER_FETCH`, `EXACT_CACHE`, `STALE_CACHE`, or `LOCAL_CATALOG`) rather than inferring provider activity from the requested mode. A local result retains stored provider provenance and its retrieval time; it does not imply that the provider was contacted or that external links are currently reachable.

## Compatibility and safety

The current Spring AI/MCP Java SDK stack negotiates supported revisions through a maximum tested revision of `2025-11-25`; it does not advertise experimental Tasks or MCP Apps support. Every protected response carries `X-OpenScholar-Mcp-Request-Id`, `Cache-Control: no-store`, and `X-Content-Type-Options: nosniff`. Micrometer records total MCP requests, pre-dispatch rejection reasons, and filter duration. The production template exposes the Prometheus registry on the backend's un-published private `9091` management listener; Prometheus reaches it over the monitoring network and Caddy does not route it publicly. Docker bridge membership is not a per-port firewall, so deployments that require monitoring-only reachability need the additional boundary described in the deployment guide.

The local shared-key/fixed-user mode is for loopback development, and its address-based limiter intentionally ignores forwarding headers. Hosted JWT authorization and owner propagation are implemented, but a target deployment still needs trusted-edge review and aggregate controls. In both modes, provider deadlines and the 18-second search application deadline bound their own layers; client disconnects and MCP `notifications/cancelled` do not currently cancel the tool worker. Durable REST refresh jobs are not MCP Tasks or MCP job handles.

## Official conformance subset

The current official conformance CLI cannot attach a custom bearer header. The repository therefore includes a dependency-free loopback proxy that injects the local MCP key without disabling the production security filter. Run it only for the duration of a local conformance check:

```bash
MCP_LOCAL_API_KEY="${MCP_LOCAL_API_KEY}" \
  node scripts/mcp-conformance-proxy.mjs
```

In another shell, install the checked-in conformance tool with lifecycle scripts disabled and its registry integrity enforced by the frozen lockfile, then run the two production-surface scenarios supported by OpenScholar:

```bash
pnpm --dir tools/mcp-conformance install --frozen-lockfile --ignore-scripts

pnpm --dir tools/mcp-conformance exec conformance server \
  --url http://127.0.0.1:6277/mcp \
  --scenario server-initialize \
  --spec-version 2025-11-25 \
  --verbose

pnpm --dir tools/mcp-conformance exec conformance server \
  --url http://127.0.0.1:6277/mcp \
  --scenario tools-list \
  --spec-version 2025-11-25 \
  --verbose
```

Stop the proxy immediately afterward. The full fixture-oriented suite expects synthetic tools, resources, prompts, sampling, and elicitation that this five-tool domain server intentionally does not advertise; failing those fixture scenarios would not indicate a production contract failure. Do not use the `server-stateless` scenario, which targets a later protocol revision.

Both commands were verified against the Compose image with the lockfile-resolved conformance `0.1.16`: each passed `1/1` with no warnings, and `tools-list` discovered exactly the five documented tools.

The `MCP Conformance` GitHub Actions workflow repeats this supported subset for backend, Compose, proxy, tooling-lock, and workflow changes. It installs the same frozen CLI with scripts disabled, starts an isolated PostgreSQL/backend stack, waits for the readiness probe, injects an ephemeral local key through the loopback proxy, runs both pinned scenarios, and always removes the containers and volume afterward.
