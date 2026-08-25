import { afterEach, describe, expect, it, vi } from "vitest";

import {
  BackendContractError,
  deletePersonalData,
  exportPersonalData,
  fetchBackend,
  getNextSearchPage,
  getOfflineCollectionPack,
  getRelatedPapers,
  resolvePaperIdentifier,
} from "@/shared/api/server";
import { getAuthConfig } from "@/shared/auth/config";
import {
  AUTH_SESSION_COOKIE,
  sealAuthSession,
} from "@/shared/auth/session";
import {
  offlineCollectionPackFixture,
  relatedPapersResponseFixture,
  searchResponseFixture,
  testIds,
} from "@/test/fixtures";

vi.mock("server-only", () => ({}));

const MEBIBYTE = 1_048_576;
const SEARCH_RESPONSE_MAX_BYTES = 8 * MEBIBYTE;

const requestCookie = vi.hoisted(() => ({ value: undefined as string | undefined }));

vi.mock("next/headers", () => ({
  cookies: vi.fn(async () => ({
    get: (name: string) =>
      name === "__Host-openscholar-session" && requestCookie.value !== undefined
        ? { name, value: requestCookie.value }
        : undefined,
  })),
}));

function configureHostedAuth(): void {
  vi.stubEnv("OPENSCHOLAR_AUTH_MODE", "oidc");
  vi.stubEnv(
    "OPENSCHOLAR_AUTH_SESSION_SECRET",
    Buffer.alloc(32, 9).toString("base64"),
  );
  vi.stubEnv("OPENSCHOLAR_OIDC_ISSUER", "https://identity.test");
  vi.stubEnv("OPENSCHOLAR_OIDC_AUTHORIZATION_ENDPOINT", "https://identity.test/authorize");
  vi.stubEnv("OPENSCHOLAR_OIDC_TOKEN_ENDPOINT", "https://identity.test/token");
  vi.stubEnv("OPENSCHOLAR_OIDC_JWKS_URI", "https://identity.test/keys");
  vi.stubEnv("OPENSCHOLAR_OIDC_CLIENT_ID", "openscholar-web");
  vi.stubEnv(
    "OPENSCHOLAR_OIDC_REDIRECT_URI",
    "https://research.test/api/auth/callback",
  );
  vi.stubEnv(
    "OPENSCHOLAR_OIDC_POST_LOGOUT_REDIRECT_URI",
    "https://research.test/",
  );
}

function offlinePackJsonWithByteLength(targetBytes: number): {
  json: string;
  payload: ReturnType<typeof offlineCollectionPackFixture>;
} {
  const payload = offlineCollectionPackFixture();
  payload.papers[0]!.title = "";
  const emptyTitleJson = JSON.stringify(payload);
  const paddingLength = targetBytes - Buffer.byteLength(emptyTitleJson, "utf8");
  if (paddingLength < 1) throw new Error("Target is too small for an offline pack");
  payload.papers[0]!.title = "x".repeat(paddingLength);
  const json = JSON.stringify(payload);
  if (Buffer.byteLength(json, "utf8") !== targetBytes) {
    throw new Error("Could not create an exact-size offline pack fixture");
  }
  return { json, payload };
}

function streamedResponse(
  chunks: Uint8Array[],
  init: ResponseInit,
): { response: Response; cancel: ReturnType<typeof vi.fn> } {
  let nextChunk = 0;
  const cancel = vi.fn();
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      const chunk = chunks[nextChunk];
      nextChunk += 1;
      if (chunk === undefined) {
        controller.close();
        return;
      }
      controller.enqueue(chunk);
    },
    cancel,
  });
  return { response: new Response(body, init), cancel };
}

afterEach(() => {
  requestCookie.value = undefined;
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe("fetchBackend hosted authentication", () => {
  it("forwards the sealed-session access token only from hosted mode", async () => {
    configureHostedAuth();
    vi.stubEnv("OPENSCHOLAR_API_BASE_URL", "https://backend.test");
    const config = getAuthConfig();
    if (config.mode !== "oidc") throw new Error("Expected OIDC configuration");
    const now = Math.floor(Date.now() / 1_000);
    requestCookie.value = sealAuthSession(
      {
        accessToken: "hosted-access-token",
        refreshToken: null,
        idToken: "id-token",
        tokenType: "Bearer",
        scopes: ["openid"],
        subject: "researcher",
        accessExpiresAt: now + 300,
        sessionExpiresAt: now + 600,
      },
      config,
    );
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await fetchBackend("/api/v1/system/status");

    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Headers;
    expect(AUTH_SESSION_COOKIE).toBe("__Host-openscholar-session");
    expect(headers.get("authorization")).toBe("Bearer hosted-access-token");
  });

  it("does not overwrite an authorization header supplied by the caller", async () => {
    configureHostedAuth();
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await fetchBackend("/api/v1/system/status", {
      headers: { authorization: "Bearer caller-token" },
    });

    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Headers;
    expect(headers.get("authorization")).toBe("Bearer caller-token");
  });
});

describe("bounded backend JSON responses", () => {
  const resolution = {
    paperId: testIds.paper,
    identifierType: "DOI" as const,
    normalizedValue: "10.1000/example",
  };

  it("accepts an ordinary streamed JSON success without Content-Length", async () => {
    const encoded = new TextEncoder().encode(JSON.stringify(resolution));
    const midpoint = Math.floor(encoded.byteLength / 2);
    const { response } = streamedResponse(
      [encoded.slice(0, midpoint), encoded.slice(midpoint)],
      {
        status: 200,
        headers: { "content-type": "application/json; charset=utf-8" },
      },
    );
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));

    await expect(resolvePaperIdentifier("10.1000/example")).resolves.toEqual(
      resolution,
    );
  });

  it("accepts a valid declared JSON body length", async () => {
    const json = JSON.stringify(resolution);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(json, {
          status: 200,
          headers: {
            "content-length": String(Buffer.byteLength(json, "utf8")),
            "content-type": "application/json",
          },
        }),
      ),
    );

    await expect(resolvePaperIdentifier("10.1000/example")).resolves.toEqual(
      resolution,
    );
  });

  it("accepts an ordinary abstract-bearing search page above the small-response ceiling", async () => {
    const payload = searchResponseFixture();
    const abstractLength = 60 * 1_024;
    const baseResult = payload.results[0]!;
    payload.results = Array.from({ length: 20 }, (_, index) => ({
      ...baseResult,
      rank: index + 1,
      paperId: `00000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
      abstractText: "x".repeat(abstractLength),
    }));
    const json = JSON.stringify(payload);
    const byteLength = Buffer.byteLength(json, "utf8");
    expect(byteLength).toBeGreaterThan(MEBIBYTE);
    expect(byteLength).toBeLessThan(SEARCH_RESPONSE_MAX_BYTES);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(json, {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    );

    const result = await getNextSearchPage(testIds.search);

    expect(result.data.results).toHaveLength(20);
    expect(result.data.results.every((paper) => paper.abstractText?.length === abstractLength)).toBe(
      true,
    );
  });

  it("rejects a declared search response above its resource ceiling before reading", async () => {
    const { response, cancel } = streamedResponse(
      [new TextEncoder().encode(JSON.stringify(searchResponseFixture()))],
      {
        status: 200,
        headers: {
          "content-length": String(SEARCH_RESPONSE_MAX_BYTES + 1),
          "content-type": "application/json",
        },
      },
    );
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));

    await expect(getNextSearchPage(testIds.search)).rejects.toBeInstanceOf(
      BackendContractError,
    );
    expect(cancel).toHaveBeenCalledOnce();
  });

  it.each([
    ["a missing length", undefined],
    ["a forged short length", "1"],
  ])(
    "caps an oversized streamed search response with %s",
    async (_label, contentLength) => {
      const headers = new Headers({ "content-type": "application/json" });
      if (contentLength !== undefined) {
        headers.set("content-length", contentLength);
      }
      const { response, cancel } = streamedResponse(
        [
          new Uint8Array(5 * MEBIBYTE),
          new Uint8Array(4 * MEBIBYTE),
          new Uint8Array([1]),
        ],
        { status: 200, headers },
      );
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));

      await expect(getNextSearchPage(testIds.search)).rejects.toBeInstanceOf(
        BackendContractError,
      );
      expect(cancel).toHaveBeenCalledOnce();
    },
  );

  it.each(["invalid", "1048577", "999999999999999999999999999999"])(
    "rejects a forged or oversized declared success length of %s before reading",
    async (contentLength) => {
      const { response, cancel } = streamedResponse(
        [new TextEncoder().encode(JSON.stringify(resolution))],
        {
          status: 200,
          headers: {
            "content-length": contentLength,
            "content-type": "application/json",
          },
        },
      );
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));

      await expect(
        resolvePaperIdentifier("10.1000/example"),
      ).rejects.toBeInstanceOf(BackendContractError);
      expect(cancel).toHaveBeenCalledOnce();
    },
  );

  it.each([
    ["a missing length", undefined],
    ["a forged short length", "1"],
  ])("caps a chunked success with %s and cancels on overflow", async (_label, length) => {
    const headers = new Headers({ "content-type": "application/json" });
    if (length !== undefined) headers.set("content-length", length);
    const { response, cancel } = streamedResponse(
      [
        new Uint8Array(700 * 1_024),
        new Uint8Array(400 * 1_024),
        new Uint8Array([1]),
      ],
      { status: 200, headers },
    );
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));

    await expect(
      resolvePaperIdentifier("10.1000/example"),
    ).rejects.toBeInstanceOf(BackendContractError);
    expect(cancel).toHaveBeenCalledOnce();
  });

  it("rejects and cancels a successful body with the wrong media type", async () => {
    const { response, cancel } = streamedResponse(
      [new TextEncoder().encode(JSON.stringify(resolution))],
      { status: 200, headers: { "content-type": "text/plain" } },
    );
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));

    await expect(
      resolvePaperIdentifier("10.1000/example"),
    ).rejects.toBeInstanceOf(BackendContractError);
    expect(cancel).toHaveBeenCalledOnce();
  });

  it("preserves an ordinary bounded Problem Details response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json(
          {
            type: "urn:openscholar:problem:paper-identifier-not-found",
            title: "Paper not found",
            status: 404,
            detail: "No visible paper uses that identifier.",
            code: "PAPER_IDENTIFIER_NOT_FOUND",
          },
          {
            status: 404,
            headers: {
              "content-type": "application/problem+json; charset=utf-8",
              "retry-after": "12",
            },
          },
        ),
      ),
    );

    await expect(resolvePaperIdentifier("10.1000/missing")).rejects.toMatchObject({
      status: 404,
      retryAfter: "12",
      problem: expect.objectContaining({ code: "PAPER_IDENTIFIER_NOT_FOUND" }),
    });
  });

  it("cancels an oversized chunked problem and exposes only the safe fallback", async () => {
    const { response, cancel } = streamedResponse(
      [
        new Uint8Array(40 * 1_024),
        new Uint8Array(25 * 1_024),
        new Uint8Array([1]),
      ],
      {
        status: 502,
        headers: {
          "content-type": "application/problem+json",
          "retry-after": "9",
        },
      },
    );
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));

    await expect(resolvePaperIdentifier("10.1000/missing")).rejects.toMatchObject({
      status: 502,
      retryAfter: "9",
      problem: expect.objectContaining({ code: "BACKEND_REQUEST_FAILED" }),
    });
    expect(cancel).toHaveBeenCalledOnce();
  });

  it("does not parse a problem body with a non-JSON media type", async () => {
    const { response, cancel } = streamedResponse(
      [
        new TextEncoder().encode(
          JSON.stringify({
            title: "Unsafe upstream detail",
            status: 400,
            detail: "This body must not cross the boundary.",
            code: "UPSTREAM_DETAIL",
          }),
        ),
      ],
      { status: 400, headers: { "content-type": "text/plain" } },
    );
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));

    await expect(resolvePaperIdentifier("10.1000/missing")).rejects.toMatchObject({
      status: 400,
      problem: expect.objectContaining({ code: "BACKEND_REQUEST_FAILED" }),
    });
    expect(cancel).toHaveBeenCalledOnce();
  });
});

describe("getRelatedPapers", () => {
  it("requests the bounded related-paper endpoint and validates its payload", async () => {
    vi.stubEnv("OPENSCHOLAR_API_BASE_URL", "http://backend.test:8080");
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(relatedPapersResponseFixture()), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(getRelatedPapers(testIds.paper, 5)).resolves.toEqual(
      relatedPapersResponseFixture(),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      new URL(
        `/api/v1/papers/${testIds.paper}/related?limit=5`,
        "http://backend.test:8080",
      ),
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it("rejects a validly shaped response belonging to another source paper", async () => {
    const mismatched = relatedPapersResponseFixture({
      sourcePaperId: "33333333-3333-4333-8333-333333333333",
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify(mismatched), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    );

    await expect(getRelatedPapers(testIds.paper)).rejects.toBeInstanceOf(
      BackendContractError,
    );
  });

  it("accepts the backend canonical UUID for an uppercase route UUID", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify(relatedPapersResponseFixture()), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    );

    await expect(
      getRelatedPapers(testIds.paper.toUpperCase()),
    ).resolves.toEqual(relatedPapersResponseFixture());
  });
});

describe("getOfflineCollectionPack", () => {
  it("uses the single bounded backend export endpoint without pagination", async () => {
    vi.stubEnv("OPENSCHOLAR_API_BASE_URL", "http://backend.test:8080");
    const payload = offlineCollectionPackFixture();
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json(payload, {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(getOfflineCollectionPack(testIds.collection)).resolves.toEqual(
      payload,
    );
    expect(fetchMock).toHaveBeenCalledWith(
      new URL(
        `/api/v1/collections/${testIds.collection}/offline-pack`,
        "http://backend.test:8080",
      ),
      expect.objectContaining({
        cache: "no-store",
        headers: expect.any(Headers),
      }),
    );
    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Headers;
    expect(headers.get("accept")).toBe("application/json");
  });

  it("rejects a backend payload that adds an abstract", async () => {
    const payload = offlineCollectionPackFixture();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json({
          ...payload,
          papers: [{ ...payload.papers[0], abstractText: "private expansion" }],
        }),
      ),
    );

    await expect(getOfflineCollectionPack(testIds.collection)).rejects.toBeInstanceOf(
      BackendContractError,
    );
  });

  it("accepts an exact 1 MiB JSON response", async () => {
    const { json, payload } = offlinePackJsonWithByteLength(1_048_576);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(json, {
          status: 200,
          headers: { "content-type": "application/json; charset=utf-8" },
        }),
      ),
    );

    await expect(getOfflineCollectionPack(testIds.collection)).resolves.toEqual(
      payload,
    );
  });

  it("stops and rejects a JSON response larger than 1 MiB", async () => {
    const { json } = offlinePackJsonWithByteLength(1_048_577);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(json, {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    );

    await expect(getOfflineCollectionPack(testIds.collection)).rejects.toBeInstanceOf(
      BackendContractError,
    );
  });

  it.each([
    ["a missing content type", undefined],
    ["a non-JSON content type", "text/plain"],
    ["a different JSON media type", "application/problem+json"],
  ])("rejects a successful response with %s", async (_label, contentType) => {
    const bytes = new TextEncoder().encode(
      JSON.stringify(offlineCollectionPackFixture()),
    );
    const headers = contentType === undefined ? undefined : { "content-type": contentType };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(bytes, {
          status: 200,
          headers,
        }),
      ),
    );

    await expect(getOfflineCollectionPack(testIds.collection)).rejects.toBeInstanceOf(
      BackendContractError,
    );
  });

  it("uses the safe fallback for an error with a non-JSON media type", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            type: "urn:openscholar:problem:offline-pack-too-large",
            title: "Offline pack too large",
            status: 422,
            detail: "The collection exceeds the offline metadata limit.",
            code: "OFFLINE_PACK_TOO_LARGE",
          }),
          {
            status: 422,
            headers: {
              "content-type": "text/plain",
              "retry-after": "30",
            },
          },
        ),
      ),
    );

    await expect(getOfflineCollectionPack(testIds.collection)).rejects.toMatchObject({
      status: 422,
      retryAfter: "30",
      problem: expect.objectContaining({ code: "BACKEND_REQUEST_FAILED" }),
    });
  });
});

describe("resolvePaperIdentifier", () => {
  it("encodes the pasted identifier and validates the resolution payload", async () => {
    vi.stubEnv("OPENSCHOLAR_API_BASE_URL", "http://backend.test:8080");
    const resolution = {
      paperId: testIds.paper,
      identifierType: "DOI" as const,
      normalizedValue: "10.1000/example",
    };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(resolution), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      resolvePaperIdentifier("doi:10.1000/example?part=one"),
    ).resolves.toEqual(resolution);

    const query = new URLSearchParams({
      identifier: "doi:10.1000/example?part=one",
    });
    expect(fetchMock).toHaveBeenCalledWith(
      new URL(`/api/v1/papers/resolve?${query}`, "http://backend.test:8080"),
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it("rejects extra fields in the backend response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            paperId: testIds.paper,
            identifierType: "ARXIV",
            normalizedValue: "2401.12345",
            title: "Unexpected field",
          }),
          { status: 200, headers: { "content-type": "application/json" } },
        ),
      ),
    );

    await expect(resolvePaperIdentifier("2401.12345")).rejects.toBeInstanceOf(
      BackendContractError,
    );
  });
});

describe("getNextSearchPage", () => {
  it("posts to the current snapshot continuation endpoint", async () => {
    vi.stubEnv("OPENSCHOLAR_API_BASE_URL", "http://backend.test:8080");
    const responseBody = {
      ...searchResponseFixture(),
      searchId: "14a97a49-9203-4871-9924-d8bf4b08dcb4",
    };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(responseBody), {
        status: 201,
        headers: {
          "content-type": "application/json",
          location: `/api/v1/searches/${responseBody.searchId}`,
        },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(getNextSearchPage(testIds.search)).resolves.toEqual({
      data: responseBody,
      status: 201,
      location: `/api/v1/searches/${responseBody.searchId}`,
    });
    expect(fetchMock).toHaveBeenCalledWith(
      new URL(
        `/api/v1/searches/${testIds.search}/next`,
        "http://backend.test:8080",
      ),
      expect.objectContaining({ cache: "no-store", method: "POST" }),
    );
  });
});

describe("privacy backend boundary", () => {
  it("aborts a backend that never returns export response headers", async () => {
    vi.useFakeTimers();
    vi.stubEnv("OPENSCHOLAR_API_BASE_URL", "https://backend.test");
    const fetchMock = vi.fn().mockImplementation(
      async (_url: URL, init: RequestInit): Promise<Response> =>
        await new Promise<Response>((_resolve, reject) => {
          init.signal?.addEventListener(
            "abort",
            () =>
              reject(new DOMException("The operation was aborted", "AbortError")),
            { once: true },
          );
        }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const rejection = expect(exportPersonalData()).rejects.toMatchObject({
      status: 503,
      problem: expect.objectContaining({ code: "BACKEND_UNREACHABLE" }),
    });
    await vi.advanceTimersByTimeAsync(140_001);

    await rejection;
    const requestSignal = fetchMock.mock.calls[0]?.[1]?.signal as AbortSignal;
    expect(requestSignal.aborted).toBe(true);
  });

  it("returns the untouched JSON export stream after clearing its header timeout", async () => {
    vi.useFakeTimers();
    vi.stubEnv("OPENSCHOLAR_API_BASE_URL", "https://backend.test");
    const payload = JSON.stringify({ userId: "private-user", searches: [] });
    const source = new ReadableStream<Uint8Array>({
      pull(controller) {
        controller.enqueue(new TextEncoder().encode(payload));
        controller.close();
      },
    });
    const backendResponse = new Response(source, {
      status: 200,
      headers: {
        "content-length": String(Buffer.byteLength(payload, "utf8")),
        "content-disposition": 'attachment; filename="backend-name.json"',
        "content-type": "application/json; charset=utf-8",
      },
    });
    const jsonSpy = vi.spyOn(backendResponse, "json");
    const textSpy = vi.spyOn(backendResponse, "text");
    const blobSpy = vi.spyOn(backendResponse, "blob");
    const arrayBufferSpy = vi.spyOn(backendResponse, "arrayBuffer");
    const timeoutSpy = vi.spyOn(globalThis, "setTimeout");
    const fetchMock = vi.fn().mockResolvedValue(backendResponse);
    vi.stubGlobal("fetch", fetchMock);

    const response = await exportPersonalData();

    expect(response).toBe(backendResponse);
    expect(response.headers.get("content-length")).toBe(
      String(Buffer.byteLength(payload, "utf8")),
    );
    expect(timeoutSpy).toHaveBeenCalledWith(expect.any(Function), 140_000);
    const requestSignal = fetchMock.mock.calls[0]?.[1]?.signal as AbortSignal;
    expect(fetchMock).toHaveBeenCalledWith(
      new URL("/api/v1/privacy/export", "https://backend.test"),
      expect.objectContaining({
        cache: "no-store",
        method: "GET",
        signal: requestSignal,
      }),
    );
    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Headers;
    expect(headers.get("accept")).toBe("application/json");
    expect(headers.get("accept-encoding")).toBe("identity");
    expect(requestSignal.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(140_001);
    expect(requestSignal.aborted).toBe(false);
    expect(response.bodyUsed).toBe(false);
    expect(response.body?.locked).toBe(false);
    expect(jsonSpy).not.toHaveBeenCalled();
    expect(textSpy).not.toHaveBeenCalled();
    expect(blobSpy).not.toHaveBeenCalled();
    expect(arrayBufferSpy).not.toHaveBeenCalled();
    await expect(response.text()).resolves.toBe(payload);
    expect(response.bodyUsed).toBe(true);
  });

  it.each([
    [
      "unexpected successful status",
      () =>
        new Response("{}", {
          status: 201,
          headers: {
            "content-length": "2",
            "content-type": "application/json",
          },
        }),
    ],
    [
      "non-JSON content type",
      () =>
        new Response("<html>not JSON</html>", {
          status: 200,
          headers: { "content-length": "21", "content-type": "text/html" },
        }),
    ],
    [
      "missing body",
      () =>
        new Response(null, {
          status: 200,
          headers: {
            "content-length": "2",
            "content-type": "application/json",
          },
        }),
    ],
  ])("rejects an export with %s", async (_label, response) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response()));

    await expect(exportPersonalData()).rejects.toBeInstanceOf(
      BackendContractError,
    );
  });

  it.each([
    ["missing", undefined],
    ["zero", "0"],
    ["leading-zero", "02"],
    ["signed", "+2"],
    ["oversized", String(128 * MEBIBYTE + 1)],
    ["unbounded integer", "9".repeat(1_000)],
  ])(
    "rejects and cancels a stream with a %s Content-Length",
    async (_label, contentLength) => {
      const headers = new Headers({ "content-type": "application/json" });
      if (contentLength !== undefined) {
        headers.set("content-length", contentLength);
      }
      const { response, cancel } = streamedResponse(
        [new TextEncoder().encode("{}")],
        { status: 200, headers },
      );
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));

      await expect(exportPersonalData()).rejects.toBeInstanceOf(
        BackendContractError,
      );
      expect(cancel).toHaveBeenCalledOnce();
    },
  );

  it("rejects and cancels a content-encoded export", async () => {
    const { response, cancel } = streamedResponse(
      [new TextEncoder().encode("{}")],
      {
        status: 200,
        headers: {
          "content-encoding": "gzip",
          "content-length": "2",
          "content-type": "application/json",
        },
      },
    );
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));

    await expect(exportPersonalData()).rejects.toBeInstanceOf(
      BackendContractError,
    );
    expect(cancel).toHaveBeenCalledOnce();
  });

  it("translates a failed export into the safe backend API error", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json(
          {
            type: "urn:openscholar:problem:access-denied",
            title: "Forbidden",
            status: 403,
            detail: "The privacy scope is required.",
            code: "ACCESS_DENIED",
          },
          {
            status: 403,
            headers: {
              "content-type": "application/problem+json",
              "retry-after": "15",
            },
          },
        ),
      ),
    );

    await expect(exportPersonalData()).rejects.toMatchObject({
      status: 403,
      retryAfter: "15",
      problem: expect.objectContaining({ code: "ACCESS_DENIED" }),
    });
  });

  it("aborts a stalled backend error body after its finite deadline", async () => {
    vi.useFakeTimers();
    vi.stubEnv("OPENSCHOLAR_API_BASE_URL", "https://backend.test");
    let bodyController!: ReadableStreamDefaultController<Uint8Array>;
    const backendResponse = new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          bodyController = controller;
        },
      }),
      {
        status: 503,
        headers: { "content-type": "application/problem+json" },
      },
    );
    const fetchMock = vi.fn().mockImplementation(
      async (_url: URL, init: RequestInit): Promise<Response> => {
        init.signal?.addEventListener(
          "abort",
          () =>
            bodyController.error(
              new DOMException("The operation was aborted", "AbortError"),
            ),
          { once: true },
        );
        return backendResponse;
      },
    );
    vi.stubGlobal("fetch", fetchMock);

    const rejection = expect(exportPersonalData()).rejects.toMatchObject({
      status: 503,
      problem: expect.objectContaining({ code: "BACKEND_REQUEST_FAILED" }),
    });
    await vi.advanceTimersByTimeAsync(30_001);

    await rejection;
    const requestSignal = fetchMock.mock.calls[0]?.[1]?.signal as AbortSignal;
    expect(requestSignal.aborted).toBe(true);
  });

  it("sends only the exact confirmation and requires the backend 204", async () => {
    vi.stubEnv("OPENSCHOLAR_API_BASE_URL", "https://backend.test");
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(null, { status: 204 }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      deletePersonalData({ confirmation: "DELETE_MY_DATA" }),
    ).resolves.toBeUndefined();

    expect(fetchMock).toHaveBeenCalledWith(
      new URL("/api/v1/privacy/account", "https://backend.test"),
      expect.objectContaining({
        body: JSON.stringify({ confirmation: "DELETE_MY_DATA" }),
        cache: "no-store",
        method: "DELETE",
      }),
    );
    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Headers;
    expect(headers.get("content-type")).toBe("application/json");
  });

  it("does not treat a different successful deletion status as completion", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json(
          { deleted: true },
          { status: 200, headers: { "content-type": "application/json" } },
        ),
      ),
    );

    await expect(
      deletePersonalData({ confirmation: "DELETE_MY_DATA" }),
    ).rejects.toBeInstanceOf(BackendContractError);
  });
});
