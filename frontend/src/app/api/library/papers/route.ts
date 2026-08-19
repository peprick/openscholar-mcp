import { NextResponse } from "next/server";

import { savedLibraryQuerySchema } from "@/shared/api/library-schemas";
import { routeErrorResponse, validationResponse } from "@/shared/api/route-errors";
import { searchSavedLibrary } from "@/shared/api/server";

export async function GET(request: Request): Promise<NextResponse> {
  const searchParams = new URL(request.url).searchParams;
  const parsed = savedLibraryQuerySchema.safeParse({
    q: searchParams.get("q") ?? undefined,
    collectionId: searchParams.get("collectionId") ?? undefined,
    readingStatus: searchParams.get("readingStatus") ?? undefined,
    tag: searchParams.get("tag") ?? undefined,
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
    return NextResponse.json(await searchSavedLibrary(parsed.data));
  } catch (error) {
    return routeErrorResponse(error);
  }
}
