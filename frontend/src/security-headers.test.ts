import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

import nextConfig, {
  contentSecurityPolicy,
  PRODUCTION_CONTENT_SECURITY_POLICY,
  securityHeaders,
} from "../next.config";

function directives(policy: string): Map<string, string[]> {
  const parsed = new Map<string, string[]>();
  for (const rawDirective of policy.split(";")) {
    const values = rawDirective.trim().split(/\s+/u);
    const name = values.shift();
    if (name === undefined || name === "" || parsed.has(name)) {
      throw new Error(`Invalid or duplicate CSP directive: ${name ?? ""}`);
    }
    parsed.set(name, values);
  }
  return parsed;
}

describe("security headers", () => {
  it("builds a conservative production CSP for Next.js, PDF.js, and same-origin application calls", () => {
    const policy = PRODUCTION_CONTENT_SECURITY_POLICY;
    const parsed = directives(policy);

    expect(parsed.get("default-src")).toEqual(["'self'"]);
    expect(parsed.get("base-uri")).toEqual(["'none'"]);
    expect(parsed.get("child-src")).toEqual(["'self'"]);
    expect(parsed.get("connect-src")).toEqual(["'self'", "https:"]);
    expect(parsed.get("font-src")).toEqual(["'self'", "data:"]);
    expect(parsed.get("form-action")).toEqual(["'self'"]);
    expect(parsed.get("frame-ancestors")).toEqual(["'none'"]);
    expect(parsed.get("frame-src")).toEqual(["'none'"]);
    expect(parsed.get("img-src")).toEqual(["'self'", "data:", "blob:"]);
    expect(parsed.get("object-src")).toEqual(["'none'"]);
    expect(parsed.get("script-src")).toEqual([
      "'self'",
      "'unsafe-inline'",
      "'wasm-unsafe-eval'",
    ]);
    expect(parsed.get("script-src-attr")).toEqual(["'none'"]);
    expect(parsed.get("style-src")).toEqual([
      "'self'",
      "'unsafe-inline'",
    ]);
    expect(parsed.get("worker-src")).toEqual(["'self'"]);
    expect(parsed.get("upgrade-insecure-requests")).toEqual([]);
    expect(parsed.has("navigate-to")).toBe(false);
    expect(policy).not.toContain("'unsafe-eval'");
    expect(policy).not.toContain("*");
  });

  it("keeps development HMR allowances out of the production policy", () => {
    const development = directives(contentSecurityPolicy("development"));
    const production = directives(contentSecurityPolicy("production"));

    expect(development.get("connect-src")).toEqual([
      "'self'",
      "https:",
      "ws:",
      "wss:",
    ]);
    expect(development.get("script-src")).toContain("'unsafe-eval'");
    expect(development.has("upgrade-insecure-requests")).toBe(false);
    expect(production.get("connect-src")).toEqual(["'self'", "https:"]);
    expect(production.get("script-src")).not.toContain("'unsafe-eval'");
  });

  it("applies the policy through Next.js and keeps the Caddy edge policy identical", async () => {
    if (typeof nextConfig.headers !== "function") {
      throw new Error("Next.js headers configuration is missing.");
    }
    const rules = await nextConfig.headers();
    expect(rules).toEqual([
      {
        source: "/:path*",
        headers: securityHeaders(process.env.NODE_ENV),
      },
    ]);

    const caddyfile = readFileSync(
      resolve(process.cwd(), "../deploy/Caddyfile"),
      "utf8",
    );
    expect(caddyfile.match(/Content-Security-Policy/gu)).toHaveLength(1);
    expect(caddyfile).toContain(
      `Content-Security-Policy "${PRODUCTION_CONTENT_SECURITY_POLICY}"`,
    );
    expect(caddyfile).toContain('X-Frame-Options "DENY"');
    expect(caddyfile).toContain(
      '?Referrer-Policy "strict-origin-when-cross-origin"',
    );
    expect(caddyfile).toContain(
      'Permissions-Policy "camera=(), geolocation=(), microphone=(), payment=()"',
    );

    const pdfWorker = readFileSync(
      resolve(
        process.cwd(),
        "node_modules/pdfjs-dist/legacy/build/pdf.worker.min.mjs",
      ),
      "utf8",
    );
    expect(pdfWorker).toMatch(/WebAssembly\.(?:instantiate|Module)/u);
    expect(PRODUCTION_CONTENT_SECURITY_POLICY).toContain(
      "'wasm-unsafe-eval'",
    );
  });
});
