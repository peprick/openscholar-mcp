import { afterEach, describe, expect, it, vi } from "vitest";

import type { OpenScholarOfflinePackRuntime } from "@/pwa/offline-pack-runtime";

function runtimeFixture(): OpenScholarOfflinePackRuntime {
  return {
    constants: {
      formatVersion: 1,
      readerRevision: "2026-08-24-r3",
      cryptoProfile: "pbkdf2-sha256-aes256gcm-v1",
      workFactor: 600000,
      maximumPapers: 500,
      maximumPlaintextBytes: 1048576,
      minimumPassphraseCharacters: 12,
      maximumPassphraseCharacters: 128,
      maximumPassphraseBytes: 256,
    },
    prepareSave: vi.fn(),
    save: vi.fn(),
    beginDeletion: vi.fn(),
    completeDeletion: vi.fn(),
    inspect: vi.fn(),
    unlock: vi.fn(),
    purge: vi.fn(),
    purgeMismatched: vi.fn(),
    lock: vi.fn(),
    subscribe: vi.fn(() => () => undefined),
  };
}

afterEach(() => {
  globalThis.OpenScholarOfflinePack = undefined;
  document
    .querySelectorAll("script[data-openscholar-offline-pack]")
    .forEach((script) => script.remove());
  vi.resetModules();
});

describe("offline-pack runtime loader", () => {
  it("reuses an already installed audited runtime", async () => {
    const runtime = runtimeFixture();
    globalThis.OpenScholarOfflinePack = runtime;
    const { loadOfflinePackRuntime } = await import(
      "@/pwa/offline-pack-loader"
    );

    await expect(loadOfflinePackRuntime()).resolves.toBe(runtime);
    expect(
      document.querySelector("script[data-openscholar-offline-pack]"),
    ).toBeNull();
  });

  it("fails closed instead of trusting a runtime from an older reader revision", async () => {
    const current = runtimeFixture();
    globalThis.OpenScholarOfflinePack = {
      ...current,
      constants: {
        ...current.constants,
        readerRevision: "older-reader",
      },
    } as unknown as OpenScholarOfflinePackRuntime;
    const { loadOfflinePackRuntime } = await import(
      "@/pwa/offline-pack-loader"
    );
    const attempt = loadOfflinePackRuntime();
    const script = document.querySelector<HTMLScriptElement>(
      "script[data-openscholar-offline-pack]",
    );
    script?.dispatchEvent(new Event("load"));

    await expect(attempt).rejects.toThrow("did not initialize");
    expect(script?.isConnected).toBe(false);
  });

  it("coalesces concurrent requests into one same-origin script", async () => {
    const { loadOfflinePackRuntime } = await import(
      "@/pwa/offline-pack-loader"
    );
    const first = loadOfflinePackRuntime();
    const second = loadOfflinePackRuntime();
    const script = document.querySelector<HTMLScriptElement>(
      "script[data-openscholar-offline-pack]",
    );

    expect(script?.getAttribute("src")).toBe("/offline-pack.js");
    expect(first).toBe(second);
    const runtime = runtimeFixture();
    globalThis.OpenScholarOfflinePack = runtime;
    script?.dispatchEvent(new Event("load"));

    await expect(first).resolves.toBe(runtime);
    await expect(second).resolves.toBe(runtime);
  });

  it("removes a failed script so a later attempt can retry", async () => {
    const { loadOfflinePackRuntime } = await import(
      "@/pwa/offline-pack-loader"
    );
    const attempt = loadOfflinePackRuntime();
    const script = document.querySelector<HTMLScriptElement>(
      "script[data-openscholar-offline-pack]",
    );
    script?.dispatchEvent(new Event("error"));

    await expect(attempt).rejects.toThrow("could not be loaded");
    expect(script?.isConnected).toBe(false);
    void loadOfflinePackRuntime().catch(() => undefined);
    expect(
      document.querySelectorAll("script[data-openscholar-offline-pack]"),
    ).toHaveLength(1);
  });

  it("removes a loaded marker when initialization is missing, then settles a retry", async () => {
    const { loadOfflinePackRuntime } = await import(
      "@/pwa/offline-pack-loader"
    );
    const failedAttempt = loadOfflinePackRuntime();
    const failedScript = document.querySelector<HTMLScriptElement>(
      "script[data-openscholar-offline-pack]",
    );
    failedScript?.dispatchEvent(new Event("load"));

    await expect(failedAttempt).rejects.toThrow("did not initialize");
    expect(failedScript?.isConnected).toBe(false);

    const retry = loadOfflinePackRuntime();
    const retryScript = document.querySelector<HTMLScriptElement>(
      "script[data-openscholar-offline-pack]",
    );
    expect(retryScript).not.toBe(failedScript);
    const runtime = runtimeFixture();
    globalThis.OpenScholarOfflinePack = runtime;
    retryScript?.dispatchEvent(new Event("load"));

    await expect(retry).resolves.toBe(runtime);
  });
});
