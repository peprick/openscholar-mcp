import { cookies } from "next/headers";
import { NextResponse } from "next/server";

import { getAuthConfig } from "@/shared/auth/config";
import { authDisabledResponse, authProblemResponse } from "@/shared/auth/responses";
import { constantTimeEqual } from "@/shared/auth/seal";
import {
  AUTH_SESSION_COOKIE,
  AUTH_TRANSACTION_COOKIE,
  authCookieOptions,
  readAuthTransaction,
  sealAuthSession,
} from "@/shared/auth/session";
import {
  exchangeAuthorizationCode,
  OidcProtocolError,
  verifyIdToken,
} from "@/shared/auth/tokens";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

function clearTransaction(response: NextResponse): NextResponse {
  response.cookies.set(AUTH_TRANSACTION_COOKIE, "", authCookieOptions(0));
  return response;
}

export async function GET(request: Request): Promise<NextResponse> {
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
  if (config.mode === "local") return authDisabledResponse();

  const requestUrl = new URL(request.url);
  const code = requestUrl.searchParams.get("code");
  const state = requestUrl.searchParams.get("state");
  const providerError = requestUrl.searchParams.get("error");
  const cookieStore = await cookies();
  const transaction = readAuthTransaction(
    cookieStore.get(AUTH_TRANSACTION_COOKIE)?.value,
    config,
  );
  if (
    providerError !== null ||
    code === null ||
    code.length > 4_096 ||
    state === null ||
    state.length > 256 ||
    transaction === null ||
    !constantTimeEqual(state, transaction.state)
  ) {
    return clearTransaction(
      authProblemResponse(
        400,
        "OIDC_CALLBACK_REJECTED",
        "The sign-in response was missing, expired, or did not match the initiating browser.",
      ),
    );
  }

  try {
    const tokens = await exchangeAuthorizationCode(
      config,
      code,
      transaction.codeVerifier,
    );
    if (tokens.idToken === null) throw new OidcProtocolError();
    const identity = await verifyIdToken(config, tokens.idToken, transaction.nonce);
    const now = Math.floor(Date.now() / 1_000);
    const session = {
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
      idToken: tokens.idToken,
      tokenType: tokens.tokenType,
      scopes: [...tokens.scopes],
      subject: identity.subject,
      accessExpiresAt: now + tokens.expiresIn,
      sessionExpiresAt: now + config.sessionMaxAgeSeconds,
    };
    const response = NextResponse.redirect(
      new URL(transaction.returnTo, config.redirectUri.origin),
      303,
    );
    response.headers.set("cache-control", "no-store");
    response.headers.set("referrer-policy", "no-referrer");
    response.cookies.set(
      AUTH_SESSION_COOKIE,
      sealAuthSession(session, config),
      authCookieOptions(config.sessionMaxAgeSeconds),
    );
    return clearTransaction(response);
  } catch (error) {
    const unavailable =
      error instanceof OidcProtocolError &&
      error.message.includes("temporarily unavailable");
    return clearTransaction(
      authProblemResponse(
        unavailable ? 502 : 400,
        unavailable ? "OIDC_PROVIDER_UNAVAILABLE" : "OIDC_RESPONSE_INVALID",
        unavailable
          ? "The identity provider could not complete sign-in."
          : "The identity provider response could not be verified.",
      ),
    );
  }
}
