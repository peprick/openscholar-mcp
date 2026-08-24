import { NextResponse } from "next/server";

import { getAuthConfig } from "@/shared/auth/config";
import { offlineStorageScope } from "@/shared/auth/offline-storage-scope";
import { authProblemResponse } from "@/shared/auth/responses";
import { getRequestAuthSession } from "@/shared/auth/session";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(): Promise<NextResponse> {
  try {
    const config = getAuthConfig();
    const session = await getRequestAuthSession();
    return NextResponse.json(
      {
        mode: config.mode,
        authenticated:
          config.mode === "oidc" &&
          session !== null &&
          session.accessExpiresAt > Math.floor(Date.now() / 1_000),
        storageScope: offlineStorageScope(config, session),
      },
      { headers: { "cache-control": "no-store" } },
    );
  } catch {
    return authProblemResponse(
      503,
      "AUTH_CONFIGURATION_ERROR",
      "Hosted authentication is not configured correctly.",
    );
  }
}
