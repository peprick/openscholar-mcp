import { NextRequest } from "next/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  config as proxyConfig,
  proxy,
  PUBLIC_CONNECTIVITY_PATH,
  PUBLIC_PWA_PATHS,
} from "@/proxy";
import { getAuthConfig } from "@/shared/auth/config";
import {
  AUTH_SESSION_COOKIE,
  readAuthSession,
  sealAuthSession,
} from "@/shared/auth/session";

vi.mock("server-only", () => ({}));

function configureOidc(): void {
  vi.stubEnv("OPENSCHOLAR_AUTH_MODE", "oidc");
  vi.stubEnv(
    "OPENSCHOLAR_AUTH_SESSION_SECRET",
    Buffer.alloc(32, 13).toString("base64"),
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
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe("hosted session refresh proxy", () => {
  it.each([...PUBLIC_PWA_PATHS])(
    "keeps the public PWA asset outside session processing: %s",
    async (pathname) => {
      configureOidc();
      vi.stubEnv("OPENSCHOLAR_AUTH_SESSION_SECRET", "");

      const response = await proxy(
        new NextRequest(`https://research.test${pathname}`),
      );

      expect(response.status).toBe(200);
      expect(response.cookies.get(AUTH_SESSION_COOKIE)).toBeUndefined();
    },
  );

  it("excludes the public PWA files from the production proxy matcher", () => {
    const matcher = proxyConfig.matcher[0];

    expect(matcher).toContain("sw\\.js");
    expect(matcher).toContain("manifest\\.webmanifest");
    expect(matcher).toContain("offline\\.html");
  });

  it("keeps the credentialless connectivity probe outside session refresh", async () => {
    configureOidc();
    const config = getAuthConfig();
    if (config.mode !== "oidc") throw new Error("Expected OIDC configuration");
    const now = Math.floor(Date.now() / 1_000);
    const expired = sealAuthSession(
      {
        accessToken: "expired-access",
        refreshToken: "refresh-token",
        idToken: "verified-id-token",
        tokenType: "Bearer",
        scopes: ["openid"],
        subject: "researcher",
        accessExpiresAt: now - 10,
        sessionExpiresAt: now + 600,
      },
      config,
    );
    const fetchMock = vi.fn().mockRejectedValue(new Error("identity unavailable"));
    vi.stubGlobal("fetch", fetchMock);

    const response = await proxy(
      new NextRequest(`https://research.test${PUBLIC_CONNECTIVITY_PATH}`, {
        headers: { cookie: `${AUTH_SESSION_COOKIE}=${expired}` },
      }),
    );

    expect(response.status).toBe(200);
    expect(response.cookies.get(AUTH_SESSION_COOKIE)).toBeUndefined();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("persists refresh-token rotation in a new sealed cookie", async () => {
    configureOidc();
    const config = getAuthConfig();
    if (config.mode !== "oidc") throw new Error("Expected OIDC configuration");
    const now = Math.floor(Date.now() / 1_000);
    const initial = sealAuthSession(
      {
        accessToken: "old-access",
        refreshToken: "old-refresh",
        idToken: "verified-id-token",
        tokenType: "Bearer",
        scopes: ["openid", "openscholar.library"],
        subject: "researcher",
        accessExpiresAt: now + 10,
        sessionExpiresAt: now + 600,
      },
      config,
    );
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json({
        access_token: "new-access",
        refresh_token: "rotated-refresh",
        token_type: "Bearer",
        expires_in: 300,
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const response = await proxy(
      new NextRequest("https://research.test/library", {
        headers: { cookie: `${AUTH_SESSION_COOKIE}=${initial}` },
      }),
    );

    const nextCookie = response.cookies.get(AUTH_SESSION_COOKIE)?.value;
    const nextSession = readAuthSession(nextCookie, config, now);
    expect(nextSession).toMatchObject({
      accessToken: "new-access",
      refreshToken: "rotated-refresh",
      scopes: ["openid", "openscholar.library"],
      subject: "researcher",
    });
    const request = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(fetchMock.mock.calls[0]?.[0]).toEqual(config.tokenEndpoint);
    expect(String(request.body)).toContain("refresh_token=old-refresh");
  });

  it("rejects cross-origin browser mutations before they reach BFF routes", async () => {
    configureOidc();

    const response = await proxy(
      new NextRequest("https://research.test/api/searches", {
        method: "POST",
        headers: { origin: "https://attacker.test" },
      }),
    );

    expect(response.status).toBe(403);
    await expect(response.json()).resolves.toMatchObject({
      code: "REQUEST_ORIGIN_REJECTED",
    });
  });

  it("fails closed when hosted authentication configuration is invalid", async () => {
    configureOidc();
    vi.stubEnv("OPENSCHOLAR_AUTH_SESSION_SECRET", "");

    const response = await proxy(
      new NextRequest("https://research.test/library"),
    );

    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toMatchObject({
      code: "AUTH_CONFIGURATION_ERROR",
    });
  });

  it("keeps a still-current access token when an early refresh is unavailable", async () => {
    configureOidc();
    const config = getAuthConfig();
    if (config.mode !== "oidc") throw new Error("Expected OIDC configuration");
    const now = Math.floor(Date.now() / 1_000);
    const initial = sealAuthSession(
      {
        accessToken: "still-current",
        refreshToken: "refresh-token",
        idToken: "verified-id-token",
        tokenType: "Bearer",
        scopes: ["openid"],
        subject: "researcher",
        accessExpiresAt: now + 10,
        sessionExpiresAt: now + 600,
      },
      config,
    );
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("unavailable")));

    const response = await proxy(
      new NextRequest("https://research.test/library", {
        headers: { cookie: `${AUTH_SESSION_COOKIE}=${initial}` },
      }),
    );

    expect(response.status).toBe(200);
    expect(response.cookies.get(AUTH_SESSION_COOKIE)).toBeUndefined();
  });

  it("deduplicates simultaneous refreshes for the same rotated token", async () => {
    configureOidc();
    const config = getAuthConfig();
    if (config.mode !== "oidc") throw new Error("Expected OIDC configuration");
    const now = Math.floor(Date.now() / 1_000);
    const initial = sealAuthSession(
      {
        accessToken: "old-access",
        refreshToken: "single-use-refresh",
        idToken: "verified-id-token",
        tokenType: "Bearer",
        scopes: ["openid"],
        subject: "researcher",
        accessExpiresAt: now + 10,
        sessionExpiresAt: now + 600,
      },
      config,
    );
    const fetchMock = vi.fn().mockImplementation(async () =>
      Response.json({
        access_token: "new-access",
        refresh_token: "rotated-refresh",
        token_type: "Bearer",
        expires_in: 300,
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const request = () =>
      new NextRequest("https://research.test/library", {
        headers: { cookie: `${AUTH_SESSION_COOKIE}=${initial}` },
      });

    const [first, second] = await Promise.all([
      proxy(request()),
      proxy(request()),
    ]);

    expect(first.cookies.get(AUTH_SESSION_COOKIE)?.value).toBeDefined();
    expect(second.cookies.get(AUTH_SESSION_COOKIE)?.value).toBeDefined();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
