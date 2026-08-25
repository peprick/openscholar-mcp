import { afterEach, describe, expect, it, vi } from "vitest";

import { GET } from "@/app/api/privacy/export/route";
import {
  BackendApiError,
  exportPersonalData,
} from "@/shared/api/server";

vi.mock("server-only", () => ({}));
vi.mock("@/shared/api/server", () => {
  class BackendContractError extends Error {
    constructor(resource: string) {
      super(`The backend returned an unexpected ${resource} response.`);
    }
  }

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
    BackendContractError,
    exportPersonalData: vi.fn(),
  };
});

afterEach(() => {
  vi.mocked(exportPersonalData).mockReset();
});

describe("personal-data export BFF route", () => {
  it("streams the JSON body with fixed private download headers", async () => {
    const payload = JSON.stringify({ userId: "private-user", searches: [] });
    const backendResponse = new Response(payload, {
      status: 200,
      headers: {
        "cache-control": "public, max-age=3600",
        "content-length": String(Buffer.byteLength(payload, "utf8")),
        "content-disposition": 'attachment; filename="unsafe.html"',
        "content-type": "application/json; charset=utf-8",
        "set-cookie": "backend-secret=leaked",
        "x-backend-internal": "private",
      },
    });
    const blobSpy = vi.spyOn(backendResponse, "blob");
    const jsonSpy = vi.spyOn(backendResponse, "json");
    const textSpy = vi.spyOn(backendResponse, "text");
    const arrayBufferSpy = vi.spyOn(backendResponse, "arrayBuffer");
    vi.mocked(exportPersonalData).mockResolvedValue(backendResponse);

    const response = await GET();

    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe(
      "no-store, no-transform",
    );
    expect(response.headers.get("content-length")).toBe(
      String(Buffer.byteLength(payload, "utf8")),
    );
    expect(response.headers.get("content-type")).toBe("application/json");
    expect(response.headers.get("content-disposition")).toBe(
      'attachment; filename="openscholar-personal-data.json"',
    );
    expect(response.headers.get("x-content-type-options")).toBe("nosniff");
    expect(response.headers.get("set-cookie")).toBeNull();
    expect(response.headers.get("x-backend-internal")).toBeNull();
    expect(blobSpy).not.toHaveBeenCalled();
    expect(jsonSpy).not.toHaveBeenCalled();
    expect(textSpy).not.toHaveBeenCalled();
    expect(arrayBufferSpy).not.toHaveBeenCalled();
    await expect(response.text()).resolves.toBe(payload);
  });

  it("propagates download cancellation to the backend stream", async () => {
    const cancel = vi.fn();
    const backendResponse = new Response(
      new ReadableStream<Uint8Array>({
        pull(controller) {
          controller.enqueue(new TextEncoder().encode('{"userId":"private'));
        },
        cancel,
      }),
      {
        status: 200,
        headers: {
          "content-length": String(128 * 1_048_576),
          "content-type": "application/json",
        },
      },
    );
    vi.mocked(exportPersonalData).mockResolvedValue(backendResponse);

    const response = await GET();
    await response.body?.cancel("browser stopped download");

    expect(cancel).toHaveBeenCalledWith("browser stopped download");
  });

  it("propagates a midstream backend failure without appending replacement JSON", async () => {
    const failure = new Error("backend stream failed");
    const backendResponse = new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(new TextEncoder().encode('{"userId":"private"'));
          controller.error(failure);
        },
      }),
      {
        status: 200,
        headers: { "content-length": "64", "content-type": "application/json" },
      },
    );
    vi.mocked(exportPersonalData).mockResolvedValue(backendResponse);

    const response = await GET();

    await expect(response.text()).rejects.toBe(failure);
  });

  it.each([
    [401, "AUTHENTICATION_REQUIRED", null],
    [403, "ACCESS_DENIED", null],
    [429, "PRIVACY_EXPORT_BUSY", "10"],
    [503, "BACKEND_UNAVAILABLE", "30"],
  ] as const)(
    "forwards a safe %i backend problem without making it cacheable",
    async (status, code, retryAfter) => {
      vi.mocked(exportPersonalData).mockRejectedValue(
        new BackendApiError(
          status,
          {
            title: "Export unavailable",
            status,
            detail: "The export could not be returned.",
            code,
          },
          retryAfter,
        ),
      );

      const response = await GET();

      expect(response.status).toBe(status);
      expect(response.headers.get("cache-control")).toBe("no-store");
      expect(response.headers.get("content-type")).toContain(
        "application/problem+json",
      );
      expect(response.headers.get("retry-after")).toBe(retryAfter);
      await expect(response.json()).resolves.toMatchObject({
        code,
        detail: "The export could not be returned.",
      });
    },
  );

  it("turns unexpected gateway failures into the generic safe problem", async () => {
    vi.mocked(exportPersonalData).mockRejectedValue(
      new Error("internal backend address and secret"),
    );

    const response = await GET();
    const responseBody = response.clone();

    expect(response.status).toBe(502);
    expect(response.headers.get("cache-control")).toBe("no-store");
    await expect(response.json()).resolves.toMatchObject({
      code: "FRONTEND_GATEWAY_FAILURE",
    });
    expect(await responseBody.text()).not.toContain("internal backend");
  });
});
