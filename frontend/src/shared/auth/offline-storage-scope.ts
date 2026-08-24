import "server-only";

import { createHmac } from "node:crypto";

import type { AuthConfig } from "@/shared/auth/config";
import type { AuthSession } from "@/shared/auth/session";

const SCOPE_PURPOSE = "openscholar-offline-owner-v1";
const HOSTED_SCOPE_BYTES = 24;

export const LOCAL_OFFLINE_STORAGE_SCOPE = "local-v1";

/**
 * Returns an opaque, stable browser-storage partition marker for the current
 * OpenScholar owner. It is deliberately not an authorization credential.
 */
export function offlineStorageScope(
  config: AuthConfig,
  session: AuthSession | null,
): string | null {
  if (config.mode === "local") return LOCAL_OFFLINE_STORAGE_SCOPE;
  if (session === null) return null;

  const ownerTuple = JSON.stringify([
    SCOPE_PURPOSE,
    config.issuer,
    session.subject,
  ]);
  const digest = createHmac("sha256", config.sessionKey)
    .update(ownerTuple, "utf8")
    .digest()
    .subarray(0, HOSTED_SCOPE_BYTES)
    .toString("base64url");
  return `oidc-v1.${digest}`;
}
