import { describe, expect, it, vi } from "vitest";

import type { OidcAuthConfig } from "@/shared/auth/config";
import { pkceChallenge } from "@/shared/auth/seal";
import {
  AUTH_SESSION_COOKIE,
  AUTH_TRANSACTION_COOKIE,
  authCookieOptions,
  readAuthSession,
  readAuthTransaction,
  sanitizeReturnTo,
  sealAuthSession,
  sealAuthTransaction,
} from "@/shared/auth/session";

vi.mock("server-only", () => ({}));

const config: OidcAuthConfig = {
  mode: "oidc",
  issuer: "https://identity.test",
  authorizationEndpoint: new URL("https://identity.test/authorize"),
  tokenEndpoint: new URL("https://identity.test/token"),
  jwksUri: new URL("https://identity.test/keys"),
  endSessionEndpoint: null,
  clientId: "client",
  clientSecret: null,
  clientAuthMethod: "none",
  redirectUri: new URL("https://research.test/api/auth/callback"),
  postLogoutRedirectUri: new URL("https://research.test/"),
  scopes: ["openid"],
  idTokenAlgorithms: ["RS256"],
  sessionKey: Buffer.alloc(32, 3),
  sessionMaxAgeSeconds: 3_600,
};

describe("sealed authentication cookies", () => {
  it("round-trips state and rejects ciphertext tampering", () => {
    const transaction = {
      state: "s".repeat(43),
      nonce: "n".repeat(43),
      codeVerifier: "v".repeat(64),
      returnTo: "/library?q=saved",
      issuedAt: 100,
      expiresAt: 700,
    };
    const sealed = sealAuthTransaction(transaction, config);
    const segments = sealed.split(".");
    const ciphertext = segments[2]!;
    const mutationIndex = Math.floor(ciphertext.length / 2);
    segments[2] = `${ciphertext.slice(0, mutationIndex)}${
      ciphertext[mutationIndex] === "A" ? "B" : "A"
    }${ciphertext.slice(mutationIndex + 1)}`;

    expect(readAuthTransaction(sealed, config, 200)).toEqual(transaction);
    expect(readAuthTransaction(segments.join("."), config, 200)).toBeNull();
    expect(readAuthTransaction(sealed, config, 700)).toBeNull();
  });

  it("keeps readable tokens inside authenticated ciphertext", () => {
    const session = {
      accessToken: "access-secret",
      refreshToken: "refresh-secret",
      idToken: "id-secret",
      tokenType: "Bearer" as const,
      scopes: ["openid", "openscholar.library"],
      subject: "researcher-1",
      accessExpiresAt: 500,
      sessionExpiresAt: 1_000,
    };
    const sealed = sealAuthSession(session, config);

    expect(sealed).not.toContain("access-secret");
    expect(sealed).not.toContain("refresh-secret");
    expect(readAuthSession(sealed, config, 200)).toEqual(session);
  });

  it("uses hardened host cookies and an interoperable S256 challenge", () => {
    expect(AUTH_SESSION_COOKIE).toMatch(/^__Host-/u);
    expect(AUTH_TRANSACTION_COOKIE).toMatch(/^__Host-/u);
    expect(authCookieOptions(600)).toMatchObject({
      httpOnly: true,
      secure: true,
      sameSite: "lax",
      path: "/",
      maxAge: 600,
    });
    expect(
      pkceChallenge(
        "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
      ),
    ).toBe("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
  });
});

describe("sanitizeReturnTo", () => {
  it("accepts only same-origin relative application paths", () => {
    expect(sanitizeReturnTo("/library?q=one", "https://research.test")).toBe(
      "/library?q=one",
    );
    expect(sanitizeReturnTo("//evil.test/path", "https://research.test")).toBe(
      "/",
    );
    expect(
      sanitizeReturnTo("/api/auth/callback", "https://research.test"),
    ).toBe("/");
  });
});
