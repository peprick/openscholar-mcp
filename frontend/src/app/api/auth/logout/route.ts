import { cookies } from "next/headers";
import { NextResponse } from "next/server";

import { getAuthConfig } from "@/shared/auth/config";
import { authDisabledResponse, authProblemResponse } from "@/shared/auth/responses";
import {
  AUTH_SESSION_COOKIE,
  AUTH_TRANSACTION_COOKIE,
  authCookieOptions,
  readAuthSession,
} from "@/shared/auth/session";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(request: Request): Promise<NextResponse> {
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
  if (request.headers.get("origin") !== config.redirectUri.origin) {
    return authProblemResponse(
      403,
      "LOGOUT_ORIGIN_REJECTED",
      "The sign-out request did not come from this OpenScholar site.",
    );
  }

  const cookieStore = await cookies();
  const session = readAuthSession(
    cookieStore.get(AUTH_SESSION_COOKIE)?.value,
    config,
  );
  let destination = config.postLogoutRedirectUri;
  if (config.endSessionEndpoint !== null) {
    destination = new URL(config.endSessionEndpoint);
    destination.searchParams.set(
      "post_logout_redirect_uri",
      config.postLogoutRedirectUri.toString(),
    );
    destination.searchParams.set("client_id", config.clientId);
    if (session !== null) {
      destination.searchParams.set("id_token_hint", session.idToken);
    }
  }

  const response = NextResponse.redirect(destination, 303);
  response.headers.set("cache-control", "no-store");
  response.headers.set("clear-site-data", '"storage"');
  response.headers.set("referrer-policy", "no-referrer");
  response.cookies.set(AUTH_SESSION_COOKIE, "", authCookieOptions(0));
  response.cookies.set(AUTH_TRANSACTION_COOKIE, "", authCookieOptions(0));
  return response;
}
