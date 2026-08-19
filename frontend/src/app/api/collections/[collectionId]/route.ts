import { NextResponse } from "next/server";
import { z } from "zod";

import {
  collectionListQuerySchema,
  updateCollectionRequestSchema,
} from "@/shared/api/library-schemas";
import {
  invalidJsonResponse,
  routeErrorResponse,
  validationResponse,
} from "@/shared/api/route-errors";
import {
  deleteCollection,
  getCollection,
  updateCollection,
} from "@/shared/api/server";

type CollectionRouteContext = {
  params: Promise<{ collectionId: string }>;
};

const collectionIdSchema = z.string().uuid();

async function validatedCollectionId(
  context: CollectionRouteContext,
): Promise<ReturnType<typeof collectionIdSchema.safeParse>> {
  return collectionIdSchema.safeParse((await context.params).collectionId);
}

export async function GET(
  request: Request,
  context: CollectionRouteContext,
): Promise<NextResponse> {
  const collectionId = await validatedCollectionId(context);
  if (!collectionId.success) return validationResponse(collectionId.error);
  const searchParams = new URL(request.url).searchParams;
  const query = collectionListQuerySchema.safeParse({
    page:
      searchParams.get("page") === null
        ? undefined
        : Number(searchParams.get("page")),
    size:
      searchParams.get("size") === null
        ? undefined
        : Number(searchParams.get("size")),
  });
  if (!query.success) return validationResponse(query.error);

  try {
    return NextResponse.json(
      await getCollection(collectionId.data, query.data.page, query.data.size),
    );
  } catch (error) {
    return routeErrorResponse(error);
  }
}

export async function PATCH(
  request: Request,
  context: CollectionRouteContext,
): Promise<NextResponse> {
  const collectionId = await validatedCollectionId(context);
  if (!collectionId.success) return validationResponse(collectionId.error);
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return invalidJsonResponse();
  }
  const parsed = updateCollectionRequestSchema.safeParse(body);
  if (!parsed.success) return validationResponse(parsed.error);

  try {
    return NextResponse.json(
      await updateCollection(collectionId.data, parsed.data),
    );
  } catch (error) {
    return routeErrorResponse(error);
  }
}

export async function DELETE(
  _request: Request,
  context: CollectionRouteContext,
): Promise<NextResponse> {
  const collectionId = await validatedCollectionId(context);
  if (!collectionId.success) return validationResponse(collectionId.error);
  try {
    await deleteCollection(collectionId.data);
    return new NextResponse(null, { status: 204 });
  } catch (error) {
    return routeErrorResponse(error);
  }
}
