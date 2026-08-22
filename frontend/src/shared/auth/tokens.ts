import "server-only";

import {
  constants,
  createPublicKey,
  verify as verifySignature,
  type JsonWebKey,
  type KeyObject,
} from "node:crypto";
import { z } from "zod";

import type { OidcAuthConfig } from "@/shared/auth/config";

const TOKEN_RESPONSE_MAX_BYTES = 64 * 1_024;
const JWKS_RESPONSE_MAX_BYTES = 256 * 1_024;
const TOKEN_MAX_LENGTH = 16_384;
const REQUEST_TIMEOUT_MS = 10_000;
const CLOCK_SKEW_SECONDS = 60;

const oauthTokenSchema = z
  .string()
  .min(1)
  .max(TOKEN_MAX_LENGTH)
  .regex(/^[\x21-\x7E]+$/u);

const tokenResponseSchema = z.object({
  access_token: oauthTokenSchema,
  token_type: z.string().min(1).max(64),
  expires_in: z.number().int().positive().max(7 * 24 * 60 * 60),
  refresh_token: oauthTokenSchema.optional(),
  id_token: oauthTokenSchema.optional(),
  scope: z.string().max(8_192).optional(),
});

const jwtHeaderSchema = z.object({
  alg: z.enum(["RS256", "PS256", "ES256"]),
  kid: z.string().min(1).max(512).optional(),
  typ: z.string().max(64).optional(),
});

const idTokenClaimsSchema = z.object({
  iss: z.string().min(1).max(2_048),
  sub: z.string().min(1).max(512),
  aud: z.union([
    z.string().min(1).max(2_048),
    z.array(z.string().min(1).max(2_048)).min(1).max(32),
  ]),
  azp: z.string().min(1).max(2_048).optional(),
  exp: z.number().int().positive(),
  iat: z.number().int().positive(),
  nbf: z.number().int().nonnegative().optional(),
  nonce: z.string().min(1).max(512),
});

const jwkSchema = z
  .object({
    kid: z.string().min(1).max(512).optional(),
    kty: z.string().min(1).max(32),
    use: z.string().max(32).optional(),
    alg: z.string().max(32).optional(),
    crv: z.string().max(32).optional(),
    key_ops: z.array(z.string().min(1).max(32)).max(16).optional(),
  })
  .passthrough();

const jwksSchema = z.object({
  keys: z.array(jwkSchema).min(1).max(128),
});

export type ValidatedTokenResponse = {
  accessToken: string;
  tokenType: "Bearer";
  expiresIn: number;
  refreshToken: string | null;
  idToken: string | null;
  scopes: readonly string[];
};

export type ValidatedIdToken = {
  subject: string;
};

export class OidcProtocolError extends Error {
  constructor(message = "The identity provider returned an invalid response.") {
    super(message);
    this.name = "OidcProtocolError";
  }
}

function clientAuthentication(
  config: OidcAuthConfig,
  form: URLSearchParams,
  headers: Headers,
): void {
  if (config.clientAuthMethod === "client_secret_basic") {
    const secret = config.clientSecret;
    if (secret === null) throw new OidcProtocolError();
    const formEncode = (value: string): string =>
      new URLSearchParams({ value }).toString().slice("value=".length);
    const credentials = Buffer.from(
      `${formEncode(config.clientId)}:${formEncode(secret)}`,
      "utf8",
    ).toString("base64");
    headers.set("authorization", `Basic ${credentials}`);
    return;
  }
  form.set("client_id", config.clientId);
  if (config.clientAuthMethod === "client_secret_post") {
    const secret = config.clientSecret;
    if (secret === null) throw new OidcProtocolError();
    form.set("client_secret", secret);
  }
}

async function boundedJson(
  response: Response,
  maxBytes: number,
): Promise<unknown> {
  const contentLength = response.headers.get("content-length");
  if (
    contentLength !== null &&
    (!/^\d+$/u.test(contentLength) || Number(contentLength) > maxBytes)
  ) {
    throw new OidcProtocolError();
  }

  const chunks: Uint8Array[] = [];
  let received = 0;
  const reader = response.body?.getReader();
  if (reader !== undefined) {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      received += value.byteLength;
      if (received > maxBytes) {
        await reader.cancel();
        throw new OidcProtocolError();
      }
      chunks.push(value);
    }
  }

  try {
    return JSON.parse(Buffer.concat(chunks, received).toString("utf8")) as unknown;
  } catch {
    throw new OidcProtocolError();
  }
}

function validateTokenResponse(value: unknown): ValidatedTokenResponse {
  const parsed = tokenResponseSchema.safeParse(value);
  if (!parsed.success || parsed.data.token_type.toLowerCase() !== "bearer") {
    throw new OidcProtocolError();
  }
  const scopes = parsed.data.scope
    ? [...new Set(parsed.data.scope.split(/\s+/u).filter(Boolean))]
    : [];
  return {
    accessToken: parsed.data.access_token,
    tokenType: "Bearer",
    expiresIn: parsed.data.expires_in,
    refreshToken: parsed.data.refresh_token ?? null,
    idToken: parsed.data.id_token ?? null,
    scopes,
  };
}

async function tokenRequest(
  config: OidcAuthConfig,
  form: URLSearchParams,
): Promise<ValidatedTokenResponse> {
  const headers = new Headers({
    accept: "application/json",
    "content-type": "application/x-www-form-urlencoded",
  });
  clientAuthentication(config, form, headers);

  let response: Response;
  try {
    response = await fetch(config.tokenEndpoint, {
      method: "POST",
      headers,
      body: form,
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch {
    throw new OidcProtocolError("The identity provider is temporarily unavailable.");
  }
  const body = await boundedJson(response, TOKEN_RESPONSE_MAX_BYTES);
  if (!response.ok) throw new OidcProtocolError();
  return validateTokenResponse(body);
}

export async function exchangeAuthorizationCode(
  config: OidcAuthConfig,
  code: string,
  codeVerifier: string,
): Promise<ValidatedTokenResponse> {
  const form = new URLSearchParams({
    grant_type: "authorization_code",
    code,
    redirect_uri: config.redirectUri.toString(),
    code_verifier: codeVerifier,
  });
  return tokenRequest(config, form);
}

export async function refreshAccessToken(
  config: OidcAuthConfig,
  refreshToken: string,
): Promise<ValidatedTokenResponse> {
  const form = new URLSearchParams({
    grant_type: "refresh_token",
    refresh_token: refreshToken,
  });
  return tokenRequest(config, form);
}

function decodeJwtBase64Url(part: string): Buffer {
  if (!/^[A-Za-z0-9_-]+$/u.test(part)) throw new OidcProtocolError();
  const decoded = Buffer.from(part, "base64url");
  if (decoded.toString("base64url") !== part) throw new OidcProtocolError();
  return decoded;
}

function decodeJwtPart(part: string): unknown {
  try {
    return JSON.parse(decodeJwtBase64Url(part).toString("utf8")) as unknown;
  } catch {
    throw new OidcProtocolError();
  }
}

async function findVerificationKey(
  config: OidcAuthConfig,
  kid: string | undefined,
  alg: string,
): Promise<KeyObject> {
  let response: Response;
  try {
    response = await fetch(config.jwksUri, {
      headers: { accept: "application/json" },
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch {
    throw new OidcProtocolError("The identity provider verification keys are unavailable.");
  }
  const body = await boundedJson(response, JWKS_RESPONSE_MAX_BYTES);
  if (!response.ok) throw new OidcProtocolError();
  const parsed = jwksSchema.safeParse(body);
  if (!parsed.success) throw new OidcProtocolError();

  const candidates = parsed.data.keys.filter(
    (key) =>
      (kid === undefined || key.kid === kid) &&
      (key.use === undefined || key.use === "sig") &&
      (key.alg === undefined || key.alg === alg) &&
      (key.key_ops === undefined || key.key_ops.includes("verify")) &&
      ((alg === "ES256" && key.kty === "EC" && key.crv === "P-256") ||
        ((alg === "RS256" || alg === "PS256") && key.kty === "RSA")),
  );
  if (candidates.length !== 1) throw new OidcProtocolError();
  try {
    const verificationKey = createPublicKey({
      key: candidates[0] as JsonWebKey,
      format: "jwk",
    });
    if (
      (alg === "ES256" && verificationKey.asymmetricKeyType !== "ec") ||
      ((alg === "RS256" || alg === "PS256") &&
        (verificationKey.asymmetricKeyType !== "rsa" ||
          (verificationKey.asymmetricKeyDetails?.modulusLength ?? 0) < 2_048))
    ) {
      throw new OidcProtocolError();
    }
    return verificationKey;
  } catch {
    throw new OidcProtocolError();
  }
}

function signatureIsValid(
  algorithm: "RS256" | "PS256" | "ES256",
  key: KeyObject,
  signingInput: Buffer,
  signature: Buffer,
): boolean {
  if (algorithm === "PS256") {
    return verifySignature(
      "sha256",
      signingInput,
      {
        key,
        padding: constants.RSA_PKCS1_PSS_PADDING,
        saltLength: constants.RSA_PSS_SALTLEN_DIGEST,
      },
      signature,
    );
  }
  if (algorithm === "ES256") {
    return verifySignature(
      "sha256",
      signingInput,
      { key, dsaEncoding: "ieee-p1363" },
      signature,
    );
  }
  return verifySignature("RSA-SHA256", signingInput, key, signature);
}

export async function verifyIdToken(
  config: OidcAuthConfig,
  idToken: string,
  expectedNonce: string,
  nowSeconds = Math.floor(Date.now() / 1_000),
): Promise<ValidatedIdToken> {
  if (idToken.length > TOKEN_MAX_LENGTH) throw new OidcProtocolError();
  const parts = idToken.split(".");
  if (parts.length !== 3) throw new OidcProtocolError();
  const header = jwtHeaderSchema.safeParse(decodeJwtPart(parts[0] ?? ""));
  const claims = idTokenClaimsSchema.safeParse(decodeJwtPart(parts[1] ?? ""));
  if (!header.success || !claims.success) throw new OidcProtocolError();
  if (
    header.data.typ !== undefined &&
    header.data.typ.toUpperCase() !== "JWT"
  ) {
    throw new OidcProtocolError();
  }
  if (!config.idTokenAlgorithms.includes(header.data.alg)) {
    throw new OidcProtocolError();
  }

  const key = await findVerificationKey(config, header.data.kid, header.data.alg);
  const signingInput = Buffer.from(`${parts[0]}.${parts[1]}`, "ascii");
  const signature = decodeJwtBase64Url(parts[2] ?? "");
  let validSignature = false;
  try {
    validSignature = signatureIsValid(
      header.data.alg,
      key,
      signingInput,
      signature,
    );
  } catch {
    throw new OidcProtocolError();
  }
  if (!validSignature) {
    throw new OidcProtocolError();
  }

  const audience = Array.isArray(claims.data.aud)
    ? claims.data.aud
    : [claims.data.aud];
  if (
    claims.data.iss !== config.issuer ||
    !audience.includes(config.clientId) ||
    (audience.length > 1 && claims.data.azp !== config.clientId) ||
    (claims.data.azp !== undefined && claims.data.azp !== config.clientId) ||
    claims.data.exp <= nowSeconds - CLOCK_SKEW_SECONDS ||
    claims.data.exp <= claims.data.iat ||
    claims.data.iat > nowSeconds + CLOCK_SKEW_SECONDS ||
    (claims.data.nbf !== undefined &&
      claims.data.nbf > nowSeconds + CLOCK_SKEW_SECONDS) ||
    claims.data.nonce !== expectedNonce
  ) {
    throw new OidcProtocolError();
  }
  return { subject: claims.data.sub };
}
