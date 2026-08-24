import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it, vi } from "vitest";

import type { OpenScholarOfflinePackRuntime } from "@/pwa/offline-pack-runtime";
import { offlineCollectionPackFixture } from "@/test/fixtures";

function contractRuntime(): {
  openDatabase: ReturnType<typeof vi.fn>;
  runtime: OpenScholarOfflinePackRuntime;
} {
  const openDatabase = vi.fn(() => {
    throw new Error("validated payload reached storage");
  });
  const sandbox = {
    OpenScholarOfflinePack: undefined as OpenScholarOfflinePackRuntime | undefined,
    atob,
    btoa,
    crypto: { subtle: {} },
    indexedDB: { open: openDatabase },
    location: { origin: "https://openscholar.test" },
  };
  const source = readFileSync(
    resolve(process.cwd(), "public/offline-pack.js"),
    "utf8",
  );
  new Function("globalThis", source)(sandbox);
  if (sandbox.OpenScholarOfflinePack === undefined) {
    throw new Error("Offline runtime did not install");
  }
  return { openDatabase, runtime: sandbox.OpenScholarOfflinePack };
}

describe("offline-pack public payload contract", () => {
  it("accepts the OpenAPI collection, title, and tag boundaries", async () => {
    const { openDatabase, runtime } = contractRuntime();
    const payload = offlineCollectionPackFixture();
    payload.collection.name = "n".repeat(120);
    payload.collection.description = "d".repeat(1_000);
    payload.papers[0]!.title = "t";
    payload.papers[0]!.tags = Array.from({ length: 10 }, (_, index) =>
      String(index).padEnd(40, "x"),
    );

    await expect(
      runtime.save(payload, "a separate passphrase", "local-v1"),
    ).rejects.toThrow("validated payload reached storage");
    expect(openDatabase).toHaveBeenCalledOnce();
  });

  it.each([
    ["a blank collection name", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.collection.name = "";
    }],
    ["a 121-character collection name", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.collection.name = "n".repeat(121);
    }],
    ["a 1001-character description", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.collection.description = "d".repeat(1_001);
    }],
    ["a blank paper title", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.papers[0]!.title = "";
    }],
    ["eleven tags", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.papers[0]!.tags = Array.from(
        { length: 11 },
        (_, index) => `tag-${index}`,
      );
    }],
    ["a blank tag", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.papers[0]!.tags = [""];
    }],
    ["a 41-character tag", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.papers[0]!.tags = ["t".repeat(41)];
    }],
    ["duplicate tags", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.papers[0]!.tags = ["methods", "methods"];
    }],
  ])("rejects %s before opening private storage", async (_label, mutate) => {
    const { openDatabase, runtime } = contractRuntime();
    const payload = offlineCollectionPackFixture();
    mutate(payload);

    await expect(
      runtime.save(payload, "a separate passphrase", "local-v1"),
    ).rejects.toMatchObject({ name: "OfflinePackDataError" });
    expect(openDatabase).not.toHaveBeenCalled();
  });
});
