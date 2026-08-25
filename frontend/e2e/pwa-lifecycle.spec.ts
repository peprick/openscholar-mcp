import {
  expect,
  test,
  type APIRequestContext,
  type CDPSession,
  type Page,
} from "@playwright/test";

const releasePort = process.env.PLAYWRIGHT_PWA_RELEASE_PORT ?? "5100";
const releaseOrigin = `http://127.0.0.1:${releasePort}`;
const cachePrefix = "openscholar-shell-";
const unrelatedCacheName = "pwa-e2e-unrelated-cache";
const unrelatedCacheValue = "UNRELATED_CACHE_SENTINEL";
const offlineDatabaseName = "openscholar-private-offline-v1";
const offlineStoreName = "packs";
const collectionName = "Lifecycle-safe research";
const paperTitle = "Service worker transitions without private cache state";
const passphrase = "pwa-lifecycle-passphrase-2026";
const collectionId = "76fb2843-407a-4499-b3ac-59935440e928";

const fixedShellPaths = new Set([
  "/apple-touch-icon.png",
  "/icon.svg",
  "/icon-192.png",
  "/icon-512.png",
  "/manifest.webmanifest",
  "/offline.html",
  "/offline-pack.js",
]);

const decoyPaths = {
  api: "/api/pwa-e2e-private",
  auth: "/auth/pwa-e2e-private",
  authorization: "/_next/static/pwa-e2e/authorization.js",
  cacheable: "/_next/static/pwa-e2e/cacheable.js",
  credentialDependent: "/_next/static/pwa-e2e/credential-dependent.js",
  document: "/files/pwa-e2e-private.pdf",
  noStore: "/_next/static/pwa-e2e/no-store.js",
  privateResponse: "/_next/static/pwa-e2e/private.js",
  query: "/_next/static/pwa-e2e/query.js?owner=private-researcher",
  range: "/_next/static/pwa-e2e/range.js",
  redirected: "/_next/static/pwa-e2e/redirected.js",
  varyStar: "/_next/static/pwa-e2e/vary-star.js",
} as const;

const forbiddenCacheMarkers = [
  "API_PRIVATE_DECOY",
  "AUTH_PATH_PRIVATE_DECOY",
  "AUTHORIZATION_PRIVATE_DECOY",
  "COOKIE_ANONYMOUS_DECOY",
  "COOKIE_PRIVATE_DECOY",
  "DOCUMENT_PRIVATE_DECOY",
  "NO_STORE_RESPONSE_DECOY",
  "PRIVATE_RESPONSE_DECOY",
  "QUERY_PRIVATE_DECOY",
  "RANGE_PRIVATE_DECOY",
  "VARY_STAR_RESPONSE_DECOY",
] as const;

type ReleaseName = "previous" | "incoherent" | "current" | "rollback";

type ReleaseState = {
  assetRevision: string;
  currentRevision: string;
  generation: number;
  ok: true;
  previousRevision: string;
  release: ReleaseName;
  rollbackRevision: string;
  workerUrl: string;
  workerRevision: string;
};

type WorkerVersion = {
  registrationId: string;
  scriptURL: string;
  status:
    | "new"
    | "installing"
    | "installed"
    | "activating"
    | "activated"
    | "redundant";
  versionId: string;
};

type WorkerVersionUpdate = { versions: WorkerVersion[] };
type WorkerPredicate = (version: WorkerVersion) => boolean;

type CacheEntry = {
  body: string;
  cacheControl: string;
  url: string;
  vary: string;
};

type CacheSnapshot = { entries: CacheEntry[]; name: string }[];
type OfflineRecord = Record<string, unknown> & { slot: string };

function observeWorkerVersions(session: CDPSession) {
  const versions = new Map<string, WorkerVersion>();
  const waiters = new Set<{
    predicate: WorkerPredicate;
    resolve: (version: WorkerVersion) => void;
  }>();

  const updated = ({ versions: incoming }: WorkerVersionUpdate): void => {
    for (const version of incoming) {
      versions.set(version.versionId, version);
      for (const waiter of waiters) {
        if (!waiter.predicate(version)) continue;
        waiters.delete(waiter);
        waiter.resolve(version);
      }
    }
  };
  session.on("ServiceWorker.workerVersionUpdated", updated);

  return {
    async enable(): Promise<void> {
      await session.send("ServiceWorker.enable");
    },
    ids(): Set<string> {
      return new Set(versions.keys());
    },
    waitFor(predicate: WorkerPredicate): Promise<WorkerVersion> {
      for (const version of versions.values()) {
        if (predicate(version)) return Promise.resolve(version);
      }
      return new Promise((resolve) => {
        waiters.add({ predicate, resolve });
      });
    },
    dispose(): void {
      session.off("ServiceWorker.workerVersionUpdated", updated);
      waiters.clear();
    },
  };
}

async function selectRelease(
  request: APIRequestContext,
  release: ReleaseName,
): Promise<ReleaseState> {
  const response = await request.post(`${releaseOrigin}/__pwa/release`, {
    data: { release },
  });
  expect(response.ok()).toBe(true);
  const state = (await response.json()) as ReleaseState;
  expect(state).toMatchObject({
    ok: true,
    release,
  });
  if (release === "incoherent") {
    expect(state.assetRevision).not.toBe(state.workerRevision);
  } else {
    expect(state.assetRevision).toBe(state.workerRevision);
  }
  return state;
}

async function waitForControl(page: Page): Promise<void> {
  await page.waitForFunction(async () => {
    if (!("serviceWorker" in navigator)) return false;
    const registration = await navigator.serviceWorker.ready;
    return registration.active?.state === "activated";
  });
  if (!(await page.evaluate(() => navigator.serviceWorker.controller !== null))) {
    await page.reload();
  }
  await page.waitForFunction(
    () => navigator.serviceWorker.controller?.state === "activated",
  );
}

async function registrationState(page: Page) {
  return page.evaluate(async () => {
    const registration = await navigator.serviceWorker.getRegistration("/");
    if (registration === undefined) throw new Error("Registration is unavailable.");
    return {
      active: registration.active?.state ?? null,
      controlled: navigator.serviceWorker.controller !== null,
      installing: registration.installing?.state ?? null,
      waiting: registration.waiting?.state ?? null,
    };
  });
}

async function registerWorker(page: Page, workerUrl: string): Promise<string | null> {
  return page.evaluate(async (candidateUrl) => {
    try {
      await navigator.serviceWorker.register(candidateUrl, {
        scope: "/",
        updateViaCache: "none",
      });
      return null;
    } catch (error) {
      return error instanceof Error ? error.name : "UnknownError";
    }
  }, workerUrl);
}

async function cacheSnapshot(page: Page): Promise<CacheSnapshot> {
  return page.evaluate(async (prefix) => {
    const snapshots: CacheSnapshot = [];
    for (const name of (await caches.keys()).toSorted()) {
      if (!name.startsWith(prefix) && name !== "pwa-e2e-unrelated-cache") {
        continue;
      }
      const cache = await caches.open(name);
      const entries: CacheEntry[] = [];
      for (const cachedRequest of await cache.keys()) {
        const response = await cache.match(cachedRequest);
        if (response === undefined) continue;
        entries.push({
          body: await response.clone().text(),
          cacheControl: response.headers.get("cache-control") ?? "",
          url: cachedRequest.url,
          vary: response.headers.get("vary") ?? "",
        });
      }
      entries.sort((left, right) => left.url.localeCompare(right.url));
      snapshots.push({ entries, name });
    }
    return snapshots;
  }, cachePrefix);
}

async function readOfflineRecords(page: Page): Promise<OfflineRecord[]> {
  return page.evaluate(
    ({ databaseName, storeName }) =>
      new Promise<OfflineRecord[]>((resolve, reject) => {
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
              (readRequest.result as OfflineRecord[]).toSorted((left, right) =>
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

async function seedLocalState(page: Page): Promise<OfflineRecord[]> {
  await page.goto(`${releaseOrigin}/offline.html`);
  await page.waitForFunction(
    () => globalThis.OpenScholarOfflinePack !== undefined,
  );
  await page.evaluate(
    async ({ fixtureCollectionId, fixturePassphrase }) => {
      const runtime = globalThis.OpenScholarOfflinePack;
      if (runtime === undefined) throw new Error("Offline runtime is unavailable.");
      const payload = {
        schemaVersion: 1 as const,
        generatedAt: "2026-08-25T10:30:00Z",
        collection: {
          collectionId: fixtureCollectionId,
          name: "Lifecycle-safe research",
          description: "Encrypted metadata must survive worker transitions.",
        },
        papers: [
          {
            paperId: "22c1800e-77f4-4aa9-98d7-5f79fa9a8a1c",
            title: "Service worker transitions without private cache state",
            authors: ["Ada Researcher"],
            publicationDate: "2026-08-25",
            publicationYear: 2026,
            documentType: "ARTICLE" as const,
            language: "en",
            venueName: "OpenScholar Security Review",
            identifiers: [
              {
                type: "DOI" as const,
                namespace: "doi",
                value: "10.5555/openscholar.pwa.2026",
              },
            ],
            publisher: "Open Research Press",
            institution: null,
            volume: "1",
            issue: "1",
            pages: "1-12",
            articleNumber: null,
            edition: null,
            isbn: [],
            issn: ["1234-5678"],
            degree: null,
            readingStatus: "READING" as const,
            tags: ["security"],
          },
        ],
      };
      const fence = await runtime.prepareSave(fixtureCollectionId, "pwa-e2e-owner");
      await runtime.save(payload, fixturePassphrase, fence);
      runtime.lock();
      const unrelated = await caches.open("pwa-e2e-unrelated-cache");
      await unrelated.put(
        "/pwa-e2e-unrelated",
        new Response("UNRELATED_CACHE_SENTINEL", {
          headers: { "content-type": "text/plain" },
        }),
      );
    },
    { fixtureCollectionId: collectionId, fixturePassphrase: passphrase },
  );
  const records = await readOfflineRecords(page);
  expect(records.map((record) => record.slot)).toEqual(["active", "control"]);
  return records;
}

async function exerciseCacheBoundary(page: Page): Promise<void> {
  await page.goto(`${releaseOrigin}/`);
  await waitForControl(page);
  const responses = await page.evaluate(async (paths) => {
    async function read(
      path: string,
      init?: RequestInit,
    ): Promise<{ body: string; status: number; url: string }> {
      const response = await fetch(path, init);
      return {
        body: await response.text(),
        status: response.status,
        url: response.url,
      };
    }

    await read(paths.auth);
    return {
      api: await read(paths.api),
      auth: await read(paths.auth),
      authorization: await read(paths.authorization, {
        headers: { authorization: "Bearer pwa-e2e-private" },
      }),
      cacheable: await read(paths.cacheable),
      credentialDependent: await read(paths.credentialDependent),
      document: await read(paths.document),
      noStore: await read(paths.noStore),
      privateResponse: await read(paths.privateResponse),
      query: await read(paths.query),
      range: await read(paths.range, { headers: { range: "bytes=0-4" } }),
      redirected: await read(paths.redirected),
      varyStar: await read(paths.varyStar),
    };
  }, decoyPaths);

  expect(responses.cacheable).toMatchObject({ status: 200 });
  expect(responses.cacheable.body).toContain("PUBLIC_CACHEABLE_DECOY");
  expect(responses.redirected).toMatchObject({
    status: 200,
    url: `${releaseOrigin}${decoyPaths.api}`,
  });
  expect(responses.redirected.body).toContain("API_PRIVATE_DECOY");
  expect(responses.credentialDependent.body).toContain(
    "COOKIE_ANONYMOUS_DECOY",
  );
  for (const response of Object.values(responses)) {
    expect(response.status).toBeGreaterThanOrEqual(200);
    expect(response.status).toBeLessThan(300);
  }

  const snapshots = await cacheSnapshot(page);
  const shellSnapshots = snapshots.filter(({ name }) => name.startsWith(cachePrefix));
  expect(shellSnapshots).toHaveLength(1);
  const entries = shellSnapshots.flatMap(({ entries }) => entries);
  expect(entries.some(({ url }) => new URL(url).pathname === decoyPaths.cacheable)).toBe(
    true,
  );
  for (const entry of entries) {
    const url = new URL(entry.url);
    expect(url.origin).toBe(releaseOrigin);
    expect(url.search).toBe("");
    expect(
      fixedShellPaths.has(url.pathname) || url.pathname.startsWith("/_next/static/"),
    ).toBe(true);
    for (const marker of [
      ...forbiddenCacheMarkers,
      collectionId,
      collectionName,
      paperTitle,
      passphrase,
    ]) {
      expect(entry.body).not.toContain(marker);
    }
  }
  for (const excludedPath of [
    decoyPaths.api,
    decoyPaths.auth,
    decoyPaths.authorization,
    decoyPaths.credentialDependent,
    decoyPaths.document,
    decoyPaths.noStore,
    decoyPaths.privateResponse,
    new URL(decoyPaths.query, releaseOrigin).pathname,
    decoyPaths.range,
    decoyPaths.redirected,
    decoyPaths.varyStar,
  ]) {
    expect(entries.some(({ url }) => new URL(url).pathname === excludedPath)).toBe(
      false,
    );
  }
}

function expectOwnedCacheNames(
  snapshots: CacheSnapshot,
  revisions: string[],
): void {
  expect(
    snapshots
      .map(({ name }) => name)
      .filter((name) => name.startsWith(cachePrefix))
      .toSorted(),
  ).toEqual(revisions.map((revision) => `${cachePrefix}${revision}`).toSorted());
  for (const revision of revisions) {
    const owned = snapshots.find(
      ({ name }) => name === `${cachePrefix}${revision}`,
    );
    const shell = owned?.entries.find(
      ({ url }) => new URL(url).pathname === "/offline.html",
    );
    const runtime = owned?.entries.find(
      ({ url }) => new URL(url).pathname === "/offline-pack.js",
    );
    expect(shell?.body).toContain(
      `data-offline-reader-revision="${revision}"`,
    );
    expect(runtime?.body).toContain(`const READER_REVISION = "${revision}"`);
  }
  const unrelated = snapshots.find(({ name }) => name === unrelatedCacheName);
  expect(unrelated?.entries).toHaveLength(1);
  expect(unrelated?.entries[0]?.body).toBe(unrelatedCacheValue);
}

async function expectControlledReaderRevision(
  page: Page,
  revision: string,
): Promise<void> {
  await page.goto(`${releaseOrigin}/offline.html`);
  await expect(page.locator("html")).toHaveAttribute(
    "data-offline-reader-revision",
    revision,
  );
  await page.waitForFunction(
    () => globalThis.OpenScholarOfflinePack !== undefined,
  );
  expect(
    await page.evaluate(
      () => globalThis.OpenScholarOfflinePack?.constants.readerRevision ?? null,
    ),
  ).toBe(revision);
}

test.beforeEach(async ({ request }) => {
  await selectRelease(request, "previous");
});

test("keeps private state intact through failed, upgraded, and forward-rollback workers", async ({
  context,
  page,
  request,
}) => {
  test.setTimeout(120_000);
  const observerPage = await context.newPage();
  const cdp = await context.newCDPSession(observerPage);
  const workerVersions = observeWorkerVersions(cdp);
  await workerVersions.enable();

  try {
    const initial = await selectRelease(request, "previous");
    await page.goto(`${releaseOrigin}/`);
    await waitForControl(page);
    await expectControlledReaderRevision(page, initial.previousRevision);
    const recordsBefore = await seedLocalState(page);
    await exerciseCacheBoundary(page);
    expectOwnedCacheNames(await cacheSnapshot(page), [initial.previousRevision]);

    const knownBeforeFailure = workerVersions.ids();
    const incoherent = await selectRelease(request, "incoherent");
    const failedCandidatePromise = workerVersions.waitFor(
      (version) =>
        version.scriptURL === `${releaseOrigin}${incoherent.workerUrl}` &&
        !knownBeforeFailure.has(version.versionId) &&
        version.status === "redundant",
    );
    await registerWorker(page, incoherent.workerUrl);
    await failedCandidatePromise;

    expect(await registrationState(page)).toEqual({
      active: "activated",
      controlled: true,
      installing: null,
      waiting: null,
    });
    expectOwnedCacheNames(await cacheSnapshot(page), [initial.previousRevision]);
    await expectControlledReaderRevision(page, initial.previousRevision);
    expect(await readOfflineRecords(page)).toEqual(recordsBefore);

    const upgradePeer = await context.newPage();
    await upgradePeer.goto(`${releaseOrigin}/offline.html`);
    await waitForControl(upgradePeer);
    const knownBeforeUpgrade = workerVersions.ids();
    const current = await selectRelease(request, "current");
    const newerRuntimeUrl = `${releaseOrigin}/offline-pack.js?reader=${encodeURIComponent(current.currentRevision)}`;
    const newerRuntimeResponsePromise = page.waitForResponse(
      (response) => response.url() === newerRuntimeUrl,
    );
    await page.evaluate(
      (source) =>
        new Promise<void>((resolve, reject) => {
          const script = document.createElement("script");
          script.src = source;
          script.addEventListener(
            "load",
            () => {
              script.remove();
              resolve();
            },
            { once: true },
          );
          script.addEventListener(
            "error",
            () => {
              script.remove();
              reject(new Error("The newer reader runtime did not load."));
            },
            { once: true },
          );
          document.head.append(script);
        }),
      newerRuntimeUrl,
    );
    const newerRuntimeResponse = await newerRuntimeResponsePromise;
    expect(newerRuntimeResponse.fromServiceWorker()).toBe(false);
    expect(await newerRuntimeResponse.text()).toContain(
      `const READER_REVISION = "${current.currentRevision}"`,
    );
    const installedCurrentPromise = workerVersions.waitFor(
      (version) =>
        version.scriptURL === `${releaseOrigin}${current.workerUrl}` &&
        !knownBeforeUpgrade.has(version.versionId) &&
        version.status === "installed",
    );
    await registerWorker(page, current.workerUrl);
    const installedCurrent = await installedCurrentPromise;
    expect(await registrationState(page)).toEqual({
      active: "activated",
      controlled: true,
      installing: null,
      waiting: "installed",
    });
    expectOwnedCacheNames(await cacheSnapshot(page), [
      initial.previousRevision,
      current.currentRevision,
    ]);
    await expectControlledReaderRevision(upgradePeer, initial.previousRevision);

    const currentActivatedPromise = workerVersions.waitFor(
      (version) =>
        version.versionId === installedCurrent.versionId &&
        version.status === "activated",
    );
    await Promise.all([page.close(), upgradePeer.close()]);
    await currentActivatedPromise;

    const currentPage = await context.newPage();
    await currentPage.goto(`${releaseOrigin}/`);
    await waitForControl(currentPage);
    expectOwnedCacheNames(await cacheSnapshot(currentPage), [
      current.currentRevision,
    ]);
    expect(await readOfflineRecords(currentPage)).toEqual(recordsBefore);
    await exerciseCacheBoundary(currentPage);

    const rollbackPeer = await context.newPage();
    await rollbackPeer.goto(`${releaseOrigin}/`);
    await waitForControl(rollbackPeer);
    const knownBeforeRollback = workerVersions.ids();
    const rollback = await selectRelease(request, "rollback");
    await context.setOffline(true);
    try {
      const matchingRuntimeOffline = await currentPage.evaluate(
        async (revision) =>
          fetch(`/offline-pack.js?reader=${encodeURIComponent(revision)}`).then(
            (response) => response.text(),
          ),
        current.currentRevision,
      );
      expect(matchingRuntimeOffline).toContain(
        `const READER_REVISION = "${current.currentRevision}"`,
      );
    } finally {
      await context.setOffline(false);
    }
    const installedRollbackPromise = workerVersions.waitFor(
      (version) =>
        version.scriptURL === `${releaseOrigin}${rollback.workerUrl}` &&
        !knownBeforeRollback.has(version.versionId) &&
        version.status === "installed",
    );
    await registerWorker(currentPage, rollback.workerUrl);
    const installedRollback = await installedRollbackPromise;
    expect(await registrationState(currentPage)).toEqual({
      active: "activated",
      controlled: true,
      installing: null,
      waiting: "installed",
    });
    expectOwnedCacheNames(await cacheSnapshot(currentPage), [
      current.currentRevision,
      rollback.rollbackRevision,
    ]);
    await expectControlledReaderRevision(rollbackPeer, current.currentRevision);

    const rollbackActivatedPromise = workerVersions.waitFor(
      (version) =>
        version.versionId === installedRollback.versionId &&
        version.status === "activated",
    );
    await Promise.all([currentPage.close(), rollbackPeer.close()]);
    await rollbackActivatedPromise;

    const finalPage = await context.newPage();
    await finalPage.goto(`${releaseOrigin}/`);
    await waitForControl(finalPage);
    expectOwnedCacheNames(await cacheSnapshot(finalPage), [
      rollback.rollbackRevision,
    ]);
    expect(await readOfflineRecords(finalPage)).toEqual(recordsBefore);
    await exerciseCacheBoundary(finalPage);

    await context.setOffline(true);
    try {
      await finalPage.goto(`${releaseOrigin}/library?cold-offline=rollback`);
      await expect(finalPage.locator("html")).toHaveAttribute(
        "data-offline-reader-revision",
        rollback.rollbackRevision,
      );
      const openButton = finalPage.getByRole("button", {
        name: "Open encrypted offline collection",
      });
      await expect(openButton).toBeEnabled();
      await openButton.click();
      await finalPage
        .getByLabel("Offline passphrase", { exact: true })
        .fill(passphrase);
      await finalPage.getByRole("button", { name: "Unlock" }).click();
      await expect(
        finalPage.getByRole("heading", { level: 2, name: collectionName }),
      ).toBeVisible();
      await expect(
        finalPage.getByRole("heading", { level: 3, name: paperTitle }),
      ).toBeVisible();
    } finally {
      await context.setOffline(false);
    }
  } finally {
    workerVersions.dispose();
    await cdp.detach();
    await observerPage.close();
  }
});
