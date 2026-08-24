import { webcrypto } from "node:crypto";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it, vi } from "vitest";

import type { OpenScholarOfflinePackRuntime } from "@/pwa/offline-pack-runtime";
import {
  offlineCollectionPackFixture,
  testIds,
} from "@/test/fixtures";

type RequestHandler = () => void;

type FakeRequest = {
  error: Error | null;
  onerror: RequestHandler | null;
  onsuccess: RequestHandler | null;
  result: unknown;
};

type SharedDatabase = {
  activePutFailure: DOMException | null;
  created: boolean;
  messages: string[];
  randomByte: number;
  records: Map<string, Record<string, unknown>>;
};

class FakeTransaction {
  error: Error | null = null;
  onabort: RequestHandler | null = null;
  oncomplete: RequestHandler | null = null;
  onerror: RequestHandler | null = null;
  private aborted = false;
  private completion: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly shared: SharedDatabase) {}

  objectStore() {
    return {
      delete: (key: string) =>
        this.request(() => {
          this.shared.records.delete(key);
          return undefined;
        }),
      get: (key: string) => this.request(() => this.shared.records.get(key)),
      put: (value: Record<string, unknown>) =>
        this.request(() => {
          if (value.slot === "active" && this.shared.activePutFailure !== null) {
            const error = this.shared.activePutFailure;
            this.shared.activePutFailure = null;
            throw error;
          }
          this.shared.records.set(String(value.slot), value);
          return value.slot;
        }),
    };
  }

  private request(action: () => unknown): FakeRequest {
    if (this.completion !== null) clearTimeout(this.completion);
    const request: FakeRequest = {
      error: null,
      onerror: null,
      onsuccess: null,
      result: undefined,
    };
    queueMicrotask(() => {
      if (this.aborted) return;
      try {
        request.result = action();
        request.onsuccess?.();
        this.completion = setTimeout(() => this.oncomplete?.(), 0);
      } catch (error) {
        const failure =
          error instanceof Error || error instanceof DOMException
            ? error
            : new Error("IDB failure");
        request.error = failure;
        this.error = failure;
        request.onerror?.();
        this.aborted = true;
        this.onabort?.();
      }
    });
    return request;
  }
}

class FakeDatabase {
  onversionchange: RequestHandler | null = null;
  readonly objectStoreNames;

  constructor(private readonly shared: SharedDatabase) {
    this.objectStoreNames = {
      contains: () => this.shared.created,
    };
  }

  close(): void {}

  createObjectStore(): void {
    this.shared.created = true;
  }

  transaction(): FakeTransaction {
    return new FakeTransaction(this.shared);
  }
}

function fakeIndexedDb(shared: SharedDatabase) {
  return {
    open: () => {
      const request = {
        error: null,
        onblocked: null as RequestHandler | null,
        onerror: null as RequestHandler | null,
        onsuccess: null as RequestHandler | null,
        onupgradeneeded: null as RequestHandler | null,
        result: new FakeDatabase(shared),
      };
      queueMicrotask(() => {
        if (!shared.created) request.onupgradeneeded?.();
        request.onsuccess?.();
      });
      return request;
    },
  };
}

function fakeCrypto(
  shared: SharedDatabase,
  encrypt: (plaintext: ArrayBuffer | ArrayBufferView) => Promise<ArrayBuffer>,
) {
  return {
    getRandomValues: (bytes: Uint8Array) => {
      shared.randomByte = (shared.randomByte % 250) + 1;
      bytes.fill(shared.randomByte);
      return bytes;
    },
    subtle: {
      decrypt: vi.fn(),
      deriveKey: vi.fn().mockResolvedValue({}),
      digest: (_algorithm: string, value: BufferSource) =>
        webcrypto.subtle.digest("SHA-256", value),
      encrypt: vi.fn(
        async (
          _algorithm: unknown,
          _key: unknown,
          plaintext: ArrayBuffer | ArrayBufferView,
        ) => encrypt(plaintext),
      ),
      importKey: vi.fn().mockResolvedValue({}),
    },
  };
}

function sharedDatabase(): SharedDatabase {
  return {
    activePutFailure: null,
    created: false,
    messages: [],
    randomByte: 0,
    records: new Map(),
  };
}

function immediateEncryption(
  plaintext: ArrayBuffer | ArrayBufferView,
): Promise<ArrayBuffer> {
  return Promise.resolve(new Uint8Array(plaintext.byteLength + 16).buffer);
}

function runtimeFor(
  shared: SharedDatabase,
  encrypt = immediateEncryption,
): OpenScholarOfflinePackRuntime {
  class QuietBroadcastChannel {
    addEventListener(): void {}
    postMessage(message: { type: string }): void {
      shared.messages.push(message.type);
    }
  }
  const sandbox = {
    AbortController,
    BroadcastChannel: QuietBroadcastChannel,
    DOMException,
    OpenScholarOfflinePack: undefined as OpenScholarOfflinePackRuntime | undefined,
    atob,
    btoa,
    clearTimeout,
    crypto: fakeCrypto(shared, encrypt),
    indexedDB: fakeIndexedDb(shared),
    location: { origin: "https://openscholar.test" },
    setTimeout,
  };
  const source = readFileSync(
    resolve(process.cwd(), "public/offline-pack.js"),
    "utf8",
  );
  new Function("globalThis", source)(sandbox);
  if (sandbox.OpenScholarOfflinePack === undefined) {
    throw new Error("Offline runtime did not install");
  }
  return sandbox.OpenScholarOfflinePack;
}

describe("offline-pack durable lifecycle fence", () => {
  it("prevents a delayed save in another context from resurrecting a committed purge", async () => {
    const shared = sharedDatabase();
    let finishEncryption: (value: ArrayBuffer) => void = () => undefined;
    const encryptStarted = vi.fn();
    const firstContext = runtimeFor(
      shared,
      (plaintext) =>
        new Promise((resolveEncryption) => {
          encryptStarted();
          finishEncryption = resolveEncryption;
          expect(plaintext.byteLength).toBeGreaterThan(0);
        }),
    );
    const secondContext = runtimeFor(shared);
    const pendingSave = firstContext.save(
      offlineCollectionPackFixture(),
      "a separate passphrase",
      "local-v1",
    );
    await vi.waitFor(() => expect(encryptStarted).toHaveBeenCalledOnce());

    await secondContext.purge();
    finishEncryption(new Uint8Array(128).buffer);

    await expect(pendingSave).rejects.toThrow("cancelled by a privacy action");
    await expect(firstContext.inspect()).resolves.toBeNull();
  });

  it("does not broadcast false PURGE for a same-owner or other-collection no-op", async () => {
    const shared = sharedDatabase();
    const runtime = runtimeFor(shared);
    await runtime.save(
      offlineCollectionPackFixture(),
      "a separate passphrase",
      "local-v1",
    );
    shared.messages.length = 0;

    await expect(runtime.purgeMismatched("local-v1")).resolves.toBe(false);
    expect(shared.messages).toEqual([]);
    await expect(runtime.purgeCollection(testIds.relatedPaper)).resolves.toBe(false);
    expect(shared.messages).toEqual(["LOCK"]);
    await expect(runtime.inspect()).resolves.not.toBeNull();
  });

  it("repairs malformed active/control records through an explicit full purge", async () => {
    const shared = sharedDatabase();
    shared.created = true;
    shared.records.set("active", { slot: "active", damaged: true });
    shared.records.set("control", { slot: "control", damaged: true });
    const runtime = runtimeFor(shared);

    await expect(runtime.inspect()).rejects.toThrow("unavailable or damaged");
    await expect(runtime.purge()).resolves.toBe(true);
    await expect(runtime.inspect()).resolves.toBeNull();
    await expect(
      runtime.save(
        offlineCollectionPackFixture(),
        "a separate passphrase",
        "local-v1",
      ),
    ).resolves.toMatchObject({ ownerScope: "local-v1" });
  });

  it("keeps the prior active envelope when the final atomic put hits quota", async () => {
    const shared = sharedDatabase();
    const runtime = runtimeFor(shared);
    const first = await runtime.save(
      offlineCollectionPackFixture(),
      "a separate passphrase",
      "local-v1",
    );
    shared.activePutFailure = new DOMException("quota", "QuotaExceededError");
    const replacement = offlineCollectionPackFixture({
      collection: {
        collectionId: testIds.relatedPaper,
        name: "Related work",
        description: null,
      },
    });

    await expect(
      runtime.save(replacement, "a separate passphrase", "local-v1"),
    ).rejects.toThrow("previous copy was kept");
    await expect(runtime.inspect()).resolves.toEqual(first);
  });
});
