import { NextResponse } from "next/server";

import { getSystemStatus } from "@/shared/api/server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const NO_STORE_HEADERS = { "cache-control": "no-store" } as const;

export async function GET(): Promise<NextResponse> {
  try {
    const status = await getSystemStatus();
    if (status.status !== "UP") throw new Error("Backend is not ready");
    return NextResponse.json(
      { available: true },
      { headers: NO_STORE_HEADERS },
    );
  } catch {
    return NextResponse.json(
      { available: false },
      { headers: NO_STORE_HEADERS, status: 503 },
    );
  }
}
