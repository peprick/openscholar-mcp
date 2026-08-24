import { afterEach, describe, expect, it, vi } from "vitest";

import { GET } from "@/app/api/papers/resolve/route";
import {
  BackendApiError,
  resolvePaperIdentifier,
} from "@/shared/api/server";

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

  return {
    BackendApiError,
    resolvePaperIdentifier: vi.fn(),
  };
});

const resolution = {
  paperId: "11111111-1111-4111-8111-111111111111",
  identifierType: "DOI" as const,
  normalizedValue: "10.1000/example",
};

afterEach(() => {
  vi.mocked(resolvePaperIdentifier).mockReset();
});

describe("paper identifier BFF route", () => {
  it("trims the identifier, forwards it, and prevents private caching", async () => {
    vi.mocked(resolvePaperIdentifier).mockResolvedValue(resolution);

    const response = await GET(
      new Request(
        "https://research.test/api/papers/resolve?identifier=%20doi%3A10.1000%2Fexample%20",
      ),
    );

    expect(resolvePaperIdentifier).toHaveBeenCalledWith("doi:10.1000/example");
    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe("no-store");
    await expect(response.json()).resolves.toEqual(resolution);
  });

  it("rejects a missing identifier before contacting the backend", async () => {
    const response = await GET(
      new Request("https://research.test/api/papers/resolve"),
    );

    expect(resolvePaperIdentifier).not.toHaveBeenCalled();
    expect(response.status).toBe(400);
    expect(response.headers.get("content-type")).toContain(
      "application/problem+json",
    );
    expect(response.headers.get("cache-control")).toBe("no-store");
    await expect(response.json()).resolves.toMatchObject({
      code: "VALIDATION_FAILED",
      violations: [
        {
          field: "identifier",
          message: "Enter a DOI, arXiv ID, or OpenAlex work ID.",
        },
      ],
    });
  });

  it("preserves a safe not-found problem without making it cacheable", async () => {
    vi.mocked(resolvePaperIdentifier).mockRejectedValue(
      new BackendApiError(404, {
        title: "Paper not found",
        status: 404,
        detail: "No visible paper matched this identifier.",
        code: "PAPER_IDENTIFIER_NOT_FOUND",
      }),
    );

    const response = await GET(
      new Request(
        "https://research.test/api/papers/resolve?identifier=W2741809807",
      ),
    );

    expect(response.status).toBe(404);
    expect(response.headers.get("cache-control")).toBe("no-store");
    await expect(response.json()).resolves.toMatchObject({
      code: "PAPER_IDENTIFIER_NOT_FOUND",
    });
  });
});
