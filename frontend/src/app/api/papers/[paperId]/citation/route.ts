import { routeErrorResponse } from "@/shared/api/route-errors";
import { fetchBackend } from "@/shared/api/server";

type CitationRouteContext = {
  params: Promise<{ paperId: string }>;
};

export async function GET(
  request: Request,
  context: CitationRouteContext,
): Promise<Response> {
  try {
    const { paperId } = await context.params;
    const requestedUrl = new URL(request.url);
    const format = requestedUrl.searchParams.get("format") ?? "bibtex";
    const query = new URLSearchParams({ format });
    const response = await fetchBackend(
      `/api/v1/papers/${encodeURIComponent(paperId)}/citation?${query}`,
    );
    const headers = new Headers();
    for (const name of [
      "content-type",
      "content-disposition",
      "x-content-type-options",
      "retry-after",
    ]) {
      const value = response.headers.get(name);
      if (value !== null) {
        headers.set(name, value);
      }
    }
    return new Response(response.body, {
      status: response.status,
      headers,
    });
  } catch (error) {
    return routeErrorResponse(error);
  }
}
