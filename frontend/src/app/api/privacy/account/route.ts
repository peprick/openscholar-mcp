import { NextResponse } from "next/server";

import {
  invalidJsonResponse,
  routeErrorResponse,
  validationResponse,
} from "@/shared/api/route-errors";
import { deletePersonalDataRequestSchema } from "@/shared/api/privacy-schemas";
import { deletePersonalData } from "@/shared/api/server";
import { getAuthConfig } from "@/shared/auth/config";
import {
  AUTH_SESSION_COOKIE,
  authCookieOptions,
} from "@/shared/auth/session";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

function noStore(response: NextResponse): NextResponse {
  response.headers.set("cache-control", "no-store");
  return response;
}

export async function DELETE(request: Request): Promise<NextResponse> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return noStore(invalidJsonResponse());
  }

  const parsed = deletePersonalDataRequestSchema.safeParse(body);
  if (!parsed.success) {
    return noStore(validationResponse(parsed.error));
  }

  try {
    const authMode = getAuthConfig().mode;
    await deletePersonalData(parsed.data);
    const response = noStore(new NextResponse(null, { status: 204 }));
    response.headers.set("clear-site-data", '"storage"');
    if (authMode === "oidc") {
      response.cookies.set(
        AUTH_SESSION_COOKIE,
        "",
        authCookieOptions(0),
      );
    }
    return response;
  } catch (error) {
    return noStore(routeErrorResponse(error));
  }
}
