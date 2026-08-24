import { afterEach, describe, expect, it, vi } from "vitest";

import { GET } from "@/app/api/collections/[collectionId]/offline-pack/route";
import {
  BackendApiError,
  getOfflineCollectionPack,
} from "@/shared/api/server";
import { offlineCollectionPackFixture, testIds } from "@/test/fixtures";

vi.mock("server-only", () => ({}));
vi.mock("@/shared/api/server", () => {
  class BackendApiError extends Error {
    constructor(
      readonly status: number,
      readonly problem: {
        title: string;
        status: number;
        detail: string;
        code: string;
      },
      readonly retryAfter: string | null = null,
    ) {
      super(problem.detail);
    }
  }
  return { BackendApiError, getOfflineCollectionPack: vi.fn() };
});

function context(collectionId: string) {
  return { params: Promise.resolve({ collectionId }) };
}

afterEach(() => {
  vi.mocked(getOfflineCollectionPack).mockReset();
});

describe("offline collection-pack BFF route", () => {
  it("rejects an invalid collection id before contacting the backend", async () => {
    const response = await GET(
      new Request("https://research.test/api/collections/not-a-uuid/offline-pack"),
      context("not-a-uuid"),
    );

    expect(getOfflineCollectionPack).not.toHaveBeenCalled();
    expect(response.status).toBe(400);
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(response.headers.get("x-content-type-options")).toBe("nosniff");
  });

  it("returns the exact backend pack through a no-store response", async () => {
    const payload = offlineCollectionPackFixture();
    vi.mocked(getOfflineCollectionPack).mockResolvedValue(payload);

    const response = await GET(
      new Request(
        `https://research.test/api/collections/${testIds.collection}/offline-pack`,
      ),
      context(testIds.collection),
    );

    expect(getOfflineCollectionPack).toHaveBeenCalledWith(testIds.collection);
    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(response.headers.get("x-content-type-options")).toBe("nosniff");
    await expect(response.json()).resolves.toEqual(payload);
  });

  it("preserves the bounded backend overflow problem without caching it", async () => {
    vi.mocked(getOfflineCollectionPack).mockRejectedValue(
      new BackendApiError(422, {
        title: "Offline pack too large",
        status: 422,
        detail: "The collection exceeds the offline metadata limit.",
        code: "OFFLINE_PACK_TOO_LARGE",
      }),
    );

    const response = await GET(
      new Request(
        `https://research.test/api/collections/${testIds.collection}/offline-pack`,
      ),
      context(testIds.collection),
    );

    expect(response.status).toBe(422);
    expect(response.headers.get("cache-control")).toBe("no-store");
    await expect(response.json()).resolves.toMatchObject({
      code: "OFFLINE_PACK_TOO_LARGE",
    });
  });
});
