import { NextResponse } from "next/server";

import {
  invalidJsonResponse,
  routeErrorResponse,
  validationResponse,
} from "@/shared/api/route-errors";
import { createSearchRequestSchema } from "@/shared/api/schemas";
import { createSearch } from "@/shared/api/server";

export async function POST(request: Request): Promise<NextResponse> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return invalidJsonResponse();
  }

  const parsed = createSearchRequestSchema.safeParse(body);
  if (!parsed.success) {
    return validationResponse(parsed.error);
  }

  try {
    const result = await createSearch(parsed.data);
    const headers = new Headers();
    if (result.status === 201) {
      headers.set("location", `/searches/${result.data.searchId}`);
    }
    return NextResponse.json(result.data, {
      status: result.status,
      headers,
    });
  } catch (error) {
    return routeErrorResponse(error);
  }
}
