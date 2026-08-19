import { batchCitationExportRequestSchema } from "@/shared/api/library-schemas";
import {
  invalidJsonResponse,
  routeErrorResponse,
  validationResponse,
} from "@/shared/api/route-errors";
import { fetchBackend } from "@/shared/api/server";

export async function POST(request: Request): Promise<Response> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return invalidJsonResponse();
  }
  const parsed = batchCitationExportRequestSchema.safeParse(body);
  if (!parsed.success) return validationResponse(parsed.error);

  try {
    const response = await fetchBackend("/api/v1/citations/export", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(parsed.data),
    });
    const headers = new Headers();
    for (const name of [
      "content-type",
      "content-disposition",
      "x-content-type-options",
      "retry-after",
    ]) {
      const value = response.headers.get(name);
      if (value !== null) headers.set(name, value);
    }
    return new Response(response.body, { status: response.status, headers });
  } catch (error) {
    return routeErrorResponse(error);
  }
}
