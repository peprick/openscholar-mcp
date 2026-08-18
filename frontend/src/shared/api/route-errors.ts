import { NextResponse } from "next/server";
import type { ZodError } from "zod";

import { BackendApiError } from "@/shared/api/server";

const PROBLEM_CONTENT_TYPE = "application/problem+json";

export function invalidJsonResponse(): NextResponse {
  return NextResponse.json(
    {
      type: "urn:openscholar:problem:invalid-request",
      title: "Invalid request",
      status: 400,
      detail: "The request body must contain valid JSON.",
      code: "INVALID_REQUEST",
    },
    { status: 400, headers: { "content-type": PROBLEM_CONTENT_TYPE } },
  );
}

export function validationResponse(error: ZodError): NextResponse {
  return NextResponse.json(
    {
      type: "urn:openscholar:problem:validation-failed",
      title: "Request validation failed",
      status: 400,
      detail: "One or more request fields are invalid.",
      code: "VALIDATION_FAILED",
      violations: error.issues.map((issue) => ({
        field: issue.path.join(".") || "request",
        message: issue.message,
      })),
    },
    { status: 400, headers: { "content-type": PROBLEM_CONTENT_TYPE } },
  );
}

export function routeErrorResponse(error: unknown): NextResponse {
  if (error instanceof BackendApiError) {
    const headers = new Headers({ "content-type": PROBLEM_CONTENT_TYPE });
    if (error.retryAfter !== null) {
      headers.set("retry-after", error.retryAfter);
    }
    return NextResponse.json(error.problem, {
      status: error.status,
      headers,
    });
  }

  return NextResponse.json(
    {
      type: "urn:openscholar:problem:frontend-gateway-failure",
      title: "Request failed",
      status: 502,
      detail: "OpenScholar could not safely process the backend response.",
      code: "FRONTEND_GATEWAY_FAILURE",
      retryable: true,
    },
    { status: 502, headers: { "content-type": PROBLEM_CONTENT_TYPE } },
  );
}
