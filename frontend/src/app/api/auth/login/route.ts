import { NextResponse } from "next/server";

import { getAuthConfig } from "@/shared/auth/config";
import { authDisabledResponse, authProblemResponse } from "@/shared/auth/responses";
import { pkceChallenge, randomUrlSafeValue } from "@/shared/auth/seal";
import {
  AUTH_TRANSACTION_COOKIE,
  AUTH_TRANSACTION_MAX_AGE_SECONDS,
  authCookieOptions,
  sanitizeReturnTo,
  sealAuthTransaction,
} from "@/shared/auth/session";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: Request): Promise<NextResponse> {
  try {
    const config = getAuthConfig();
    if (config.mode === "local") return authDisabledResponse();

    const now = Math.floor(Date.now() / 1_000);
    const requestUrl = new URL(request.url);
    const state = randomUrlSafeValue();
    const nonce = randomUrlSafeValue();
    const codeVerifier = randomUrlSafeValue(48);
    const returnTo = sanitizeReturnTo(
      requestUrl.searchParams.get("returnTo"),
      config.redirectUri.origin,
    );
    const authorizationUrl = new URL(config.authorizationEndpoint);
    authorizationUrl.searchParams.set("response_type", "code");
    authorizationUrl.searchParams.set("client_id", config.clientId);
    authorizationUrl.searchParams.set("redirect_uri", config.redirectUri.toString());
    authorizationUrl.searchParams.set("scope", config.scopes.join(" "));
    authorizationUrl.searchParams.set("state", state);
    authorizationUrl.searchParams.set("nonce", nonce);
    authorizationUrl.searchParams.set("code_challenge", pkceChallenge(codeVerifier));
    authorizationUrl.searchParams.set("code_challenge_method", "S256");

    const response = NextResponse.redirect(authorizationUrl, 302);
    response.headers.set("cache-control", "no-store");
    response.headers.set("referrer-policy", "no-referrer");
    response.cookies.set(
      AUTH_TRANSACTION_COOKIE,
      sealAuthTransaction(
        {
          state,
          nonce,
          codeVerifier,
          returnTo,
          issuedAt: now,
          expiresAt: now + AUTH_TRANSACTION_MAX_AGE_SECONDS,
        },
        config,
      ),
      authCookieOptions(AUTH_TRANSACTION_MAX_AGE_SECONDS),
    );
    return response;
  } catch {
    return authProblemResponse(
      503,
      "AUTH_CONFIGURATION_ERROR",
      "Hosted authentication is not configured correctly.",
    );
  }
}
