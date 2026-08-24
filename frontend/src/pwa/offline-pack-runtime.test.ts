import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it, vi } from "vitest";

import { offlineCollectionPackFixture } from "@/test/fixtures";

function runtimeSource(): string {
  return readFileSync(resolve(process.cwd(), "public/offline-pack.js"), "utf8");
}

function ownerReconciler(
  globalObject: Record<string, unknown>,
  runtime: Record<string, unknown>,
): () => Promise<{ kind: string; scope?: string }> {
  const source = runtimeSource();
  const start = source.indexOf("async function reconcileOwner()");
  const end = source.indexOf("\n\n    const initialOwnerCheck", start);
  if (start < 0 || end < 0) throw new Error("Owner reconciler not found");
  const factory = new Function(
    "global",
    "runtime",
    "isRecord",
    "hasExactKeys",
    `return (${source.slice(start, end)});`,
  );
  return factory(
    globalObject,
    runtime,
    (value: unknown) =>
      value !== null && typeof value === "object" && !Array.isArray(value),
    (value: unknown, expected: string[]) => {
      if (value === null || typeof value !== "object" || Array.isArray(value)) {
        return false;
      }
      const record = value as Record<string, unknown>;
      return (
        Object.keys(record).length === expected.length &&
        expected.every((key) => Object.hasOwn(record, key))
      );
    },
  ) as () => Promise<{ kind: string; scope?: string }>;
}

function readerGlobal(fetchMock: unknown): Record<string, unknown> {
  return {
    AbortController,
    DOMException,
    clearTimeout,
    fetch: fetchMock,
    setTimeout,
  };
}

function installExtractedReader(
  globalObject: Record<string, unknown>,
  runtime: Record<string, unknown>,
): void {
  const source = runtimeSource();
  const start = source.indexOf("function installReader()");
  const end = source.indexOf("\n\n  if (global.document !== undefined)", start);
  if (start < 0 || end < 0) throw new Error("Reader installer not found");
  const factory = new Function(
    "global",
    "runtime",
    "isRecord",
    "hasExactKeys",
    "renderPaper",
    "READER_REVISION",
    `return (${source.slice(start, end)});`,
  );
  const isRecord = (value: unknown) =>
    value !== null && typeof value === "object" && !Array.isArray(value);
  const hasExactKeys = (value: unknown, expected: string[]) => {
    if (!isRecord(value)) return false;
    const record = value as Record<string, unknown>;
    return (
      Object.keys(record).length === expected.length &&
      expected.every((key) => Object.hasOwn(record, key))
    );
  };
  const install = factory(
    globalObject,
    runtime,
    isRecord,
    hasExactKeys,
    vi.fn(),
    "2026-08-24-r2",
  ) as () => void;
  install();
}

function readerMarkup(): void {
  document.documentElement.dataset.offlineReaderRevision = "2026-08-24-r2";
  document.body.innerHTML = `
    <button disabled id="offline-pack-open" type="button">Open</button>
    <button id="offline-pack-remove-any" type="button">Remove data</button>
    <form hidden id="offline-pack-unlock-form">
      <input id="offline-pack-passphrase" />
      <button type="submit">Unlock</button>
      <button id="offline-pack-remove-locked" type="button">Remove</button>
    </form>
    <div hidden id="offline-pack-reader">
      <h2 id="offline-pack-title" tabindex="-1"></h2>
      <p id="offline-pack-description"></p>
      <p id="offline-pack-generated"></p>
      <input id="offline-pack-filter" />
      <ol id="offline-pack-papers"></ol>
      <button id="offline-pack-lock" type="button">Lock</button>
      <button id="offline-pack-purge" type="button">Purge</button>
    </div>
    <p id="offline-pack-status"></p>
  `;
}

describe("audited offline-pack runtime", () => {
  it("publishes the fixed v1 crypto/storage profile", () => {
    const sandbox = {
      location: { origin: "https://openscholar.test" },
      btoa,
      atob,
      crypto,
      OpenScholarOfflinePack: undefined,
    };
    const evaluate = new Function("globalThis", runtimeSource());
    evaluate(sandbox);

    expect(sandbox.OpenScholarOfflinePack).toMatchObject({
      constants: {
        formatVersion: 1,
        readerRevision: "2026-08-24-r2",
        cryptoProfile: "pbkdf2-sha256-aes256gcm-v1",
        workFactor: 600000,
        maximumPapers: 500,
        maximumPlaintextBytes: 1048576,
        minimumPassphraseCharacters: 12,
        maximumPassphraseCharacters: 128,
        maximumPassphraseBytes: 256,
      },
    });
    expect(Object.isFrozen(sandbox.OpenScholarOfflinePack)).toBe(true);
  });

  it("keeps plaintext out of browser persistence and uses text-only rendering", () => {
    const source = runtimeSource();

    expect(source).toContain('const DATABASE_NAME = "openscholar-private-offline-v1"');
    expect(source).toContain('const STORE_NAME = "packs"');
    expect(source).toContain('const ACTIVE_KEY = "active"');
    expect(source).toContain('const CONTROL_KEY = "control"');
    expect(source).toContain('iterations: WORK_FACTOR');
    expect(source).toContain('{ name: "AES-GCM", length: KEY_BITS }');
    expect(source).toContain("tagLength: TAG_BITS");
    expect(source).toContain("false,");
    expect(source).not.toMatch(/localStorage|sessionStorage|caches\.|\.persist\s*\(/u);
    expect(source).not.toMatch(/innerHTML|outerHTML|insertAdjacentHTML/u);
    expect(source).toContain(".textContent =");
    expect(source).toContain("store.put(envelope)");
    expect(source).toContain("await Promise.all([");
    expect(source).toContain("await done.catch(() => undefined)");
    expect(source).toContain("control.lifecycleEpoch !== lifecycleEpoch");
  });

  it("bounds and validates the clear envelope before starting its KDF", () => {
    const source = runtimeSource();
    const unlock = source.slice(
      source.indexOf("async function unlock"),
      source.indexOf("async function purge"),
    );

    expect(unlock.indexOf("validateEnvelope(envelope)")).toBeGreaterThan(-1);
    expect(unlock.indexOf("deriveKey(passphrase")).toBeGreaterThan(
      unlock.indexOf("validateEnvelope(envelope)"),
    );
    expect(source).toContain("const MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + TAG_BYTES");
    expect(source).toContain("digest.fill(0)");
    expect(source).toContain("plaintext.fill(0)");
    const save = source.slice(
      source.indexOf("async function save"),
      source.indexOf("async function inspect"),
    );
    expect(save.indexOf("captureSaveFence(scope)")).toBeLessThan(
      save.indexOf("deriveKey(passphrase"),
    );
  });

  it("verifies a reachable owner and fails closed when mismatch purge fails", async () => {
    const purgeMismatched = vi.fn().mockResolvedValue(false);
    const verified = ownerReconciler(
      readerGlobal(
        vi.fn().mockResolvedValue(
          Response.json({
            mode: "oidc",
            authenticated: true,
            storageScope: "oidc-v1.new-owner",
          }),
        ),
      ),
      { purgeMismatched },
    );

    await expect(verified()).resolves.toEqual({
      kind: "verified",
      scope: "oidc-v1.new-owner",
    });
    expect(purgeMismatched).toHaveBeenCalledWith("oidc-v1.new-owner");

    const rejectedPurge = ownerReconciler(
      readerGlobal(
        vi.fn().mockResolvedValue(
          Response.json({
            mode: "oidc",
            authenticated: true,
            storageScope: "oidc-v1.new-owner",
          }),
        ),
      ),
      { purgeMismatched: vi.fn().mockRejectedValue(new Error("IDB blocked")) },
    );
    await expect(rejectedPurge()).resolves.toEqual({
      kind: "locked",
      scope: undefined,
    });
  });

  it("retains an envelope only for a network-offline check, not invalid reachable auth", async () => {
    const offline = ownerReconciler(
      readerGlobal(vi.fn().mockRejectedValue(new TypeError("offline"))),
      {},
    );
    await expect(offline()).resolves.toEqual({
      kind: "offline",
      scope: undefined,
    });

    const malformed = ownerReconciler(
      readerGlobal(
        vi.fn().mockResolvedValue(
          new Response("not json", {
            headers: { "content-type": "application/json" },
          }),
        ),
      ),
      {},
    );
    await expect(malformed()).resolves.toEqual({
      kind: "locked",
      scope: undefined,
    });

    const expanded = ownerReconciler(
      readerGlobal(
        vi.fn().mockResolvedValue(
          Response.json({
            mode: "local",
            authenticated: false,
            storageScope: "local-v1",
            subject: "must-not-be-exposed",
          }),
        ),
      ),
      { purgeMismatched: vi.fn() },
    );
    await expect(expanded()).resolves.toEqual({
      kind: "locked",
      scope: undefined,
    });
  });

  it("keeps the owner check timeout active through a stalled response body", async () => {
    vi.useFakeTimers();
    try {
      const fetchMock = vi.fn(
        async (_input: string, init: { signal: AbortSignal }) => ({
          ok: true,
          json: () =>
            new Promise((_resolve, reject) => {
              init.signal.addEventListener(
                "abort",
                () => reject(new DOMException("aborted", "AbortError")),
                { once: true },
              );
            }),
        }),
      );
      const reconcile = ownerReconciler(readerGlobal(fetchMock), {});
      const result = reconcile();

      await vi.advanceTimersByTimeAsync(3000);

      await expect(result).resolves.toEqual({
        kind: "locked",
        scope: undefined,
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it("purges and remains locked for a reachable signed-out hosted session", async () => {
    const purge = vi.fn().mockResolvedValue(false);
    const reconcile = ownerReconciler(
      readerGlobal(
        vi.fn().mockResolvedValue(
          Response.json({
            mode: "oidc",
            authenticated: false,
            storageScope: null,
          }),
        ),
      ),
      { purge },
    );

    await expect(reconcile()).resolves.toEqual({
      kind: "locked",
      scope: undefined,
    });
    expect(purge).toHaveBeenCalledOnce();
  });

  it("rechecks ownership on open and unlock, then binds unlock to that scope", () => {
    const source = runtimeSource();
    const reader = source.slice(
      source.indexOf("function installReader"),
      source.indexOf("if (global.document !== undefined)"),
    );

    expect(reader.match(/await reconcileOwner\(\)/gu)).toHaveLength(2);
    expect(reader).toContain("runtime.unlock(secret, owner.scope)");
  });

  it("does not render a delayed unlock after visibility invalidates the attempt", async () => {
    readerMarkup();
    let completeUnlock: (payload: unknown) => void = () => undefined;
    const unlock = vi.fn(
      () =>
        new Promise((resolve) => {
          completeUnlock = resolve;
        }),
    );
    const runtime = {
      constants: { readerRevision: "2026-08-24-r2" },
      inspect: vi.fn().mockResolvedValue({ collectionDigest: "opaque" }),
      lock: vi.fn(),
      purge: vi.fn().mockResolvedValue(false),
      purgeMismatched: vi.fn().mockResolvedValue(false),
      subscribe: vi.fn(() => () => undefined),
      unlock,
    };
    installExtractedReader(
      {
        AbortController,
        DOMException,
        HTMLInputElement,
        addEventListener: vi.fn(),
        clearTimeout,
        confirm: vi.fn(),
        document,
        fetch: vi.fn().mockImplementation(() =>
          Promise.resolve(
            Response.json({
              mode: "local",
              authenticated: false,
              storageScope: "local-v1",
            }),
          ),
        ),
        setTimeout,
      },
      runtime,
    );
    const open = document.querySelector<HTMLButtonElement>("#offline-pack-open")!;
    await vi.waitFor(() => expect(open.disabled).toBe(false));
    open.click();
    const form = document.querySelector<HTMLFormElement>(
      "#offline-pack-unlock-form",
    )!;
    await vi.waitFor(() => expect(form.hidden).toBe(false));
    const passphrase = document.querySelector<HTMLInputElement>(
      "#offline-pack-passphrase",
    )!;
    passphrase.value = "a separate passphrase";
    form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect(unlock).toHaveBeenCalledOnce());

    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      value: "hidden",
    });
    document.dispatchEvent(new Event("visibilitychange"));
    completeUnlock(offlineCollectionPackFixture());
    await Promise.resolve();
    await Promise.resolve();

    expect(document.querySelector("#offline-pack-reader")).toHaveProperty(
      "hidden",
      true,
    );
    expect(document.querySelector("#offline-pack-title")).toHaveTextContent("");
    expect(passphrase.value).toBe("");
    Reflect.deleteProperty(document, "visibilityState");
    document.body.replaceChildren();
  });

  it("fails closed but keeps purge-only recovery when reader revisions are mixed", async () => {
    readerMarkup();
    const fetchMock = vi.fn();
    const runtime = {
      constants: { readerRevision: "older-reader" },
      lock: vi.fn(),
      purge: vi.fn().mockResolvedValue(true),
      unlock: vi.fn(),
    };
    installExtractedReader(
      {
        HTMLInputElement,
        confirm: vi.fn().mockReturnValue(true),
        document,
        fetch: fetchMock,
      },
      runtime,
    );

    expect(document.querySelector("#offline-pack-open")).toHaveProperty(
      "disabled",
      true,
    );
    expect(document.querySelector("#offline-pack-status")).toHaveTextContent(
      "needs an update",
    );
    document
      .querySelector<HTMLButtonElement>("#offline-pack-remove-any")!
      .click();
    await vi.waitFor(() => expect(runtime.purge).toHaveBeenCalledOnce());
    expect(runtime.lock).toHaveBeenCalledOnce();
    expect(runtime.unlock).not.toHaveBeenCalled();
    expect(document.querySelector("#offline-pack-status")).toHaveTextContent(
      "removed from this device",
    );
    expect(fetchMock).not.toHaveBeenCalled();
    document.body.replaceChildren();
  });

  it("returns keyboard focus to Open after local and cross-tab clears", async () => {
    readerMarkup();
    let onRuntimeEvent: (type: string) => void = () => undefined;
    const runtime = {
      constants: { readerRevision: "2026-08-24-r2" },
      inspect: vi.fn().mockResolvedValue({ collectionDigest: "opaque" }),
      lock: vi.fn(),
      purge: vi.fn().mockResolvedValue(false),
      purgeMismatched: vi.fn().mockResolvedValue(false),
      subscribe: vi.fn((listener: (type: string) => void) => {
        onRuntimeEvent = listener;
        return () => undefined;
      }),
      unlock: vi.fn().mockResolvedValue(offlineCollectionPackFixture()),
    };
    installExtractedReader(
      {
        AbortController,
        DOMException,
        HTMLInputElement,
        addEventListener: vi.fn(),
        clearTimeout,
        confirm: vi.fn(),
        document,
        fetch: vi.fn().mockImplementation(() =>
          Promise.resolve(
            Response.json({
              mode: "local",
              authenticated: false,
              storageScope: "local-v1",
            }),
          ),
        ),
        setTimeout,
      },
      runtime,
    );
    const open = document.querySelector<HTMLButtonElement>("#offline-pack-open")!;
    const form = document.querySelector<HTMLFormElement>(
      "#offline-pack-unlock-form",
    )!;
    const passphrase = document.querySelector<HTMLInputElement>(
      "#offline-pack-passphrase",
    )!;
    await vi.waitFor(() => expect(open.disabled).toBe(false));

    async function unlockReader(): Promise<void> {
      open.click();
      await vi.waitFor(() => expect(form.hidden).toBe(false));
      passphrase.value = "a separate passphrase";
      form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
      await vi.waitFor(() =>
        expect(document.querySelector("#offline-pack-reader")).toHaveProperty(
          "hidden",
          false,
        ),
      );
    }

    await unlockReader();
    document.querySelector<HTMLButtonElement>("#offline-pack-lock")!.click();
    expect(document.activeElement).toBe(open);

    await unlockReader();
    onRuntimeEvent("PURGE");
    expect(document.activeElement).toBe(open);
    document.body.replaceChildren();
  });
});
