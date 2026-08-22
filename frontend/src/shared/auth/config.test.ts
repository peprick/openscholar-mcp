import { afterEach, describe, expect, it, vi } from "vitest";

import { AuthConfigurationError, getAuthConfig } from "@/shared/auth/config";

vi.mock("server-only", () => ({}));

const SESSION_SECRET = Buffer.alloc(32, 7).toString("base64");

function configureOidc(): void {
  vi.stubEnv("OPENSCHOLAR_AUTH_MODE", "oidc");
  vi.stubEnv("OPENSCHOLAR_AUTH_SESSION_SECRET", SESSION_SECRET);
  vi.stubEnv("OPENSCHOLAR_OIDC_ISSUER", "https://identity.test/tenant/");
  vi.stubEnv(
    "OPENSCHOLAR_OIDC_AUTHORIZATION_ENDPOINT",
    "https://identity.test/oauth2/authorize?policy=research",
  );
  vi.stubEnv("OPENSCHOLAR_OIDC_TOKEN_ENDPOINT", "https://identity.test/oauth2/token");
  vi.stubEnv("OPENSCHOLAR_OIDC_JWKS_URI", "https://identity.test/keys");
  vi.stubEnv("OPENSCHOLAR_OIDC_CLIENT_ID", "openscholar-web");
  vi.stubEnv(
    "OPENSCHOLAR_OIDC_REDIRECT_URI",
    "https://research.test/api/auth/callback",
  );
  vi.stubEnv(
    "OPENSCHOLAR_OIDC_POST_LOGOUT_REDIRECT_URI",
    "https://research.test/signed-out",
  );
}

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("getAuthConfig", () => {
  it("keeps local mode as the zero-configuration default", () => {
    vi.stubEnv("OPENSCHOLAR_OIDC_CLIENT_ID", "ignored-in-local-mode");

    expect(getAuthConfig()).toEqual({ mode: "local" });
  });

  it("preserves explicitly configured provider endpoints and issuer values", () => {
    configureOidc();

    const config = getAuthConfig();
    expect(config).toMatchObject({
      mode: "oidc",
      issuer: "https://identity.test/tenant/",
      clientId: "openscholar-web",
      clientAuthMethod: "none",
      sessionMaxAgeSeconds: 28_800,
    });
    if (config.mode !== "oidc") throw new Error("Expected OIDC configuration");
    expect(config.authorizationEndpoint.toString()).toBe(
      "https://identity.test/oauth2/authorize?policy=research",
    );
    expect(config.redirectUri.toString()).toBe(
      "https://research.test/api/auth/callback",
    );
    expect(config.scopes).toContain("openid");
    expect(config.sessionKey).toEqual(Buffer.alloc(32, 7));
  });

  it("rejects insecure endpoints and weak cookie keys", () => {
    configureOidc();
    vi.stubEnv("OPENSCHOLAR_OIDC_TOKEN_ENDPOINT", "http://identity.test/token");
    expect(() => getAuthConfig()).toThrow(AuthConfigurationError);

    configureOidc();
    vi.stubEnv(
      "OPENSCHOLAR_AUTH_SESSION_SECRET",
      Buffer.alloc(16).toString("base64"),
    );
    expect(() => getAuthConfig()).toThrow(/exactly 32 bytes/u);
  });

  it("requires a secret for confidential-client authentication", () => {
    configureOidc();
    vi.stubEnv("OPENSCHOLAR_OIDC_CLIENT_AUTH_METHOD", "client_secret_basic");

    expect(() => getAuthConfig()).toThrow(/CLIENT_SECRET/u);
  });
});
