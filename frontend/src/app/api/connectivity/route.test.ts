import { afterEach, describe, expect, it, vi } from "vitest";

import { GET } from "@/app/api/connectivity/route";
import { getSystemStatus } from "@/shared/api/server";

vi.mock("server-only", () => ({}));
vi.mock("@/shared/api/server", () => ({ getSystemStatus: vi.fn() }));

afterEach(() => {
  vi.mocked(getSystemStatus).mockReset();
});

describe("connectivity route", () => {
  it("reports the same-origin application stack as available without metrics", async () => {
    vi.mocked(getSystemStatus).mockResolvedValue({
      service: "openscholar-backend",
      status: "UP",
      timestamp: "2026-08-24T00:00:00Z",
    });

    const response = await GET();

    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe("no-store");
    await expect(response.json()).resolves.toEqual({ available: true });
  });

  it("fails closed when the application stack cannot be reached", async () => {
    vi.mocked(getSystemStatus).mockRejectedValue(new Error("unreachable"));

    const response = await GET();

    expect(response.status).toBe(503);
    expect(response.headers.get("cache-control")).toBe("no-store");
    await expect(response.json()).resolves.toEqual({ available: false });
  });
});
