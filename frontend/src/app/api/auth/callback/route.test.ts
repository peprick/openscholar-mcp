import { afterEach, describe, expect, it, vi } from "vitest";

import { GET } from "@/app/api/auth/callback/route";
import { getAuthConfig } from "@/shared/auth/config";
import {
  AUTH_TRANSACTION_COOKIE,
  sealAuthTransaction,
} from "@/shared/auth/session";

vi.mock("server-only", () => ({}));

const transactionCookie = vi.hoisted(() => ({
  value: undefined as string | undefined,
}));

vi.mock("next/headers", () => ({
  cookies: vi.fn(async () => ({
    get: (name: string) =>
      name === "__Host-openscholar-oidc" &&
      transactionCookie.value !== undefined
        ? { name, value: transactionCookie.value }
        : undefined,
  })),
}));

function configureOidc(): void {
  vi.stubEnv("OPENSCHOLAR_AUTH_MODE", "oidc");
  vi.stubEnv(
    "OPENSCHOLAR_AUTH_SESSION_SECRET",
    Buffer.alloc(32, 11).toString("base64"),
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
  transactionCookie.value = undefined;
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe("OIDC callback", () => {
  it("rejects a state mismatch before contacting the token endpoint", async () => {
    configureOidc();
    const config = getAuthConfig();
    if (config.mode !== "oidc") throw new Error("Expected OIDC configuration");
    const now = Math.floor(Date.now() / 1_000);
    transactionCookie.value = sealAuthTransaction(
      {
        state: "correct-state-value-that-is-long-enough",
        nonce: "nonce-value-that-is-also-long-enough",
        codeVerifier: "v".repeat(64),
        returnTo: "/library",
        issuedAt: now,
        expiresAt: now + 600,
      },
      config,
    );
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(
      new Request(
        "https://research.test/api/auth/callback?code=authorization-code&state=wrong-state",
      ),
    );

    expect(response.status).toBe(400);
    expect(response.headers.get("referrer-policy")).toBe("no-referrer");
    await expect(response.json()).resolves.toMatchObject({
      code: "OIDC_CALLBACK_REJECTED",
    });
    expect(fetchMock).not.toHaveBeenCalled();
    expect(response.headers.get("set-cookie")).toContain(
      `${AUTH_TRANSACTION_COOKIE}=`,
    );
    expect(response.headers.get("set-cookie")).toContain("Max-Age=0");
  });
});
