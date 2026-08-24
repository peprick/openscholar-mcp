import { afterEach, describe, expect, it, vi } from "vitest";

import { GET } from "@/app/api/auth/status/route";
import { getAuthConfig } from "@/shared/auth/config";
import { offlineStorageScope } from "@/shared/auth/offline-storage-scope";
import { getRequestAuthSession } from "@/shared/auth/session";

vi.mock("server-only", () => ({}));
vi.mock("@/shared/auth/config", () => ({ getAuthConfig: vi.fn() }));
vi.mock("@/shared/auth/offline-storage-scope", () => ({
  offlineStorageScope: vi.fn(),
}));
vi.mock("@/shared/auth/session", () => ({
  getRequestAuthSession: vi.fn(),
}));

afterEach(() => {
  vi.mocked(getAuthConfig).mockReset();
  vi.mocked(getRequestAuthSession).mockReset();
  vi.mocked(offlineStorageScope).mockReset();
  vi.restoreAllMocks();
});

describe("authentication status route", () => {
  it("returns the fixed local offline-storage scope without claiming authentication", async () => {
    const config = { mode: "local" } as const;
    vi.mocked(getAuthConfig).mockReturnValue(config);
    vi.mocked(getRequestAuthSession).mockResolvedValue(null);
    vi.mocked(offlineStorageScope).mockReturnValue("local-v1");

    const response = await GET();

    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe("no-store");
    await expect(response.json()).resolves.toEqual({
      mode: "local",
      authenticated: false,
      storageScope: "local-v1",
    });
    expect(offlineStorageScope).toHaveBeenCalledWith(config, null);
  });

  it("keeps the opaque owner scope when a hosted access token has expired", async () => {
    vi.spyOn(Date, "now").mockReturnValue(2_000_000);
    const config = {
      mode: "oidc",
      issuer: "https://identity.test",
    } as never;
    const session = {
      subject: "private-subject",
      accessExpiresAt: 1_999,
    } as never;
    vi.mocked(getAuthConfig).mockReturnValue(config);
    vi.mocked(getRequestAuthSession).mockResolvedValue(session);
    vi.mocked(offlineStorageScope).mockReturnValue("oidc-v1.opaque");

    const response = await GET();

    await expect(response.json()).resolves.toEqual({
      mode: "oidc",
      authenticated: false,
      storageScope: "oidc-v1.opaque",
    });
    expect(offlineStorageScope).toHaveBeenCalledWith(config, session);
  });

  it("returns a no-store configuration problem without exposing a scope", async () => {
    vi.mocked(getAuthConfig).mockImplementation(() => {
      throw new Error("secret configuration detail");
    });

    const response = await GET();

    expect(response.status).toBe(503);
    expect(response.headers.get("cache-control")).toBe("no-store");
    await expect(response.json()).resolves.toMatchObject({
      code: "AUTH_CONFIGURATION_ERROR",
    });
  });
});
