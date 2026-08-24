import { afterEach, describe, expect, it, vi } from "vitest";

import { cookies } from "next/headers";

import { POST } from "@/app/api/auth/logout/route";
import { getAuthConfig } from "@/shared/auth/config";
import {
  AUTH_SESSION_COOKIE,
  AUTH_TRANSACTION_COOKIE,
  readAuthSession,
} from "@/shared/auth/session";

vi.mock("server-only", () => ({}));
vi.mock("next/headers", () => ({ cookies: vi.fn() }));
vi.mock("@/shared/auth/config", () => ({ getAuthConfig: vi.fn() }));
vi.mock("@/shared/auth/session", async (importOriginal) => {
  const original = await importOriginal<
    typeof import("@/shared/auth/session")
  >();
  return { ...original, readAuthSession: vi.fn() };
});

const config = {
  mode: "oidc",
  issuer: "https://identity.test",
  authorizationEndpoint: new URL("https://identity.test/authorize"),
  tokenEndpoint: new URL("https://identity.test/token"),
  jwksUri: new URL("https://identity.test/keys"),
  endSessionEndpoint: null,
  clientId: "openscholar-web",
  clientSecret: null,
  clientAuthMethod: "none",
  redirectUri: new URL("https://research.test/api/auth/callback"),
  postLogoutRedirectUri: new URL("https://research.test/signed-out"),
  scopes: ["openid"],
  idTokenAlgorithms: ["RS256"],
  sessionKey: Buffer.alloc(32, 9),
  sessionMaxAgeSeconds: 3_600,
} as const;

function request(origin = "https://research.test"): Request {
  return new Request("https://research.test/api/auth/logout", {
    method: "POST",
    headers: { origin },
  });
}

afterEach(() => {
  vi.mocked(cookies).mockReset();
  vi.mocked(getAuthConfig).mockReset();
  vi.mocked(readAuthSession).mockReset();
});

describe("hosted logout route", () => {
  it("clears browser storage and both application cookies on success", async () => {
    vi.mocked(getAuthConfig).mockReturnValue(config);
    vi.mocked(cookies).mockResolvedValue({
      get: vi.fn().mockReturnValue({ value: "sealed-session" }),
    } as never);
    vi.mocked(readAuthSession).mockReturnValue(null);

    const response = await POST(request());

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe(
      "https://research.test/signed-out",
    );
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(response.headers.get("clear-site-data")).toBe('"storage"');
    expect(response.headers.get("referrer-policy")).toBe("no-referrer");
    const cookiesHeader = response.headers.get("set-cookie") ?? "";
    expect(cookiesHeader).toContain(`${AUTH_SESSION_COOKIE}=`);
    expect(cookiesHeader).toContain(`${AUTH_TRANSACTION_COOKIE}=`);
    expect(cookiesHeader).toContain("Max-Age=0");
  });

  it("does not clear storage or cookies for a rejected cross-origin request", async () => {
    vi.mocked(getAuthConfig).mockReturnValue(config);

    const response = await POST(request("https://attacker.test"));

    expect(response.status).toBe(403);
    expect(response.headers.get("clear-site-data")).toBeNull();
    expect(response.headers.get("set-cookie")).toBeNull();
    expect(cookies).not.toHaveBeenCalled();
  });
});
