import { NextResponse } from "next/server";
import { z } from "zod";

import { savedPaperMutationSchema } from "@/shared/api/library-schemas";
import {
  invalidJsonResponse,
  routeErrorResponse,
  validationResponse,
} from "@/shared/api/route-errors";
import {
  deleteCollectionPaper,
  saveCollectionPaper,
} from "@/shared/api/server";

type SavedPaperRouteContext = {
  params: Promise<{ collectionId: string; paperId: string }>;
};

const routeParamsSchema = z.object({
  collectionId: z.string().uuid(),
  paperId: z.string().uuid(),
});

async function mutate(
  request: Request,
  context: SavedPaperRouteContext,
  method: "PUT" | "PATCH",
): Promise<NextResponse> {
  const params = routeParamsSchema.safeParse(await context.params);
  if (!params.success) return validationResponse(params.error);
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return invalidJsonResponse();
  }
  const parsed = savedPaperMutationSchema.safeParse(body);
  if (!parsed.success) return validationResponse(parsed.error);

  try {
    return NextResponse.json(
      await saveCollectionPaper(
        params.data.collectionId,
        params.data.paperId,
        parsed.data,
        method,
      ),
    );
  } catch (error) {
    return routeErrorResponse(error);
  }
}

export async function PUT(
  request: Request,
  context: SavedPaperRouteContext,
): Promise<NextResponse> {
  return mutate(request, context, "PUT");
}

export async function PATCH(
  request: Request,
  context: SavedPaperRouteContext,
): Promise<NextResponse> {
  return mutate(request, context, "PATCH");
}

export async function DELETE(
  _request: Request,
  context: SavedPaperRouteContext,
): Promise<NextResponse> {
  const params = routeParamsSchema.safeParse(await context.params);
  if (!params.success) return validationResponse(params.error);
  try {
    await deleteCollectionPaper(
      params.data.collectionId,
      params.data.paperId,
    );
    return new NextResponse(null, { status: 204 });
  } catch (error) {
    return routeErrorResponse(error);
  }
}
