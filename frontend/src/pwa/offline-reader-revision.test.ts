import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

import { EXPECTED_OFFLINE_READER_REVISION } from "@/pwa/offline-pack-loader";

function source(pathname: string): string {
  return readFileSync(resolve(process.cwd(), pathname), "utf8");
}

function oneRevision(value: string, pattern: RegExp, label: string): string {
  const matches = [...value.matchAll(pattern)];
  expect(matches, `${label} must have exactly one revision literal`).toHaveLength(
    1,
  );
  const revision = matches[0]?.[1];
  if (revision === undefined) throw new Error(`${label} revision is missing.`);
  return revision;
}

describe("offline reader revision contract", () => {
  it("keeps all five production literals coherent", () => {
    const worker = oneRevision(
      source("public/sw.js"),
      /const OFFLINE_READER_REVISION = "([A-Za-z0-9._-]+)";/gu,
      "service worker",
    );
    const shell = oneRevision(
      source("public/offline.html"),
      /data-offline-reader-revision="([A-Za-z0-9._-]+)"/gu,
      "offline shell",
    );
    const runtime = oneRevision(
      source("public/offline-pack.js"),
      /const READER_REVISION = "([A-Za-z0-9._-]+)";/gu,
      "offline runtime",
    );
    const declaration = oneRevision(
      source("src/pwa/offline-pack-runtime.d.ts"),
      /readerRevision: "([A-Za-z0-9._-]+)";/gu,
      "runtime declaration",
    );

    expect([
      worker,
      shell,
      runtime,
      declaration,
      EXPECTED_OFFLINE_READER_REVISION,
    ]).toEqual(Array.from({ length: 5 }, () => worker));
  });
});
