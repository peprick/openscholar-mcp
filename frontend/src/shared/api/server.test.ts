import { afterEach, describe, expect, it, vi } from "vitest";

import {
  BackendContractError,
  getNextSearchPage,
  getRelatedPapers,
} from "@/shared/api/server";
import {
  relatedPapersResponseFixture,
  searchResponseFixture,
  testIds,
} from "@/test/fixtures";

vi.mock("server-only", () => ({}));

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe("getRelatedPapers", () => {
  it("requests the bounded related-paper endpoint and validates its payload", async () => {
    vi.stubEnv("OPENSCHOLAR_API_BASE_URL", "http://backend.test:8080");
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(relatedPapersResponseFixture()), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(getRelatedPapers(testIds.paper, 5)).resolves.toEqual(
      relatedPapersResponseFixture(),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      new URL(
        `/api/v1/papers/${testIds.paper}/related?limit=5`,
        "http://backend.test:8080",
      ),
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it("rejects a validly shaped response belonging to another source paper", async () => {
    const mismatched = relatedPapersResponseFixture({
      sourcePaperId: "33333333-3333-4333-8333-333333333333",
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify(mismatched), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    );

    await expect(getRelatedPapers(testIds.paper)).rejects.toBeInstanceOf(
      BackendContractError,
    );
  });

  it("accepts the backend canonical UUID for an uppercase route UUID", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify(relatedPapersResponseFixture()), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    );

    await expect(
      getRelatedPapers(testIds.paper.toUpperCase()),
    ).resolves.toEqual(relatedPapersResponseFixture());
  });
});

describe("getNextSearchPage", () => {
  it("posts to the current snapshot continuation endpoint", async () => {
    vi.stubEnv("OPENSCHOLAR_API_BASE_URL", "http://backend.test:8080");
    const responseBody = {
      ...searchResponseFixture(),
      searchId: "14a97a49-9203-4871-9924-d8bf4b08dcb4",
    };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(responseBody), {
        status: 201,
        headers: {
          "content-type": "application/json",
          location: `/api/v1/searches/${responseBody.searchId}`,
        },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(getNextSearchPage(testIds.search)).resolves.toEqual({
      data: responseBody,
      status: 201,
      location: `/api/v1/searches/${responseBody.searchId}`,
    });
    expect(fetchMock).toHaveBeenCalledWith(
      new URL(
        `/api/v1/searches/${testIds.search}/next`,
        "http://backend.test:8080",
      ),
      expect.objectContaining({ cache: "no-store", method: "POST" }),
    );
  });
});
