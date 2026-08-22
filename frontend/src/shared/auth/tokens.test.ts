import { generateKeyPairSync, sign, type KeyObject } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { OidcAuthConfig } from "@/shared/auth/config";
import { OidcProtocolError, verifyIdToken } from "@/shared/auth/tokens";

vi.mock("server-only", () => ({}));

const config: OidcAuthConfig = {
  mode: "oidc",
  issuer: "https://identity.test/tenant",
  authorizationEndpoint: new URL("https://identity.test/authorize"),
  tokenEndpoint: new URL("https://identity.test/token"),
  jwksUri: new URL("https://identity.test/keys"),
  endSessionEndpoint: null,
  clientId: "openscholar-web",
  clientSecret: null,
  clientAuthMethod: "none",
  redirectUri: new URL("https://research.test/api/auth/callback"),
  postLogoutRedirectUri: new URL("https://research.test/"),
  scopes: ["openid"],
  idTokenAlgorithms: ["RS256"],
  sessionKey: Buffer.alloc(32),
  sessionMaxAgeSeconds: 3_600,
};

function encodeJson(value: unknown): string {
  return Buffer.from(JSON.stringify(value), "utf8").toString("base64url");
}

function signedIdToken(
  privateKey: KeyObject,
  claims: Record<string, unknown>,
): string {
  const header = encodeJson({ alg: "RS256", kid: "signing-key", typ: "JWT" });
  const payload = encodeJson(claims);
  const signingInput = `${header}.${payload}`;
  return `${signingInput}.${sign(
    "RSA-SHA256",
    Buffer.from(signingInput, "ascii"),
    privateKey,
  ).toString("base64url")}`;
}

function oidcClaims(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    iss: config.issuer,
    sub: "researcher-42",
    aud: config.clientId,
    exp: 10_300,
    iat: 9_990,
    nonce: "expected-nonce",
    ...overrides,
  };
}

function mockJwks(publicKey: KeyObject): void {
  const jwk = publicKey.export({ format: "jwk" });
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation(async () =>
      Response.json({
        keys: [{ ...jwk, kid: "signing-key", use: "sig", alg: "RS256" }],
      }),
    ),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("verifyIdToken", () => {
  it("verifies provider signature, issuer, audience, time, and nonce", async () => {
    const { privateKey, publicKey } = generateKeyPairSync("rsa", {
      modulusLength: 2_048,
    });
    const idToken = signedIdToken(privateKey, oidcClaims());
    mockJwks(publicKey);

    await expect(
      verifyIdToken(config, idToken, "expected-nonce", 10_000),
    ).resolves.toEqual({ subject: "researcher-42" });
    await expect(
      verifyIdToken(config, idToken, "different-nonce", 10_000),
    ).rejects.toBeInstanceOf(OidcProtocolError);
    await expect(
      verifyIdToken(config, `${idToken}=`, "expected-nonce", 10_000),
    ).rejects.toBeInstanceOf(OidcProtocolError);
  });

  it("rejects a mismatched authorized party and a token used before nbf", async () => {
    const { privateKey, publicKey } = generateKeyPairSync("rsa", {
      modulusLength: 2_048,
    });
    mockJwks(publicKey);

    await expect(
      verifyIdToken(
        config,
        signedIdToken(privateKey, oidcClaims({ azp: "different-client" })),
        "expected-nonce",
        10_000,
      ),
    ).rejects.toBeInstanceOf(OidcProtocolError);
    await expect(
      verifyIdToken(
        config,
        signedIdToken(privateKey, oidcClaims({ nbf: 10_061 })),
        "expected-nonce",
        10_000,
      ),
    ).rejects.toBeInstanceOf(OidcProtocolError);
  });

  it("rejects RSA verification keys smaller than 2048 bits", async () => {
    const { privateKey, publicKey } = generateKeyPairSync("rsa", {
      modulusLength: 1_024,
    });
    mockJwks(publicKey);

    await expect(
      verifyIdToken(
        config,
        signedIdToken(privateKey, oidcClaims()),
        "expected-nonce",
        10_000,
      ),
    ).rejects.toBeInstanceOf(OidcProtocolError);
  });

  it("stops reading an oversized JWKS response without trusting its shape", async () => {
    const { privateKey } = generateKeyPairSync("rsa", {
      modulusLength: 2_048,
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ keys: [], padding: "x".repeat(300_000) })),
      ),
    );

    await expect(
      verifyIdToken(
        config,
        signedIdToken(privateKey, oidcClaims()),
        "expected-nonce",
        10_000,
      ),
    ).rejects.toBeInstanceOf(OidcProtocolError);
  });
});
