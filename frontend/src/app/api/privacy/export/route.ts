import { routeErrorResponse } from "@/shared/api/route-errors";
import {
  BackendContractError,
  exportPersonalData,
} from "@/shared/api/server";

const EXPORT_CONTENT_TYPE = "application/json";
const EXPORT_CONTENT_DISPOSITION =
  'attachment; filename="openscholar-personal-data.json"';

function noStore(response: Response): Response {
  response.headers.set("cache-control", "no-store");
  return response;
}

export async function GET(): Promise<Response> {
  try {
    const backendResponse = await exportPersonalData();
    const contentLength = backendResponse.headers.get("content-length");
    if (contentLength === null) {
      await backendResponse.body?.cancel().catch(() => undefined);
      throw new BackendContractError("personal data export");
    }
    return new Response(backendResponse.body, {
      status: 200,
      headers: {
        "cache-control": "no-store, no-transform",
        "content-length": contentLength,
        "content-disposition": EXPORT_CONTENT_DISPOSITION,
        "content-type": EXPORT_CONTENT_TYPE,
        "x-content-type-options": "nosniff",
      },
    });
  } catch (error) {
    return noStore(routeErrorResponse(error));
  }
}
