import { NextResponse } from "next/server";

import {
  collectionListQuerySchema,
  createCollectionRequestSchema,
} from "@/shared/api/library-schemas";
import {
  invalidJsonResponse,
  routeErrorResponse,
  validationResponse,
} from "@/shared/api/route-errors";
import { createCollection, getCollections } from "@/shared/api/server";

export async function GET(request: Request): Promise<NextResponse> {
  const searchParams = new URL(request.url).searchParams;
  const parsed = collectionListQuerySchema.safeParse({
    page:
      searchParams.get("page") === null
        ? undefined
        : Number(searchParams.get("page")),
    size:
      searchParams.get("size") === null
        ? undefined
        : Number(searchParams.get("size")),
  });
  if (!parsed.success) return validationResponse(parsed.error);

  try {
    return NextResponse.json(
      await getCollections(parsed.data.page, parsed.data.size),
    );
  } catch (error) {
    return routeErrorResponse(error);
  }
}

export async function POST(request: Request): Promise<NextResponse> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return invalidJsonResponse();
  }

  const parsed = createCollectionRequestSchema.safeParse(body);
  if (!parsed.success) return validationResponse(parsed.error);

  try {
    const result = await createCollection(parsed.data);
    const headers = new Headers();
    if (result.location !== null) headers.set("location", result.location);
    return NextResponse.json(result.data, { status: result.status, headers });
  } catch (error) {
    return routeErrorResponse(error);
  }
}
