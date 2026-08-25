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
  connections: Set<FakeDatabase>;
  created: boolean;
  messages: string[];
  pendingUpgrades: Set<() => void>;
  randomByte: number;
  records: Map<string, Record<string, unknown>>;
  version: number;
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
  private closed = false;

  constructor(private readonly shared: SharedDatabase) {
    this.objectStoreNames = {
      contains: () => this.shared.created,
    };
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.shared.connections.delete(this);
    for (const resume of this.shared.pendingUpgrades) {
      queueMicrotask(resume);
    }
  }

  isClosed(): boolean {
    return this.closed;
  }

  createObjectStore(): void {
    this.shared.created = true;
  }

  transaction(): FakeTransaction {
    if (this.closed) {
      throw new DOMException("database closed", "InvalidStateError");
    }
    return new FakeTransaction(this.shared);
  }
}

function fakeIndexedDb(shared: SharedDatabase) {
  return {
    open: (_name: string, requestedVersion = 1) => {
      const database = new FakeDatabase(shared);
      const request = {
        error: null as Error | null,
        onblocked: null as RequestHandler | null,
        onerror: null as RequestHandler | null,
        onsuccess: null as RequestHandler | null,
        onupgradeneeded: null as RequestHandler | null,
        result: database,
      };
      let completed = false;
      const finishOpen = () => {
        if (completed) return;
        if (requestedVersion < shared.version) {
          completed = true;
          shared.pendingUpgrades.delete(finishOpen);
          request.error = new DOMException(
            "The requested version is older than the existing database.",
            "VersionError",
          );
          request.onerror?.();
          return;
        }
        if (
          requestedVersion > shared.version &&
          [...shared.connections].some((connection) => !connection.isClosed())
        ) {
          return;
        }
        completed = true;
        shared.pendingUpgrades.delete(finishOpen);
        if (requestedVersion > shared.version) {
          shared.version = requestedVersion;
          request.onupgradeneeded?.();
        }
        shared.connections.add(database);
        request.onsuccess?.();
      };
      queueMicrotask(() => {
        if (requestedVersion < shared.version) {
          request.error = new DOMException(
            "The requested version is older than the existing database.",
            "VersionError",
          );
          request.onerror?.();
          return;
        }
        if (requestedVersion > shared.version) {
          for (const connection of [...shared.connections]) {
            connection.onversionchange?.();
          }
          if ([...shared.connections].some((connection) => !connection.isClosed())) {
            shared.pendingUpgrades.add(finishOpen);
            request.onblocked?.();
            return;
          }
        }
        finishOpen();
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
    connections: new Set(),
    created: false,
    messages: [],
    pendingUpgrades: new Set(),
    randomByte: 0,
    records: new Map(),
    version: 0,
  };
}

function openDatabaseVersion(
  shared: SharedDatabase,
  version: number,
): Promise<FakeDatabase> {
  return new Promise((resolveOpen, rejectOpen) => {
    const request = fakeIndexedDb(shared).open(
      "openscholar-private-offline-v1",
      version,
    );
    request.onsuccess = () => resolveOpen(request.result);
    request.onerror = () => rejectOpen(request.error);
    request.onblocked = () => rejectOpen(new Error("upgrade blocked"));
  });
}

async function legacyFreshSave(shared: SharedDatabase): Promise<void> {
  const database = await openDatabaseVersion(shared, 1);
  try {
    await new Promise<void>((resolveWrite, rejectWrite) => {
      const transaction = database.transaction();
      transaction.oncomplete = resolveWrite;
      transaction.onabort = () => rejectWrite(transaction.error);
      transaction.objectStore().put({ slot: "active", legacy: true });
    });
  } finally {
    database.close();
  }
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
  it("closes a late connection after a blocked database upgrade", async () => {
    const shared = sharedDatabase();
    shared.created = true;
    shared.version = 1;
    const blocker = await openDatabaseVersion(shared, 1);
    blocker.onversionchange = () => undefined;
    const runtime = runtimeFor(shared);

    await expect(runtime.inspect()).rejects.toMatchObject({
      name: "OfflinePackStorageBusyError",
    });
    expect(blocker.isClosed()).toBe(false);
    expect(shared.connections).toEqual(new Set([blocker]));

    blocker.close();

    await vi.waitFor(() => expect(shared.version).toBe(2));
    await vi.waitFor(() => expect(shared.connections.size).toBe(0));
    expect(shared.pendingUpgrades.size).toBe(0);
  });

  it("upgrades the database in place and prevents a legacy v1 client from fresh-saving", async () => {
    const shared = sharedDatabase();
    shared.created = true;
    shared.version = 1;
    shared.records.set("legacy-marker", {
      slot: "legacy-marker",
      preserved: true,
    });
    const legacyConnection = await openDatabaseVersion(shared, 1);
    legacyConnection.onversionchange = () => legacyConnection.close();
    const runtime = runtimeFor(shared);

    await runtime.beginDeletion(testIds.collection);

    expect(shared.version).toBe(2);
    expect(legacyConnection.isClosed()).toBe(true);
    expect(shared.records.get("legacy-marker")).toEqual({
      slot: "legacy-marker",
      preserved: true,
    });
    await expect(legacyFreshSave(shared)).rejects.toMatchObject({
      name: "VersionError",
    });
    expect(shared.records.has("active")).toBe(false);
    expect(shared.records.get("deletion")).toMatchObject({
      slot: "deletion",
      formatVersion: 1,
    });
  });

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
    const payload = offlineCollectionPackFixture();
    const fence = await firstContext.prepareSave(
      payload.collection.collectionId,
      "local-v1",
    );
    const pendingSave = firstContext.save(
      payload,
      "a separate passphrase",
      fence,
    );
    await vi.waitFor(() => expect(encryptStarted).toHaveBeenCalledOnce());

    await secondContext.purge();
    finishEncryption(new Uint8Array(128).buffer);

    await expect(pendingSave).rejects.toThrow("cancelled by a privacy action");
    await expect(firstContext.inspect()).resolves.toBeNull();
  });

  it("persists and reuses an exact targeted deletion barrier until exact completion", async () => {
    const shared = sharedDatabase();
    const runtime = runtimeFor(shared);
    const payload = offlineCollectionPackFixture();
    const saveFence = await runtime.prepareSave(
      payload.collection.collectionId,
      "local-v1",
    );
    const saved = await runtime.save(
      payload,
      "a separate passphrase",
      saveFence,
    );
    const previousEpoch = (
      shared.records.get("control") as { lifecycleEpoch: string }
    ).lifecycleEpoch;
    shared.messages.length = 0;

    const deletion = await runtime.beginDeletion(payload.collection.collectionId);

    expect(Object.isFrozen(deletion)).toBe(true);
    expect(deletion).toEqual({
      collectionDigest: saved.collectionDigest,
      deletionId: expect.stringMatching(/^[A-Za-z0-9_-]{22}$/u),
    });
    expect(shared.records.get("deletion")).toEqual({
      slot: "deletion",
      formatVersion: 1,
      ...deletion,
    });
    expect(shared.records.has("active")).toBe(false);
    expect(
      (shared.records.get("control") as { lifecycleEpoch: string })
        .lifecycleEpoch,
    ).not.toBe(previousEpoch);
    expect(shared.messages).toEqual(["PURGE"]);
    await expect(
      runtime.prepareSave(payload.collection.collectionId, "local-v1"),
    ).rejects.toMatchObject({ name: "OfflinePackDeletionPendingError" });

    await expect(
      runtime.beginDeletion(payload.collection.collectionId),
    ).resolves.toEqual(deletion);
    await expect(runtime.beginDeletion(testIds.relatedPaper)).rejects.toMatchObject({
      name: "OfflinePackDeletionPendingError",
    });
    await expect(runtime.purge()).resolves.toBe(false);
    expect(shared.records.get("deletion")).toEqual({
      slot: "deletion",
      formatVersion: 1,
      ...deletion,
    });

    await expect(
      runtime.completeDeletion({
        ...deletion,
        deletionId: "AAAAAAAAAAAAAAAAAAAAAA",
      }),
    ).resolves.toBe(false);
    expect(shared.records.has("deletion")).toBe(true);
    await expect(runtime.completeDeletion(deletion)).resolves.toBe(true);
    expect(shared.records.has("deletion")).toBe(false);
    await expect(runtime.completeDeletion(deletion)).resolves.toBe(false);
    await expect(
      runtime.prepareSave(payload.collection.collectionId, "local-v1"),
    ).resolves.toMatchObject({
      collectionDigest: saved.collectionDigest,
      ownerScope: "local-v1",
    });
  });

  it("uses a global deletion barrier to purge and block every collection", async () => {
    const shared = sharedDatabase();
    const runtime = runtimeFor(shared);
    const payload = offlineCollectionPackFixture();
    const fence = await runtime.prepareSave(
      payload.collection.collectionId,
      "local-v1",
    );
    await runtime.save(payload, "a separate passphrase", fence);

    const deletion = await runtime.beginDeletion();

    expect(deletion.collectionDigest).toBeNull();
    expect(shared.records.has("active")).toBe(false);
    await expect(
      runtime.prepareSave(payload.collection.collectionId, "local-v1"),
    ).rejects.toMatchObject({ name: "OfflinePackDeletionPendingError" });
    await expect(
      runtime.prepareSave(testIds.relatedPaper, "local-v1"),
    ).rejects.toMatchObject({ name: "OfflinePackDeletionPendingError" });
    await expect(runtime.beginDeletion()).resolves.toEqual(deletion);
    await expect(
      runtime.beginDeletion(payload.collection.collectionId),
    ).rejects.toMatchObject({ name: "OfflinePackDeletionPendingError" });
  });

  it("keeps an unrelated active pack while blocking only the deletion target", async () => {
    const shared = sharedDatabase();
    const runtime = runtimeFor(shared);
    const payload = offlineCollectionPackFixture();
    const fence = await runtime.prepareSave(
      payload.collection.collectionId,
      "local-v1",
    );
    const saved = await runtime.save(payload, "a separate passphrase", fence);
    shared.messages.length = 0;

    await runtime.beginDeletion(testIds.relatedPaper);

    await expect(runtime.inspect()).resolves.toEqual(saved);
    expect(shared.messages).toEqual(["LOCK"]);
    await expect(
      runtime.prepareSave(payload.collection.collectionId, "local-v1"),
    ).resolves.toMatchObject({ collectionDigest: saved.collectionDigest });
    await expect(
      runtime.prepareSave(testIds.relatedPaper, "local-v1"),
    ).rejects.toMatchObject({ name: "OfflinePackDeletionPendingError" });
  });

  it("atomically supersedes a targeted barrier with a fresh global barrier", async () => {
    const shared = sharedDatabase();
    const runtime = runtimeFor(shared);
    const payload = offlineCollectionPackFixture();
    const fence = await runtime.prepareSave(
      payload.collection.collectionId,
      "local-v1",
    );
    await runtime.save(payload, "a separate passphrase", fence);
    const targeted = await runtime.beginDeletion(testIds.relatedPaper);
    const targetedEpoch = (
      shared.records.get("control") as { lifecycleEpoch: string }
    ).lifecycleEpoch;

    const global = await runtime.beginDeletion();

    expect(global).toEqual({
      collectionDigest: null,
      deletionId: expect.stringMatching(/^[A-Za-z0-9_-]{22}$/u),
    });
    expect(global.deletionId).not.toBe(targeted.deletionId);
    expect(shared.records.get("deletion")).toEqual({
      slot: "deletion",
      formatVersion: 1,
      ...global,
    });
    expect(shared.records.has("active")).toBe(false);
    expect(
      (shared.records.get("control") as { lifecycleEpoch: string })
        .lifecycleEpoch,
    ).not.toBe(targetedEpoch);
    await expect(runtime.completeDeletion(targeted)).resolves.toBe(false);
    expect(shared.records.get("deletion")).toEqual({
      slot: "deletion",
      formatVersion: 1,
      ...global,
    });
    await expect(
      runtime.beginDeletion(payload.collection.collectionId),
    ).rejects.toMatchObject({ name: "OfflinePackDeletionPendingError" });
    await expect(runtime.beginDeletion()).resolves.toEqual(global);
  });

  it("rejects a final save when a matching barrier appears after preparation", async () => {
    const shared = sharedDatabase();
    let finishEncryption: (value: ArrayBuffer) => void = () => undefined;
    const encryptStarted = vi.fn();
    const savingContext = runtimeFor(
      shared,
      () =>
        new Promise((resolveEncryption) => {
          encryptStarted();
          finishEncryption = resolveEncryption;
        }),
    );
    const deletingContext = runtimeFor(shared);
    const payload = offlineCollectionPackFixture();
    const fence = await savingContext.prepareSave(
      payload.collection.collectionId,
      "local-v1",
    );
    const pendingSave = savingContext.save(
      payload,
      "a separate passphrase",
      fence,
    );
    await vi.waitFor(() => expect(encryptStarted).toHaveBeenCalledOnce());

    await deletingContext.beginDeletion(payload.collection.collectionId);
    finishEncryption(new Uint8Array(128).buffer);

    await expect(pendingSave).rejects.toMatchObject({
      name: "OfflinePackDeletionPendingError",
    });
    await expect(savingContext.inspect()).resolves.toBeNull();
  });

  it("binds a prepared fence to exactly one collection digest", async () => {
    const shared = sharedDatabase();
    const runtime = runtimeFor(shared);
    const payload = offlineCollectionPackFixture();
    const fence = await runtime.prepareSave(
      payload.collection.collectionId,
      "local-v1",
    );
    const replacement = offlineCollectionPackFixture({
      collection: {
        collectionId: testIds.relatedPaper,
        name: "Related work",
        description: null,
      },
    });

    await expect(
      runtime.save(replacement, "a separate passphrase", fence),
    ).rejects.toMatchObject({ name: "OfflinePackOperationCancelledError" });
    await expect(runtime.inspect()).resolves.toBeNull();
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
    const payload = offlineCollectionPackFixture();
    const fence = await runtime.prepareSave(
      payload.collection.collectionId,
      "local-v1",
    );
    await expect(
      runtime.save(
        payload,
        "a separate passphrase",
        fence,
      ),
    ).resolves.toMatchObject({ ownerScope: "local-v1" });
  });

  it("keeps the prior active envelope when the final atomic put hits quota", async () => {
    const shared = sharedDatabase();
    const runtime = runtimeFor(shared);
    const firstPayload = offlineCollectionPackFixture();
    const firstFence = await runtime.prepareSave(
      firstPayload.collection.collectionId,
      "local-v1",
    );
    const first = await runtime.save(
      firstPayload,
      "a separate passphrase",
      firstFence,
    );
    shared.activePutFailure = new DOMException("quota", "QuotaExceededError");
    const replacement = offlineCollectionPackFixture({
      collection: {
        collectionId: testIds.relatedPaper,
        name: "Related work",
        description: null,
      },
    });
    const replacementFence = await runtime.prepareSave(
      replacement.collection.collectionId,
      "local-v1",
    );

    await expect(
      runtime.save(replacement, "a separate passphrase", replacementFence),
    ).rejects.toThrow("previous copy was kept");
    await expect(runtime.inspect()).resolves.toEqual(first);
  });
});
