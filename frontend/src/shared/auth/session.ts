import "server-only";

import { cookies } from "next/headers";
import { z } from "zod";

import { getAuthConfig, type OidcAuthConfig } from "@/shared/auth/config";
import { sealJson, unsealJson } from "@/shared/auth/seal";

export const AUTH_SESSION_COOKIE = "__Host-openscholar-session";
export const AUTH_TRANSACTION_COOKIE = "__Host-openscholar-oidc";
export const AUTH_TRANSACTION_MAX_AGE_SECONDS = 10 * 60;
export const ACCESS_TOKEN_REFRESH_SKEW_SECONDS = 60;
const TRANSACTION_CLOCK_SKEW_SECONDS = 60;

const TOKEN_MAX_LENGTH = 16_384;
const oauthTokenSchema = z
  .string()
  .min(1)
  .max(TOKEN_MAX_LENGTH)
  .regex(/^[\x21-\x7E]+$/u);
const transactionSchema = z.object({
  state: z.string().min(32).max(256),
  nonce: z.string().min(32).max(256),
  codeVerifier: z.string().min(43).max(128),
  returnTo: z.string().min(1).max(2_048),
  issuedAt: z.number().int().nonnegative(),
  expiresAt: z.number().int().positive(),
});

const sessionSchema = z.object({
  accessToken: oauthTokenSchema,
  refreshToken: oauthTokenSchema.nullable(),
  idToken: oauthTokenSchema,
  tokenType: z.literal("Bearer"),
  scopes: z.array(z.string().min(1).max(256)).max(128),
  subject: z.string().min(1).max(512),
  accessExpiresAt: z.number().int().positive(),
  sessionExpiresAt: z.number().int().positive(),
});

export type AuthTransaction = z.infer<typeof transactionSchema>;
export type AuthSession = z.infer<typeof sessionSchema>;

const TRANSACTION_PURPOSE = "oidc-authorization-transaction";
const SESSION_PURPOSE = "oidc-session";

export function authCookieOptions(maxAge: number): {
  httpOnly: true;
  secure: true;
  sameSite: "lax";
  path: "/";
  maxAge: number;
  priority: "high";
} {
  return {
    httpOnly: true,
    secure: true,
    sameSite: "lax",
    path: "/",
    maxAge: Math.max(0, Math.floor(maxAge)),
    priority: "high",
  };
}

export function sealAuthTransaction(
  transaction: AuthTransaction,
  config: OidcAuthConfig,
): string {
  return sealJson(transactionSchema.parse(transaction), config.sessionKey, TRANSACTION_PURPOSE);
}

export function readAuthTransaction(
  sealed: string | undefined,
  config: OidcAuthConfig,
  nowSeconds = Math.floor(Date.now() / 1_000),
): AuthTransaction | null {
  if (!sealed) return null;
  const parsed = transactionSchema.safeParse(
    unsealJson(sealed, config.sessionKey, TRANSACTION_PURPOSE),
  );
  if (
    !parsed.success ||
    parsed.data.expiresAt <= nowSeconds ||
    parsed.data.issuedAt > nowSeconds + TRANSACTION_CLOCK_SKEW_SECONDS ||
    parsed.data.expiresAt <= parsed.data.issuedAt ||
    parsed.data.expiresAt - parsed.data.issuedAt >
      AUTH_TRANSACTION_MAX_AGE_SECONDS
  ) {
    return null;
  }
  return parsed.data;
}

export function sealAuthSession(
  session: AuthSession,
  config: OidcAuthConfig,
): string {
  return sealJson(sessionSchema.parse(session), config.sessionKey, SESSION_PURPOSE);
}

export function readAuthSession(
  sealed: string | undefined,
  config: OidcAuthConfig,
  nowSeconds = Math.floor(Date.now() / 1_000),
): AuthSession | null {
  if (!sealed) return null;
  const parsed = sessionSchema.safeParse(
    unsealJson(sealed, config.sessionKey, SESSION_PURPOSE),
  );
  if (!parsed.success || parsed.data.sessionExpiresAt <= nowSeconds) return null;
  return parsed.data;
}

export async function getRequestAuthSession(): Promise<AuthSession | null> {
  const config = getAuthConfig();
  if (config.mode === "local") return null;
  const cookieStore = await cookies();
  return readAuthSession(cookieStore.get(AUTH_SESSION_COOKIE)?.value, config);
}

export async function getBackendAccessToken(): Promise<string | null> {
  const config = getAuthConfig();
  if (config.mode === "local") return null;
  const session = await getRequestAuthSession();
  const now = Math.floor(Date.now() / 1_000);
  return session !== null && session.accessExpiresAt > now
    ? session.accessToken
    : null;
}

export function accessTokenIsCurrent(session: AuthSession | null): boolean {
  return (
    session !== null &&
    session.accessExpiresAt > Math.floor(Date.now() / 1_000)
  );
}

export function sessionCookieMaxAge(
  session: AuthSession,
  nowSeconds = Math.floor(Date.now() / 1_000),
): number {
  return Math.max(0, session.sessionExpiresAt - nowSeconds);
}

export function sanitizeReturnTo(
  candidate: string | null,
  publicOrigin: string,
): string {
  if (!candidate || !candidate.startsWith("/") || candidate.startsWith("//")) {
    return "/";
  }
  try {
    const resolved = new URL(candidate, publicOrigin);
    if (
      resolved.origin !== publicOrigin ||
      resolved.pathname.startsWith("/api/auth/")
    ) {
      return "/";
    }
    return `${resolved.pathname}${resolved.search}${resolved.hash}`;
  } catch {
    return "/";
  }
}
