import { NextResponse } from "next/server";
import { z } from "zod";

import {
  invalidJsonResponse,
  routeErrorResponse,
  validationResponse,
} from "@/shared/api/route-errors";
import { verifyPaperAccess } from "@/shared/api/server";

const accessRequestSchema = z
  .object({
    forceRefresh: z.boolean().default(false),
  })
  .strict();

type AccessRouteContext = {
  params: Promise<{ paperId: string }>;
};

export async function POST(
  request: Request,
  context: AccessRouteContext,
): Promise<NextResponse> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return invalidJsonResponse();
  }

  const parsed = accessRequestSchema.safeParse(body);
  if (!parsed.success) {
    return validationResponse(parsed.error);
  }

  try {
    const { paperId } = await context.params;
    return NextResponse.json(
      await verifyPaperAccess(paperId, parsed.data.forceRefresh),
    );
  } catch (error) {
    return routeErrorResponse(error);
  }
}
