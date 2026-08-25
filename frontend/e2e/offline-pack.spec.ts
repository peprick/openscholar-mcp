import AxeBuilder from "@axe-core/playwright";
import {
  expect,
  test,
  type APIRequestContext,
  type Page,
  type Request as PlaywrightRequest,
} from "@playwright/test";

const fixtureOrigin = `http://127.0.0.1:${process.env.PLAYWRIGHT_FIXTURE_PORT ?? "4100"}`;
const collectionId = "76fb2843-407a-4499-b3ac-59935440e928";
const collectionName = "Thesis foundations";
const paperTitle = "Graph neural networks for molecular property prediction";
const passphrase = "offline-fixture-2026";
const offlineDatabaseName = "openscholar-private-offline-v1";
const offlineStoreName = "packs";
const collectionDeleteGateUrl = `${fixtureOrigin}/__fixture/gates/collection-delete`;

type OfflineStoreRecord = Record<string, unknown> & { slot: string };

type MutationGateSnapshot = {
  armed: boolean;
  generation: number;
  reached: boolean;
};

const publicShellPaths = new Set([
  "/apple-touch-icon.png",
  "/icon.svg",
  "/icon-192.png",
  "/icon-512.png",
  "/manifest.webmanifest",
  "/offline.html",
  "/offline-pack.js",
]);

async function expectNoSeriousAccessibilityViolations(page: Page): Promise<void> {
  const scan = await new AxeBuilder({ page })
    .withTags([
      "wcag2a",
      "wcag2aa",
      "wcag21a",
      "wcag21aa",
      "wcag22a",
      "wcag22aa",
    ])
    .analyze();
  const violations = scan.violations
    .filter(
      (violation) =>
        violation.impact === "critical" || violation.impact === "serious",
    )
    .map((violation) => ({
      help: violation.help,
      id: violation.id,
      targets: violation.nodes.flatMap((node) => node.target),
    }));
  expect(violations).toEqual([]);
}

async function waitForControlledServiceWorker(page: Page): Promise<void> {
  await page.waitForFunction(async () => {
    if (!("serviceWorker" in navigator)) return false;
    const registration = await navigator.serviceWorker.ready;
    return registration.active !== null;
  });

  if (!(await page.evaluate(() => navigator.serviceWorker.controller !== null))) {
    await page.reload();
  }
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
}

async function armCollectionDeleteGate(
  request: APIRequestContext,
): Promise<MutationGateSnapshot> {
  const response = await request.post(`${collectionDeleteGateUrl}/arm`);
  expect(response.ok()).toBe(true);
  return (await response.json()) as MutationGateSnapshot;
}

async function collectionDeleteGateSnapshot(
  request: APIRequestContext,
): Promise<MutationGateSnapshot> {
  const response = await request.get(collectionDeleteGateUrl);
  expect(response.ok()).toBe(true);
  return (await response.json()) as MutationGateSnapshot;
}

async function releaseCollectionDeleteGate(
  request: APIRequestContext,
): Promise<void> {
  const response = await request.post(`${collectionDeleteGateUrl}/release`);
  expect(response.ok()).toBe(true);
}

async function readOfflineStoreRecords(
  page: Page,
): Promise<OfflineStoreRecord[]> {
  return page.evaluate(
    ({ databaseName, storeName }) =>
      new Promise<OfflineStoreRecord[]>((resolve, reject) => {
        const openRequest = indexedDB.open(databaseName);
        openRequest.onerror = () =>
          reject(openRequest.error ?? new Error("Could not open offline storage."));
        openRequest.onsuccess = () => {
          const database = openRequest.result;
          const transaction = database.transaction(storeName, "readonly");
          const readRequest = transaction.objectStore(storeName).getAll();
          transaction.oncomplete = () => {
            database.close();
            resolve(
              (readRequest.result as OfflineStoreRecord[]).toSorted((left, right) =>
                left.slot.localeCompare(right.slot),
              ),
            );
          };
          transaction.onabort = () => {
            database.close();
            reject(
              transaction.error ??
                new Error("Could not read encrypted offline storage."),
            );
          };
        };
      }),
    { databaseName: offlineDatabaseName, storeName: offlineStoreName },
  );
}

async function openOfflineRuntime(page: Page): Promise<void> {
  await page.goto("/offline.html");
  await page.waitForFunction(
    () => globalThis.OpenScholarOfflinePack !== undefined,
  );
  await expect(
    page.getByRole("button", { name: "Open encrypted offline collection" }),
  ).toBeEnabled();
}

async function saveFixturePack(page: Page): Promise<void> {
  await page.evaluate(
    async ({ fixtureCollectionId, fixturePassphrase }) => {
      const runtime = globalThis.OpenScholarOfflinePack;
      if (runtime === undefined) throw new Error("Offline runtime is unavailable.");
      const fence = await runtime.prepareSave(fixtureCollectionId, "local-v1");
      const response = await fetch(
        `/api/collections/${encodeURIComponent(fixtureCollectionId)}/offline-pack`,
        { cache: "no-store", headers: { accept: "application/json" } },
      );
      if (!response.ok) throw new Error("Could not load the offline fixture pack.");
      await runtime.save(await response.json(), fixturePassphrase, fence);
    },
    { fixtureCollectionId: collectionId, fixturePassphrase: passphrase },
  );
}

test.beforeEach(async ({ request }) => {
  const reset = await request.post(`${fixtureOrigin}/__fixture/reset`);
  expect(reset.ok()).toBe(true);
});

test("saves one encrypted metadata pack and opens it in a read-only offline reader", async ({
  context,
  page,
}) => {
  test.setTimeout(90_000);

  await page.goto("/");
  await waitForControlledServiceWorker(page);
  await page.goto(`/library/collections/${collectionId}`);

  await expect(
    page.getByRole("heading", { level: 1, name: collectionName }),
  ).toBeVisible();
  const offlineSettings = page.locator("section").filter({
    has: page.getByRole("heading", {
      level: 2,
      name: "Encrypted device copy",
    }),
  });
  await expect(offlineSettings).toContainText("PDFs are not included.");
  await offlineSettings
    .getByRole("button", { name: "Prepare offline copy" })
    .click();
  await offlineSettings
    .getByLabel("Offline passphrase", { exact: true })
    .fill(passphrase);
  await offlineSettings
    .getByLabel("Confirm offline passphrase")
    .fill(passphrase);

  const packResponsePromise = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/collections/${collectionId}/offline-pack`) &&
      response.request().method() === "GET",
  );
  await offlineSettings
    .getByRole("button", { name: "Save encrypted offline copy" })
    .click();
  const packResponse = await packResponsePromise;
  expect(packResponse.status()).toBe(200);
  expect(packResponse.headers()["cache-control"]).toContain("no-store");
  await expect(offlineSettings.getByRole("status")).toHaveText(
    "Encrypted offline copy saved. Open it from the offline screen with this passphrase.",
  );
  await expect(
    offlineSettings.getByRole("button", { name: "Replace offline copy" }),
  ).toBeVisible();

  const records = await readOfflineStoreRecords(page);
  expect(records.map((record) => record.slot)).toEqual(["active", "control"]);
  const legacyOpenError = await page.evaluate(
    (databaseName) =>
      new Promise<string | null>((resolve) => {
        const openRequest = indexedDB.open(databaseName, 1);
        openRequest.onerror = () =>
          resolve(openRequest.error?.name ?? "UnknownError");
        openRequest.onsuccess = () => {
          openRequest.result.close();
          resolve(null);
        };
      }),
    offlineDatabaseName,
  );
  expect(legacyOpenError).toBe("VersionError");
  const envelope = records.find((record) => record.slot === "active");
  const control = records.find((record) => record.slot === "control");
  if (envelope === undefined || control === undefined) {
    throw new Error("Encrypted offline storage did not contain both records.");
  }
  expect(Object.keys(envelope).toSorted()).toEqual(
    [
      "ciphertext",
      "collectionDigest",
      "cryptoProfile",
      "formatVersion",
      "iv",
      "ownerScope",
      "salt",
      "slot",
      "workFactor",
    ].toSorted(),
  );
  expect(envelope).toMatchObject({
    cryptoProfile: "pbkdf2-sha256-aes256gcm-v1",
    formatVersion: 1,
    ownerScope: "local-v1",
    slot: "active",
    workFactor: 600_000,
  });
  expect(envelope.collectionDigest).toMatch(/^[A-Za-z0-9_-]{43}$/u);
  expect(envelope.salt).toMatch(/^[A-Za-z0-9_-]{22}$/u);
  expect(envelope.iv).toMatch(/^[A-Za-z0-9_-]{16}$/u);
  expect(envelope.ciphertext).toMatch(/^[A-Za-z0-9_-]{64,}$/u);
  expect(Object.keys(control).toSorted()).toEqual(
    ["formatVersion", "lifecycleEpoch", "ownerScope", "slot"].toSorted(),
  );
  expect(control).toMatchObject({
    formatVersion: 1,
    ownerScope: "local-v1",
    slot: "control",
  });
  expect(control.lifecycleEpoch).toMatch(/^[A-Za-z0-9_-]{22}$/u);
  const serializedRecords = JSON.stringify(records);
  for (const sensitiveValue of [
    collectionName,
    collectionId,
    "Core papers for the literature review.",
    paperTitle,
    "Ada Researcher",
    "methods",
    passphrase,
  ]) {
    expect(serializedRecords).not.toContain(sensitiveValue);
  }

  const cacheSnapshot = await page.evaluate(async () => {
    const urls: string[] = [];
    let offlineHtml = "";
    for (const name of await caches.keys()) {
      if (!name.startsWith("openscholar-shell-")) continue;
      const cache = await caches.open(name);
      urls.push(...(await cache.keys()).map((request) => request.url));
      const response = await cache.match(
        new URL("/offline.html", globalThis.location.origin).href,
      );
      if (response !== undefined) offlineHtml = await response.text();
    }
    return { offlineHtml, urls };
  });

  expect(cacheSnapshot.urls.length).toBeGreaterThan(0);
  for (const value of cacheSnapshot.urls) {
    const url = new URL(value);
    expect(url.origin).toBe(new URL(page.url()).origin);
    expect(
      publicShellPaths.has(url.pathname) ||
        (url.search === "" && url.pathname.startsWith("/_next/static/")),
    ).toBe(true);
    expect(url.pathname.startsWith("/api/")).toBe(false);
    expect(url.pathname.startsWith("/library/")).toBe(false);
    expect(value).not.toContain(collectionId);
  }
  expect(cacheSnapshot.offlineHtml).not.toContain(collectionName);
  expect(cacheSnapshot.offlineHtml).not.toContain(paperTitle);
  expect(cacheSnapshot.offlineHtml).not.toContain(collectionId);

  await context.setOffline(true);
  try {
    await page.goto(
      `/library/collections/${collectionId}?offline-check=uncached-navigation`,
    );
    await expect(
      page.getByRole("heading", {
        level: 1,
        name: "Read your saved research metadata.",
      }),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", {
        level: 2,
        name: "Open an offline collection",
      }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Prepare offline copy" }),
    ).toHaveCount(0);

    const openButton = page.getByRole("button", {
      name: "Open encrypted offline collection",
    });
    await expect(openButton).toBeEnabled();
    await openButton.click();
    const offlinePassphrase = page.getByLabel("Offline passphrase", {
      exact: true,
    });
    await offlinePassphrase.fill("offline-fixture-wrong");
    await page.getByRole("button", { name: "Unlock" }).click();
    await expect(page.getByRole("status")).toHaveText(
      "The passphrase is incorrect, or the stored copy is unavailable.",
    );
    await expect(
      page.getByRole("heading", { level: 2, name: collectionName }),
    ).toHaveCount(0);

    await offlinePassphrase.fill(passphrase);
    await page.getByRole("button", { name: "Unlock" }).click();
    await expect(
      page.getByRole("heading", { level: 2, name: collectionName }),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", { level: 3, name: paperTitle }),
    ).toBeVisible();
    await expect(page.getByText("Ada Researcher", { exact: true })).toBeVisible();
    await expect(
      page.getByText("Reading status: Unread", { exact: true }),
    ).toBeVisible();
    await expect(page.getByText("Tags: methods", { exact: true })).toBeVisible();

    await expect(
      page.getByRole("link", {
        name: /(?:read here|open source|pdf|access)/iu,
      }),
    ).toHaveCount(0);
    await expect(
      page.getByRole("button", {
        name: /(?:save|edit|update|delete|refresh|check access)/iu,
      }),
    ).toHaveCount(0);
    await expect(page.getByRole("combobox")).toHaveCount(0);
    await expectNoSeriousAccessibilityViolations(page);

    await page.getByRole("button", { name: "Lock collection" }).click();
    await expect(page.getByRole("status")).toHaveText(
      "Offline collection locked.",
    );
    await expect(
      page.getByRole("heading", { level: 2, name: collectionName }),
    ).toHaveCount(0);

    await openButton.click();
    page.once("dialog", async (dialog) => {
      expect(dialog.message()).toBe(
        "Remove the encrypted offline collection from this device?",
      );
      await dialog.accept();
    });
    await page.getByRole("button", { name: "Remove copy" }).click();
    await expect(page.getByRole("status")).toHaveText(
      "Encrypted offline collection removed from this device.",
    );

    await openButton.click();
    await expect(page.getByRole("status")).toHaveText(
      "No encrypted offline collection is stored on this device.",
    );
    const remainingRecords = await readOfflineStoreRecords(page);
    expect(remainingRecords).toHaveLength(1);
    expect(remainingRecords[0]).toMatchObject({
      formatVersion: 1,
      ownerScope: null,
      slot: "control",
    });
    expect(Object.keys(remainingRecords[0] ?? {}).toSorted()).toEqual(
      ["formatVersion", "lifecycleEpoch", "ownerScope", "slot"].toSorted(),
    );
  } finally {
    await context.setOffline(false);
  }
});

test("rejects tampered ciphertext without deleting the encrypted records", async ({
  page,
}) => {
  test.setTimeout(60_000);
  await openOfflineRuntime(page);
  await saveFixturePack(page);

  const tamperedCiphertext = await page.evaluate(
    ({ databaseName, storeName }) =>
      new Promise<string>((resolve, reject) => {
        const openRequest = indexedDB.open(databaseName);
        openRequest.onerror = () =>
          reject(openRequest.error ?? new Error("Could not open offline storage."));
        openRequest.onsuccess = () => {
          const database = openRequest.result;
          const transaction = database.transaction(storeName, "readwrite");
          const store = transaction.objectStore(storeName);
          const readRequest = store.get("active");
          let changedCiphertext = "";
          readRequest.onsuccess = () => {
            const active = readRequest.result as
              | (OfflineStoreRecord & { ciphertext: string })
              | undefined;
            if (active === undefined || typeof active.ciphertext !== "string") {
              transaction.abort();
              return;
            }
            changedCiphertext = `${active.ciphertext.startsWith("A") ? "B" : "A"}${active.ciphertext.slice(1)}`;
            store.put({ ...active, ciphertext: changedCiphertext });
          };
          transaction.oncomplete = () => {
            database.close();
            resolve(changedCiphertext);
          };
          transaction.onabort = () => {
            database.close();
            reject(
              transaction.error ?? new Error("Could not tamper with the fixture."),
            );
          };
        };
      }),
    { databaseName: offlineDatabaseName, storeName: offlineStoreName },
  );

  const unlockFailure = await page.evaluate(async (fixturePassphrase) => {
    const runtime = globalThis.OpenScholarOfflinePack;
    if (runtime === undefined) throw new Error("Offline runtime is unavailable.");
    try {
      await runtime.unlock(fixturePassphrase, "local-v1");
      return null;
    } catch (error) {
      return {
        message: error instanceof Error ? error.message : String(error),
        name: error instanceof Error ? error.name : "UnknownError",
      };
    }
  }, passphrase);
  expect(unlockFailure).toMatchObject({
    name: "OfflinePackUnlockError",
  });
  expect(unlockFailure?.message).toContain("passphrase is incorrect");

  const records = await readOfflineStoreRecords(page);
  expect(records.map((record) => record.slot)).toEqual(["active", "control"]);
  expect(records[0]?.ciphertext).toBe(tamperedCiphertext);
  expect(records[1]).toMatchObject({ ownerScope: "local-v1", slot: "control" });
});

test("keeps the prior active envelope when the replacement put exceeds quota", async ({
  page,
}) => {
  test.setTimeout(60_000);
  await openOfflineRuntime(page);
  await saveFixturePack(page);
  const recordsBefore = await readOfflineStoreRecords(page);

  const quotaFailure = await page.evaluate(
    async ({ fixtureCollectionId, fixturePassphrase }) => {
      const response = await fetch(
        `/api/collections/${encodeURIComponent(fixtureCollectionId)}/offline-pack`,
        { cache: "no-store", headers: { accept: "application/json" } },
      );
      if (!response.ok) throw new Error("Could not load the offline fixture pack.");
      const replacement = (await response.json()) as Parameters<
        NonNullable<typeof globalThis.OpenScholarOfflinePack>["save"]
      >[0] & {
        collection: { collectionId: string; name: string };
      };
      replacement.collection.collectionId =
        "4a0f4958-e2a2-48a2-926d-43e8cb163810";
      replacement.collection.name = "Replacement collection";

      const runtime = globalThis.OpenScholarOfflinePack;
      if (runtime === undefined) throw new Error("Offline runtime is unavailable.");
      const fence = await runtime.prepareSave(
        replacement.collection.collectionId,
        "local-v1",
      );
      const originalPut = IDBObjectStore.prototype.put;
      IDBObjectStore.prototype.put = function quotaLimitedPut(value, key) {
        if (
          value !== null &&
          typeof value === "object" &&
          "slot" in value &&
          value.slot === "active"
        ) {
          throw new DOMException("Simulated offline quota", "QuotaExceededError");
        }
        return key === undefined
          ? originalPut.call(this, value)
          : originalPut.call(this, value, key);
      };
      try {
        await runtime.save(replacement, fixturePassphrase, fence);
        return null;
      } catch (error) {
        return {
          message: error instanceof Error ? error.message : String(error),
          name: error instanceof Error ? error.name : "UnknownError",
        };
      } finally {
        IDBObjectStore.prototype.put = originalPut;
      }
    },
    { fixtureCollectionId: collectionId, fixturePassphrase: passphrase },
  );
  expect(quotaFailure).toMatchObject({ name: "OfflinePackQuotaError" });
  expect(quotaFailure?.message).toContain("previous copy was kept");
  expect(await readOfflineStoreRecords(page)).toEqual(recordsBefore);

  const unlockedName = await page.evaluate(async (fixturePassphrase) => {
    const runtime = globalThis.OpenScholarOfflinePack;
    if (runtime === undefined) throw new Error("Offline runtime is unavailable.");
    return (await runtime.unlock(fixturePassphrase, "local-v1")).collection.name;
  }, passphrase);
  expect(unlockedName).toBe(collectionName);
});

test("blocks a fresh cross-tab save while collection deletion is pending", async ({
  context,
  page: deletePage,
  request,
}) => {
  test.setTimeout(60_000);
  const savePage = await context.newPage();
  let gateArmed = false;
  try {
    await Promise.all([
      deletePage.goto(`/library/collections/${collectionId}`),
      savePage.goto(`/library/collections/${collectionId}`),
    ]);
    await Promise.all([
      expect(
        deletePage.getByRole("heading", { level: 1, name: collectionName }),
      ).toBeVisible(),
      expect(
        savePage.getByRole("heading", { level: 1, name: collectionName }),
      ).toBeVisible(),
      deletePage.waitForFunction(
        () => globalThis.OpenScholarOfflinePack !== undefined,
      ),
      savePage.waitForFunction(
        () => globalThis.OpenScholarOfflinePack !== undefined,
      ),
    ]);

    const saveOfflineSettings = savePage.locator("section").filter({
      has: savePage.getByRole("heading", {
        level: 2,
        name: "Encrypted device copy",
      }),
    });
    await expect(saveOfflineSettings.getByRole("status")).toHaveText("");
    await saveOfflineSettings
      .getByRole("button", { name: "Prepare offline copy" })
      .click();
    await saveOfflineSettings
      .getByLabel("Offline passphrase", { exact: true })
      .fill(passphrase);
    await saveOfflineSettings
      .getByLabel("Confirm offline passphrase")
      .fill(passphrase);
    await saveOfflineSettings
      .getByRole("button", { name: "Save encrypted offline copy" })
      .click();
    await expect(saveOfflineSettings.getByRole("status")).toHaveText(
      "Encrypted offline copy saved. Open it from the offline screen with this passphrase.",
    );
    await expect(
      saveOfflineSettings.getByRole("button", { name: "Replace offline copy" }),
    ).toBeVisible();

    const recordsBeforeDeletion = await readOfflineStoreRecords(savePage);
    expect(recordsBeforeDeletion.map((record) => record.slot)).toEqual([
      "active",
      "control",
    ]);
    const initialControl = recordsBeforeDeletion.find(
      (record) => record.slot === "control",
    );
    if (
      initialControl === undefined ||
      typeof initialControl.lifecycleEpoch !== "string"
    ) {
      throw new Error("The initial offline lifecycle control is missing.");
    }
    const initialControlEpoch = initialControl.lifecycleEpoch;
    expect(initialControlEpoch).toMatch(/^[A-Za-z0-9_-]{22}$/u);
    await expect(
      savePage.evaluate(async () => {
        const runtime = globalThis.OpenScholarOfflinePack;
        if (runtime === undefined) {
          throw new Error("Offline runtime is unavailable.");
        }
        return runtime.inspect();
      }),
    ).resolves.toMatchObject({ ownerScope: "local-v1" });

    const armedGate = await armCollectionDeleteGate(request);
    gateArmed = true;
    const deleteResponsePromise = deletePage.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/collections/${collectionId}`) &&
        response.request().method() === "DELETE",
    );
    deletePage.once("dialog", async (dialog) => {
      await dialog.accept();
    });
    await deletePage
      .getByRole("button", { name: "Delete collection" })
      .click();
    await expect
      .poll(() => collectionDeleteGateSnapshot(request))
      .toMatchObject({
        armed: true,
        generation: armedGate.generation,
        reached: true,
      });
    await expect(
      deletePage.getByRole("button", { name: "Deleting…" }),
    ).toBeDisabled();
    await expect(
      saveOfflineSettings.getByRole("button", { name: "Prepare offline copy" }),
    ).toBeVisible();

    const expectedCollectionDigest = await savePage.evaluate(
      async (fixtureCollectionId) => {
        const source = new TextEncoder().encode(fixtureCollectionId.toLowerCase());
        const digest = new Uint8Array(
          await crypto.subtle.digest("SHA-256", source),
        );
        return btoa(String.fromCharCode(...digest))
          .replace(/\+/gu, "-")
          .replace(/\//gu, "_")
          .replace(/=+$/gu, "");
      },
      collectionId,
    );
    const recordsDuringDeletion = await readOfflineStoreRecords(savePage);
    expect(recordsDuringDeletion.map((record) => record.slot)).toEqual([
      "control",
      "deletion",
    ]);
    const control = recordsDuringDeletion.find(
      (record) => record.slot === "control",
    );
    const deletion = recordsDuringDeletion.find(
      (record) => record.slot === "deletion",
    );
    if (control === undefined || deletion === undefined) {
      throw new Error("The durable deletion barrier was not stored.");
    }
    expect(Object.keys(control).toSorted()).toEqual(
      ["formatVersion", "lifecycleEpoch", "ownerScope", "slot"].toSorted(),
    );
    expect(control).toMatchObject({
      formatVersion: 1,
      ownerScope: "local-v1",
      slot: "control",
    });
    expect(control.lifecycleEpoch).toMatch(/^[A-Za-z0-9_-]{22}$/u);
    expect(control.lifecycleEpoch).not.toBe(initialControlEpoch);
    expect(Object.keys(deletion).toSorted()).toEqual(
      ["collectionDigest", "deletionId", "formatVersion", "slot"].toSorted(),
    );
    expect(deletion).toMatchObject({
      collectionDigest: expectedCollectionDigest,
      formatVersion: 1,
      slot: "deletion",
    });
    expect(deletion.deletionId).toMatch(/^[A-Za-z0-9_-]{22}$/u);
    expect(JSON.stringify(recordsDuringDeletion)).not.toContain(collectionId);

    const backendPackResponse = await request.get(
      `${fixtureOrigin}/api/v1/collections/${collectionId}/offline-pack`,
    );
    expect(backendPackResponse.status()).toBe(200);
    expect(backendPackResponse.headers()["cache-control"]).toContain("no-store");

    await saveOfflineSettings
      .getByRole("button", { name: "Prepare offline copy" })
      .click();
    await saveOfflineSettings
      .getByLabel("Offline passphrase", { exact: true })
      .fill("fresh-delete-race-2026");
    await saveOfflineSettings
      .getByLabel("Confirm offline passphrase")
      .fill("fresh-delete-race-2026");
    const observedSnapshotRequests: string[] = [];
    const observeSnapshotRequest = (candidate: PlaywrightRequest): void => {
      const url = new URL(candidate.url());
      if (
        candidate.method() === "GET" &&
        url.pathname === `/api/collections/${collectionId}/offline-pack`
      ) {
        observedSnapshotRequests.push(candidate.url());
      }
    };
    savePage.on("request", observeSnapshotRequest);
    try {
      await saveOfflineSettings
        .getByRole("button", { name: "Save encrypted offline copy" })
        .click();
      await expect(saveOfflineSettings.getByRole("status")).toHaveText(
        "An offline deletion is still pending on this device.",
      );
    } finally {
      savePage.off("request", observeSnapshotRequest);
    }
    expect(observedSnapshotRequests).toEqual([]);
    expect(await readOfflineStoreRecords(savePage)).toEqual(
      recordsDuringDeletion,
    );

    await releaseCollectionDeleteGate(request);
    gateArmed = false;
    const deleteResponse = await deleteResponsePromise;
    expect(deleteResponse.status()).toBe(204);
    await deletePage.waitForURL(/\/library(?:\?|$)/u);

    const deletedPackResponse = await request.get(
      `/api/collections/${collectionId}/offline-pack`,
    );
    expect(deletedPackResponse.status()).toBe(404);
    await expect(
      savePage.evaluate(async () => {
        const runtime = globalThis.OpenScholarOfflinePack;
        if (runtime === undefined) {
          throw new Error("Offline runtime is unavailable.");
        }
        return runtime.inspect();
      }),
    ).resolves.toBeNull();
    expect(
      (await readOfflineStoreRecords(savePage)).map((record) => record.slot),
    ).toEqual(["control"]);
  } finally {
    if (gateArmed) await releaseCollectionDeleteGate(request);
    await savePage.close();
  }
});

test("rejects a delayed cross-page save after the lifecycle purge fence", async ({
  context,
  page,
}) => {
  test.setTimeout(60_000);
  const privacyPage = await context.newPage();
  try {
    await openOfflineRuntime(page);
    await openOfflineRuntime(privacyPage);
    await page.evaluate(
      async ({ fixtureCollectionId, fixturePassphrase }) => {
        const runtime = globalThis.OpenScholarOfflinePack;
        if (runtime === undefined) throw new Error("Offline runtime is unavailable.");
        const fence = await runtime.prepareSave(fixtureCollectionId, "local-v1");
        const response = await fetch(
          `/api/collections/${encodeURIComponent(fixtureCollectionId)}/offline-pack`,
          { cache: "no-store", headers: { accept: "application/json" } },
        );
        if (!response.ok) throw new Error("Could not load the offline fixture pack.");

        type RaceState = {
          outcome: null | { message: string; name: string; status: "rejected" };
          release: () => void;
          started: boolean;
        };
        const originalEncrypt = SubtleCrypto.prototype.encrypt;
        let releaseEncryption: () => void = () => undefined;
        const encryptionGate = new Promise<void>((resolve) => {
          releaseEncryption = resolve;
        });
        const raceState: RaceState = {
          outcome: null,
          release: releaseEncryption,
          started: false,
        };
        Object.defineProperty(globalThis, "__offlinePackRace", {
          configurable: true,
          value: raceState,
        });
        SubtleCrypto.prototype.encrypt = async function delayedEncryption(
          algorithm,
          key,
          data,
        ) {
          raceState.started = true;
          await encryptionGate;
          return originalEncrypt.call(this, algorithm, key, data);
        };
        void runtime
          .save(await response.json(), fixturePassphrase, fence)
          .then(() => {
            throw new Error("The delayed save unexpectedly committed.");
          })
          .catch((error) => {
            raceState.outcome = {
              message: error instanceof Error ? error.message : String(error),
              name: error instanceof Error ? error.name : "UnknownError",
              status: "rejected",
            };
          })
          .finally(() => {
            SubtleCrypto.prototype.encrypt = originalEncrypt;
          });
      },
      { fixtureCollectionId: collectionId, fixturePassphrase: passphrase },
    );
    await page.waitForFunction(
      () =>
        (
          globalThis as typeof globalThis & {
            __offlinePackRace?: { started: boolean };
          }
        ).__offlinePackRace?.started === true,
    );

    await privacyPage.evaluate(async () => {
      const runtime = globalThis.OpenScholarOfflinePack;
      if (runtime === undefined) throw new Error("Offline runtime is unavailable.");
      await runtime.purge();
    });
    await page.evaluate(() => {
      const race = (
        globalThis as typeof globalThis & {
          __offlinePackRace?: { release: () => void };
        }
      ).__offlinePackRace;
      if (race === undefined) throw new Error("Offline race fixture is unavailable.");
      race.release();
    });
    await page.waitForFunction(
      () =>
        (
          globalThis as typeof globalThis & {
            __offlinePackRace?: { outcome: unknown };
          }
        ).__offlinePackRace?.outcome !== null,
    );
    const outcome = await page.evaluate(
      () =>
        (
          globalThis as typeof globalThis & {
            __offlinePackRace?: { outcome: unknown };
          }
        ).__offlinePackRace?.outcome,
    );
    expect(outcome).toMatchObject({
      message: expect.stringContaining("cancelled by a privacy action"),
      name: "OfflinePackOperationCancelledError",
      status: "rejected",
    });
    expect(await readOfflineStoreRecords(privacyPage)).toEqual([
      expect.objectContaining({ ownerScope: null, slot: "control" }),
    ]);
    await expect(
      privacyPage.evaluate(async () => {
        const runtime = globalThis.OpenScholarOfflinePack;
        if (runtime === undefined) {
          throw new Error("Offline runtime is unavailable.");
        }
        return runtime.inspect();
      }),
    ).resolves.toBeNull();
  } finally {
    await privacyPage.close();
  }
});
