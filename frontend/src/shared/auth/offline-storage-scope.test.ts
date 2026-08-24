import { describe, expect, it, vi } from "vitest";

import type { OidcAuthConfig } from "@/shared/auth/config";
import type { AuthSession } from "@/shared/auth/session";
import {
  LOCAL_OFFLINE_STORAGE_SCOPE,
  offlineStorageScope,
} from "@/shared/auth/offline-storage-scope";

vi.mock("server-only", () => ({}));

const config: OidcAuthConfig = {
  mode: "oidc",
  issuer: "https://identity.example/tenant",
  authorizationEndpoint: new URL("https://identity.example/authorize"),
  tokenEndpoint: new URL("https://identity.example/token"),
  jwksUri: new URL("https://identity.example/jwks"),
  endSessionEndpoint: null,
  clientId: "openscholar",
  clientSecret: null,
  clientAuthMethod: "none",
  redirectUri: new URL("https://research.example/api/auth/callback"),
  postLogoutRedirectUri: new URL("https://research.example/"),
  scopes: ["openid"],
  idTokenAlgorithms: ["RS256"],
  sessionKey: Buffer.alloc(32, 7),
  sessionMaxAgeSeconds: 3_600,
};

const session: AuthSession = {
  accessToken: "access-token",
  refreshToken: null,
  idToken: "id-token",
  tokenType: "Bearer",
  scopes: ["openid"],
  subject: "researcher@example.test",
  accessExpiresAt: 2_000,
  sessionExpiresAt: 3_000,
};

describe("offline browser-storage scope", () => {
  it("uses a fixed origin-local partition in local mode", () => {
    expect(offlineStorageScope({ mode: "local" }, null)).toBe(
      LOCAL_OFFLINE_STORAGE_SCOPE,
    );
  });

  it("returns no hosted partition without a readable session", () => {
    expect(offlineStorageScope(config, null)).toBeNull();
  });

  it("is stable and reveals no issuer or subject", () => {
    const first = offlineStorageScope(config, session);
    const second = offlineStorageScope(config, session);

    expect(first).toBe(second);
    expect(first).toMatch(/^oidc-v1\.[A-Za-z0-9_-]{32}$/u);
    expect(first).not.toContain("identity.example");
    expect(first).not.toContain("researcher");
  });

  it("changes across owners, issuers, and deployment keys", () => {
    const baseline = offlineStorageScope(config, session);
    expect(
      offlineStorageScope(config, { ...session, subject: "another-owner" }),
    ).not.toBe(baseline);
    expect(
      offlineStorageScope(
        { ...config, issuer: "https://another-issuer.example" },
        session,
      ),
    ).not.toBe(baseline);
    expect(
      offlineStorageScope(
        { ...config, sessionKey: Buffer.alloc(32, 8) },
        session,
      ),
    ).not.toBe(baseline);
  });
});
