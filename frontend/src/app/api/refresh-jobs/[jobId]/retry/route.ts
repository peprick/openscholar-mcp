import { NextResponse } from "next/server";
import { z } from "zod";

import {
  routeErrorResponse,
  validationResponse,
} from "@/shared/api/route-errors";
import { retryResearchRefreshJob } from "@/shared/api/server";

type RetryRefreshJobRouteContext = {
  params: Promise<{ jobId: string }>;
};

const jobIdSchema = z.string().uuid();

export async function POST(
  _request: Request,
  context: RetryRefreshJobRouteContext,
): Promise<NextResponse> {
  const jobId = jobIdSchema.safeParse((await context.params).jobId);
  if (!jobId.success) return validationResponse(jobId.error);

  try {
    return NextResponse.json(await retryResearchRefreshJob(jobId.data), {
      status: 202,
    });
  } catch (error) {
    return routeErrorResponse(error);
  }
}
