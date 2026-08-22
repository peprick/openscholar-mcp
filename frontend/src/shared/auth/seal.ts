import "server-only";

import {
  createCipheriv,
  createDecipheriv,
  createHash,
  randomBytes,
  timingSafeEqual,
} from "node:crypto";

const SEAL_VERSION = "v1";
const IV_BYTES = 12;
const TAG_BYTES = 16;
const MAX_SEALED_VALUE_BYTES = 3_800;

function encode(value: Buffer): string {
  return value.toString("base64url");
}

function decode(value: string): Buffer {
  if (!/^[A-Za-z0-9_-]*$/u.test(value)) {
    throw new Error("Invalid base64url value.");
  }
  const decoded = Buffer.from(value, "base64url");
  if (encode(decoded) !== value) {
    throw new Error("Non-canonical base64url value.");
  }
  return decoded;
}

export function randomUrlSafeValue(bytes = 32): string {
  return encode(randomBytes(bytes));
}

export function pkceChallenge(verifier: string): string {
  return encode(createHash("sha256").update(verifier, "ascii").digest());
}

export function constantTimeEqual(left: string, right: string): boolean {
  const leftDigest = createHash("sha256").update(left).digest();
  const rightDigest = createHash("sha256").update(right).digest();
  return timingSafeEqual(leftDigest, rightDigest);
}

export function sealJson(
  value: unknown,
  key: Buffer,
  purpose: string,
): string {
  const iv = randomBytes(IV_BYTES);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(`${SEAL_VERSION}:${purpose}`, "utf8"));
  const encrypted = Buffer.concat([
    cipher.update(JSON.stringify(value), "utf8"),
    cipher.final(),
  ]);
  const sealed = [SEAL_VERSION, encode(iv), encode(encrypted), encode(cipher.getAuthTag())].join(".");
  if (Buffer.byteLength(sealed, "utf8") > MAX_SEALED_VALUE_BYTES) {
    throw new Error("The encrypted authentication session is too large for a secure cookie.");
  }
  return sealed;
}

export function unsealJson(
  sealed: string,
  key: Buffer,
  purpose: string,
): unknown | null {
  if (Buffer.byteLength(sealed, "utf8") > MAX_SEALED_VALUE_BYTES) return null;
  const parts = sealed.split(".");
  if (parts.length !== 4 || parts[0] !== SEAL_VERSION) return null;

  try {
    const iv = decode(parts[1] ?? "");
    const encrypted = decode(parts[2] ?? "");
    const tag = decode(parts[3] ?? "");
    if (iv.length !== IV_BYTES || tag.length !== TAG_BYTES) return null;
    const decipher = createDecipheriv("aes-256-gcm", key, iv);
    decipher.setAAD(Buffer.from(`${SEAL_VERSION}:${purpose}`, "utf8"));
    decipher.setAuthTag(tag);
    const plaintext = Buffer.concat([
      decipher.update(encrypted),
      decipher.final(),
    ]).toString("utf8");
    return JSON.parse(plaintext) as unknown;
  } catch {
    return null;
  }
}
