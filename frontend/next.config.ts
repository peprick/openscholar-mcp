import type { NextConfig } from "next";

export function contentSecurityPolicy(
  environment: string | undefined,
): string {
  const development = environment !== "production";
  return [
    "default-src 'self'",
    "base-uri 'none'",
    "child-src 'self'",
    `connect-src 'self' https:${development ? " ws: wss:" : ""}`,
    "font-src 'self' data:",
    "form-action 'self'",
    "frame-ancestors 'none'",
    "frame-src 'none'",
    "img-src 'self' data: blob:",
    "manifest-src 'self'",
    "media-src 'none'",
    "object-src 'none'",
    `script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'${development ? " 'unsafe-eval'" : ""}`,
    "script-src-attr 'none'",
    "style-src 'self' 'unsafe-inline'",
    "worker-src 'self'",
    ...(development ? [] : ["upgrade-insecure-requests"]),
  ].join("; ");
}

export const PRODUCTION_CONTENT_SECURITY_POLICY =
  contentSecurityPolicy("production");

export function securityHeaders(
  environment: string | undefined,
): { key: string; value: string }[] {
  return [
    {
      key: "Content-Security-Policy",
      value: contentSecurityPolicy(environment),
    },
    { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
    { key: "X-Content-Type-Options", value: "nosniff" },
    { key: "X-Frame-Options", value: "DENY" },
    {
      key: "Permissions-Policy",
      value: "camera=(), microphone=(), geolocation=(), payment=()",
    },
  ];
}

const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  reactStrictMode: true,
  typedRoutes: true,
  async headers() {
    return [
      {
        source: "/:path*",
        headers: securityHeaders(process.env.NODE_ENV),
      },
    ];
  },
};

export default nextConfig;
