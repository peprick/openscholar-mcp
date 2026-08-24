import { createHash } from "node:crypto";
import { NextRequest, NextResponse } from "next/server";

import { getAuthConfig } from "@/shared/auth/config";
import { authProblemResponse } from "@/shared/auth/responses";
import {
  ACCESS_TOKEN_REFRESH_SKEW_SECONDS,
  AUTH_SESSION_COOKIE,
  authCookieOptions,
  readAuthSession,
  sealAuthSession,
  sessionCookieMaxAge,
} from "@/shared/auth/session";
import {
  refreshAccessToken,
  type ValidatedTokenResponse,
} from "@/shared/auth/tokens";

const AUTH_FLOW_PATHS = new Set([
  "/api/auth/login",
  "/api/auth/callback",
  "/api/auth/logout",
]);
export const PUBLIC_PWA_PATHS = new Set([
  "/apple-touch-icon.png",
  "/icon.svg",
  "/icon-192.png",
  "/icon-512.png",
  "/manifest.webmanifest",
  "/offline.html",
  "/offline-pack.js",
  "/sw.js",
]);
export const PUBLIC_CONNECTIVITY_PATH = "/api/connectivity";
const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);
const refreshRequests = new Map<string, Promise<ValidatedTokenResponse>>();

async function refreshAccessTokenOnce(
  config: Parameters<typeof refreshAccessToken>[0],
  refreshToken: string,
): Promise<ValidatedTokenResponse> {
  const key = createHash("sha256").update(refreshToken).digest("base64url");
  const existing = refreshRequests.get(key);
  if (existing !== undefined) return existing;

  const pending = refreshAccessToken(config, refreshToken);
  refreshRequests.set(key, pending);
  try {
    return await pending;
  } finally {
    if (refreshRequests.get(key) === pending) refreshRequests.delete(key);
  }
}

function withRequestSessionCookie(
  request: NextRequest,
  sealedSession: string,
): Headers {
  const headers = new Headers(request.headers);
  const existing = (headers.get("cookie") ?? "")
    .split(";")
    .map((cookie) => cookie.trim())
    .filter(
      (cookie) =>
        cookie !== "" && !cookie.startsWith(`${AUTH_SESSION_COOKIE}=`),
    );
  existing.push(`${AUTH_SESSION_COOKIE}=${sealedSession}`);
  headers.set("cookie", existing.join("; "));
  return headers;
}

function clearInvalidSession(request: NextRequest): NextResponse {
  const response = NextResponse.next();
  if (request.cookies.has(AUTH_SESSION_COOKIE)) {
    response.cookies.set(AUTH_SESSION_COOKIE, "", authCookieOptions(0));
  }
  return response;
}

export async function proxy(request: NextRequest): Promise<NextResponse> {
  if (
    AUTH_FLOW_PATHS.has(request.nextUrl.pathname) ||
    PUBLIC_PWA_PATHS.has(request.nextUrl.pathname) ||
    request.nextUrl.pathname === PUBLIC_CONNECTIVITY_PATH
  ) {
    return NextResponse.next();
  }

  let config;
  try {
    config = getAuthConfig();
  } catch {
    return authProblemResponse(
      503,
      "AUTH_CONFIGURATION_ERROR",
      "Hosted authentication is not configured correctly.",
    );
  }
  if (config.mode === "local") return NextResponse.next();

  if (
    request.nextUrl.pathname.startsWith("/api/") &&
    UNSAFE_METHODS.has(request.method) &&
    request.headers.get("origin") !== config.redirectUri.origin
  ) {
    return NextResponse.json(
      {
        type: "urn:openscholar:problem:request-origin-rejected",
        title: "Request rejected",
        status: 403,
        detail: "The request did not originate from this OpenScholar site.",
        code: "REQUEST_ORIGIN_REJECTED",
      },
      {
        status: 403,
        headers: {
          "cache-control": "no-store",
          "content-type": "application/problem+json",
        },
      },
    );
  }

  const sealed = request.cookies.get(AUTH_SESSION_COOKIE)?.value;
  const now = Math.floor(Date.now() / 1_000);
  const session = readAuthSession(sealed, config, now);
  if (session === null) return clearInvalidSession(request);
  if (session.accessExpiresAt > now + ACCESS_TOKEN_REFRESH_SKEW_SECONDS) {
    return NextResponse.next();
  }
  if (session.refreshToken === null) return clearInvalidSession(request);

  try {
    const refreshed = await refreshAccessTokenOnce(config, session.refreshToken);
    const nextSession = {
      ...session,
      accessToken: refreshed.accessToken,
      refreshToken: refreshed.refreshToken ?? session.refreshToken,
      tokenType: refreshed.tokenType,
      scopes:
        refreshed.scopes.length === 0
          ? session.scopes
          : [...refreshed.scopes],
      accessExpiresAt: now + refreshed.expiresIn,
    };
    const nextSealed = sealAuthSession(nextSession, config);
    const response = NextResponse.next({
      request: { headers: withRequestSessionCookie(request, nextSealed) },
    });
    response.cookies.set(
      AUTH_SESSION_COOKIE,
      nextSealed,
      authCookieOptions(sessionCookieMaxAge(nextSession, now)),
    );
    return response;
  } catch {
    if (session.accessExpiresAt > now) return NextResponse.next();
    return clearInvalidSession(request);
  }
}

export const config = {
  matcher: [
    "/((?!_next/static|_next/image|favicon.ico|sw\\.js|manifest\\.webmanifest|offline\\.html|offline-pack\\.js|pdfjs/|.*\\.(?:svg|png|jpg|jpeg|gif|webp|ico)$).*)",
  ],
};
