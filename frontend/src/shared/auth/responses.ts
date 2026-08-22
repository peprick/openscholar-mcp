import "server-only";

import { NextResponse } from "next/server";

export function authProblemResponse(
  status: number,
  code: string,
  detail: string,
): NextResponse {
  return NextResponse.json(
    {
      type: `urn:openscholar:problem:${code.toLowerCase().replaceAll("_", "-")}`,
      title: "Authentication failed",
      status,
      detail,
      code,
    },
    {
      status,
      headers: {
        "cache-control": "no-store",
        "content-type": "application/problem+json",
        "referrer-policy": "no-referrer",
      },
    },
  );
}

export function authDisabledResponse(): NextResponse {
  return authProblemResponse(
    404,
    "AUTH_NOT_ENABLED",
    "Hosted authentication is not enabled for this OpenScholar instance.",
  );
}
