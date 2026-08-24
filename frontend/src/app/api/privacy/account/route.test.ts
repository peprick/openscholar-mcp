import { afterEach, describe, expect, it, vi } from "vitest";

import { DELETE } from "@/app/api/privacy/account/route";
import { BackendApiError, deletePersonalData } from "@/shared/api/server";
import { AUTH_SESSION_COOKIE } from "@/shared/auth/session";

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
    deletePersonalData: vi.fn(),
  };
});

function request(body: string): Request {
  return new Request("https://research.test/api/privacy/account", {
    method: "DELETE",
    headers: { "content-type": "application/json" },
    body,
  });
}

function configureHostedAuth(): void {
  vi.stubEnv("OPENSCHOLAR_AUTH_MODE", "oidc");
  vi.stubEnv(
    "OPENSCHOLAR_AUTH_SESSION_SECRET",
    Buffer.alloc(32, 17).toString("base64"),
  );
  vi.stubEnv("OPENSCHOLAR_OIDC_ISSUER", "https://identity.test");
  vi.stubEnv(
    "OPENSCHOLAR_OIDC_AUTHORIZATION_ENDPOINT",
    "https://identity.test/authorize",
  );
  vi.stubEnv(
    "OPENSCHOLAR_OIDC_TOKEN_ENDPOINT",
    "https://identity.test/token",
  );
  vi.stubEnv("OPENSCHOLAR_OIDC_JWKS_URI", "https://identity.test/keys");
  vi.stubEnv("OPENSCHOLAR_OIDC_CLIENT_ID", "openscholar-web");
  vi.stubEnv(
    "OPENSCHOLAR_OIDC_REDIRECT_URI",
    "https://research.test/api/auth/callback",
  );
  vi.stubEnv(
    "OPENSCHOLAR_OIDC_POST_LOGOUT_REDIRECT_URI",
    "https://research.test/",
  );
}

afterEach(() => {
  vi.mocked(deletePersonalData).mockReset();
  vi.unstubAllEnvs();
});

describe("personal-data deletion BFF route", () => {
  it.each([
    ["missing confirmation", {}],
    ["null confirmation", { confirmation: null }],
    ["wrong case", { confirmation: "delete_my_data" }],
    ["trailing whitespace", { confirmation: "DELETE_MY_DATA " }],
    [
      "additional property",
      { confirmation: "DELETE_MY_DATA", unexpected: true },
    ],
  ])("rejects %s before contacting the backend", async (_label, body) => {
    const response = await DELETE(request(JSON.stringify(body)));

    expect(deletePersonalData).not.toHaveBeenCalled();
    expect(response.status).toBe(400);
    expect(response.headers.get("cache-control")).toBe("no-store");
    await expect(response.json()).resolves.toMatchObject({
      code: "VALIDATION_FAILED",
    });
  });

  it("rejects malformed JSON before contacting the backend", async () => {
    const response = await DELETE(request('{"confirmation":'));

    expect(deletePersonalData).not.toHaveBeenCalled();
    expect(response.status).toBe(400);
    expect(response.headers.get("cache-control")).toBe("no-store");
    await expect(response.json()).resolves.toMatchObject({
      code: "INVALID_REQUEST",
    });
  });

  it("deletes local-mode data without disabling the local application", async () => {
    vi.mocked(deletePersonalData).mockResolvedValue();

    const response = await DELETE(
      request(JSON.stringify({ confirmation: "DELETE_MY_DATA" })),
    );

    expect(deletePersonalData).toHaveBeenCalledWith({
      confirmation: "DELETE_MY_DATA",
    });
    expect(response.status).toBe(204);
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(response.headers.get("clear-site-data")).toBe('"storage"');
    expect(response.headers.get("set-cookie")).toBeNull();
  });

  it("clears the hosted app session only after successful deletion", async () => {
    configureHostedAuth();
    vi.mocked(deletePersonalData).mockResolvedValue();

    const response = await DELETE(
      request(JSON.stringify({ confirmation: "DELETE_MY_DATA" })),
    );

    expect(response.status).toBe(204);
    expect(response.headers.get("clear-site-data")).toBe('"storage"');
    expect(response.headers.get("set-cookie")).toContain(
      `${AUTH_SESSION_COOKIE}=`,
    );
    expect(response.headers.get("set-cookie")).toContain("Max-Age=0");
    expect(response.headers.get("set-cookie")).toContain("HttpOnly");
    expect(response.headers.get("set-cookie")).toContain("Secure");
  });

  it.each([
    [401, "AUTHENTICATION_REQUIRED", null],
    [403, "ACCESS_DENIED", null],
    [503, "BACKEND_UNAVAILABLE", "20"],
  ] as const)(
    "preserves a safe %i backend problem and does not clear the session",
    async (status, code, retryAfter) => {
      configureHostedAuth();
      vi.mocked(deletePersonalData).mockRejectedValue(
        new BackendApiError(
          status,
          {
            title: "Deletion unavailable",
            status,
            detail: "The account data could not be deleted yet.",
            code,
          },
          retryAfter,
        ),
      );

      const response = await DELETE(
        request(JSON.stringify({ confirmation: "DELETE_MY_DATA" })),
      );

      expect(response.status).toBe(status);
      expect(response.headers.get("cache-control")).toBe("no-store");
      expect(response.headers.get("retry-after")).toBe(retryAfter);
      expect(response.headers.get("clear-site-data")).toBeNull();
      expect(response.headers.get("set-cookie")).toBeNull();
      await expect(response.json()).resolves.toMatchObject({ code });
    },
  );
});
