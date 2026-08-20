import { NextResponse } from "next/server";

import { routeErrorResponse } from "@/shared/api/route-errors";
import { getNextSearchPage } from "@/shared/api/server";

type NextSearchPageRouteContext = {
  params: Promise<{ searchId: string }>;
};

export async function POST(
  _request: Request,
  context: NextSearchPageRouteContext,
): Promise<NextResponse> {
  try {
    const { searchId } = await context.params;
    const result = await getNextSearchPage(searchId);
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
