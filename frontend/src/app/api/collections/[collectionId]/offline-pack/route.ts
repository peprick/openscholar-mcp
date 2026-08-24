import { NextResponse } from "next/server";
import { z } from "zod";

import { routeErrorResponse, validationResponse } from "@/shared/api/route-errors";
import { getOfflineCollectionPack } from "@/shared/api/server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type OfflinePackRouteContext = {
  params: Promise<{ collectionId: string }>;
};

const collectionIdSchema = z.string().uuid();

function privateResponse(response: NextResponse): NextResponse {
  response.headers.set("cache-control", "no-store");
  response.headers.set("x-content-type-options", "nosniff");
  return response;
}

export async function GET(
  _request: Request,
  context: OfflinePackRouteContext,
): Promise<NextResponse> {
  const collectionId = collectionIdSchema.safeParse(
    (await context.params).collectionId,
  );
  if (!collectionId.success) {
    return privateResponse(validationResponse(collectionId.error));
  }

  try {
    return privateResponse(
      NextResponse.json(await getOfflineCollectionPack(collectionId.data)),
    );
  } catch (error) {
    return privateResponse(routeErrorResponse(error));
  }
}
