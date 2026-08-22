import { afterEach, describe, expect, it, vi } from "vitest";

import {
  BackendContractError,
  fetchBackend,
  getNextSearchPage,
  getRelatedPapers,
} from "@/shared/api/server";
import { getAuthConfig } from "@/shared/auth/config";
import {
  AUTH_SESSION_COOKIE,
  sealAuthSession,
} from "@/shared/auth/session";
import {
  relatedPapersResponseFixture,
  searchResponseFixture,
  testIds,
} from "@/test/fixtures";

vi.mock("server-only", () => ({}));

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

afterEach(() => {
  requestCookie.value = undefined;
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
