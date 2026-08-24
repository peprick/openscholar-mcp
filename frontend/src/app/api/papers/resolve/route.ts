import { NextResponse } from "next/server";

import { routeErrorResponse, validationResponse } from "@/shared/api/route-errors";
import { paperIdentifierLookupRequestSchema } from "@/shared/api/schemas";
import { resolvePaperIdentifier } from "@/shared/api/server";

function privateResponse(response: NextResponse): NextResponse {
  response.headers.set("cache-control", "no-store");
  return response;
}

export async function GET(request: Request): Promise<NextResponse> {
  const parsed = paperIdentifierLookupRequestSchema.safeParse({
    identifier: new URL(request.url).searchParams.get("identifier") ?? "",
  });
  if (!parsed.success) {
    return privateResponse(validationResponse(parsed.error));
  }

  try {
    return privateResponse(
      NextResponse.json(
        await resolvePaperIdentifier(parsed.data.identifier),
      ),
    );
  } catch (error) {
    return privateResponse(routeErrorResponse(error));
  }
}
