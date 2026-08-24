(function installOpenScholarOfflinePack(global) {
  "use strict";

  if (global.OpenScholarOfflinePack !== undefined) return;

  const DATABASE_NAME = "openscholar-private-offline-v1";
  const DATABASE_VERSION = 1;
  const STORE_NAME = "packs";
  const ACTIVE_KEY = "active";
  const CONTROL_KEY = "control";
  const CHANNEL_NAME = "openscholar-offline-pack-v1";
  const READER_REVISION = "2026-08-24-r2";
  const FORMAT_VERSION = 1;
  const CRYPTO_PROFILE = "pbkdf2-sha256-aes256gcm-v1";
  const WORK_FACTOR = 600000;
  const SALT_BYTES = 16;
  const IV_BYTES = 12;
  const TAG_BITS = 128;
  const TAG_BYTES = TAG_BITS / 8;
  const KEY_BITS = 256;
  const MAX_PAPERS = 500;
  const MAX_COLLECTION_NAME_CHARACTERS = 120;
  const MAX_COLLECTION_DESCRIPTION_CHARACTERS = 1000;
  const MAX_TAGS = 10;
  const MAX_TAG_CHARACTERS = 40;
  const MAX_PLAINTEXT_BYTES = 1048576;
  const MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + TAG_BYTES;
  const MIN_PASSPHRASE_CHARACTERS = 12;
  const MAX_PASSPHRASE_CHARACTERS = 128;
  const MAX_PASSPHRASE_BYTES = 256;
  const MAX_SCOPE_BYTES = 128;
  const AAD_PURPOSE = "openscholar-private-offline-pack";
  const UUID_PATTERN =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
  const INSTANT_PATTERN =
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/u;
  const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/u;
  const SCOPE_PATTERN = /^[A-Za-z0-9._~-]+$/u;
  const BASE64URL_PATTERN = /^[A-Za-z0-9_-]+$/u;
  const DOCUMENT_TYPES = new Set([
    "ARTICLE",
    "PREPRINT",
    "CONFERENCE_PAPER",
    "THESIS",
    "DISSERTATION",
    "BOOK",
    "BOOK_CHAPTER",
    "REPORT",
    "DATASET",
    "OTHER",
  ]);
  const IDENTIFIER_TYPES = new Set([
    "DOI",
    "ARXIV",
    "OPENALEX",
    "PMID",
    "PMCID",
    "CORE",
    "REPOSITORY",
  ]);
  const READING_STATUSES = new Set(["UNREAD", "READING", "COMPLETED"]);
  const ENVELOPE_KEYS = [
    "slot",
    "formatVersion",
    "cryptoProfile",
    "workFactor",
    "ownerScope",
    "collectionDigest",
    "salt",
    "iv",
    "ciphertext",
  ];
  const CONTROL_KEYS = [
    "slot",
    "formatVersion",
    "ownerScope",
    "lifecycleEpoch",
  ];
  const PAYLOAD_KEYS = [
    "schemaVersion",
    "generatedAt",
    "collection",
    "papers",
  ];
  const COLLECTION_KEYS = ["collectionId", "name", "description"];
  const IDENTIFIER_KEYS = ["type", "namespace", "value"];
  const PAPER_KEYS = [
    "paperId",
    "title",
    "authors",
    "publicationDate",
    "publicationYear",
    "documentType",
    "language",
    "venueName",
    "identifiers",
    "publisher",
    "institution",
    "volume",
    "issue",
    "pages",
    "articleNumber",
    "edition",
    "isbn",
    "issn",
    "degree",
    "readingStatus",
    "tags",
  ];
  const encoder = new TextEncoder();
  const subscribers = new Set();
  let channel = null;
  let operationEpoch = 0;

  class OfflinePackError extends Error {
    constructor(message, name = "OfflinePackError") {
      super(message);
      this.name = name;
    }
  }

  function invalidPack() {
    return new OfflinePackError(
      "The offline collection copy is unavailable or damaged.",
      "OfflinePackDataError",
    );
  }

  function unlockFailed() {
    return new OfflinePackError(
      "The passphrase is incorrect, or the offline copy is unavailable.",
      "OfflinePackUnlockError",
    );
  }

  function isRecord(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value);
  }

  function hasExactKeys(value, expected) {
    if (!isRecord(value)) return false;
    const actual = Object.keys(value);
    return (
      actual.length === expected.length &&
      expected.every((key) => Object.prototype.hasOwnProperty.call(value, key))
    );
  }

  function isNullableString(value) {
    return value === null || typeof value === "string";
  }

  function isStringArray(value) {
    return Array.isArray(value) && value.every((item) => typeof item === "string");
  }

  function isUuid(value) {
    return typeof value === "string" && UUID_PATTERN.test(value);
  }

  function isInstant(value) {
    return (
      typeof value === "string" &&
      INSTANT_PATTERN.test(value) &&
      Number.isFinite(Date.parse(value))
    );
  }

  function validateIdentifier(value) {
    return (
      hasExactKeys(value, IDENTIFIER_KEYS) &&
      IDENTIFIER_TYPES.has(value.type) &&
      typeof value.namespace === "string" &&
      typeof value.value === "string"
    );
  }

  function validatePaper(value) {
    return (
      hasExactKeys(value, PAPER_KEYS) &&
      isUuid(value.paperId) &&
      typeof value.title === "string" &&
      value.title.length > 0 &&
      isStringArray(value.authors) &&
      (value.publicationDate === null ||
        (typeof value.publicationDate === "string" &&
          DATE_PATTERN.test(value.publicationDate))) &&
      (value.publicationYear === null || Number.isInteger(value.publicationYear)) &&
      DOCUMENT_TYPES.has(value.documentType) &&
      isNullableString(value.language) &&
      isNullableString(value.venueName) &&
      Array.isArray(value.identifiers) &&
      value.identifiers.every(validateIdentifier) &&
      isNullableString(value.publisher) &&
      isNullableString(value.institution) &&
      isNullableString(value.volume) &&
      isNullableString(value.issue) &&
      isNullableString(value.pages) &&
      isNullableString(value.articleNumber) &&
      isNullableString(value.edition) &&
      isStringArray(value.isbn) &&
      isStringArray(value.issn) &&
      isNullableString(value.degree) &&
      READING_STATUSES.has(value.readingStatus) &&
      isStringArray(value.tags) &&
      value.tags.length <= MAX_TAGS &&
      value.tags.every(
        (tag) => tag.length > 0 && tag.length <= MAX_TAG_CHARACTERS,
      ) &&
      new Set(value.tags).size === value.tags.length
    );
  }

  function validatePayload(value) {
    if (
      !hasExactKeys(value, PAYLOAD_KEYS) ||
      value.schemaVersion !== 1 ||
      !isInstant(value.generatedAt) ||
      !hasExactKeys(value.collection, COLLECTION_KEYS) ||
      !isUuid(value.collection.collectionId) ||
      typeof value.collection.name !== "string" ||
      value.collection.name.length === 0 ||
      value.collection.name.length > MAX_COLLECTION_NAME_CHARACTERS ||
      !isNullableString(value.collection.description) ||
      (typeof value.collection.description === "string" &&
        value.collection.description.length >
          MAX_COLLECTION_DESCRIPTION_CHARACTERS) ||
      !Array.isArray(value.papers) ||
      value.papers.length > MAX_PAPERS ||
      !value.papers.every(validatePaper)
    ) {
      throw invalidPack();
    }
    return value;
  }

  function payloadBytes(value) {
    validatePayload(value);
    const serialized = JSON.stringify(value);
    const bytes = encoder.encode(serialized);
    if (bytes.byteLength > MAX_PLAINTEXT_BYTES) {
      bytes.fill(0);
      throw new OfflinePackError(
        "This collection is too large for an offline copy.",
        "OfflinePackTooLargeError",
      );
    }
    return bytes;
  }

  function validateOwnerScope(value) {
    if (
      typeof value !== "string" ||
      value.length === 0 ||
      !SCOPE_PATTERN.test(value) ||
      encoder.encode(value).byteLength > MAX_SCOPE_BYTES
    ) {
      throw new OfflinePackError(
        "The offline storage owner could not be verified.",
        "OfflinePackOwnerError",
      );
    }
    return value;
  }

  function passphraseBytes(value) {
    const characterCount = typeof value === "string" ? Array.from(value).length : 0;
    if (
      typeof value !== "string" ||
      characterCount < MIN_PASSPHRASE_CHARACTERS ||
      characterCount > MAX_PASSPHRASE_CHARACTERS
    ) {
      throw new OfflinePackError(
        `Use a passphrase with ${MIN_PASSPHRASE_CHARACTERS}–${MAX_PASSPHRASE_CHARACTERS} characters.`,
        "OfflinePackPassphraseError",
      );
    }
    const bytes = encoder.encode(value);
    if (bytes.byteLength > MAX_PASSPHRASE_BYTES) {
      bytes.fill(0);
      throw new OfflinePackError(
        `The passphrase must be at most ${MAX_PASSPHRASE_BYTES} UTF-8 bytes.`,
        "OfflinePackPassphraseError",
      );
    }
    return bytes;
  }

  function encodeBase64Url(bytes) {
    let binary = "";
    const chunkSize = 32768;
    for (let offset = 0; offset < bytes.byteLength; offset += chunkSize) {
      binary += String.fromCharCode(
        ...bytes.subarray(offset, Math.min(bytes.byteLength, offset + chunkSize)),
      );
    }
    return global
      .btoa(binary)
      .replace(/\+/gu, "-")
      .replace(/\//gu, "_")
      .replace(/=+$/gu, "");
  }

  function decodeBase64Url(value, maximumBytes, exactBytes) {
    const maximumCharacters = Math.ceil((maximumBytes * 4) / 3) + 2;
    if (
      typeof value !== "string" ||
      value.length === 0 ||
      value.length > maximumCharacters ||
      !BASE64URL_PATTERN.test(value)
    ) {
      throw invalidPack();
    }
    try {
      const padded = value.replace(/-/gu, "+").replace(/_/gu, "/");
      const binary = global.atob(
        padded + "=".repeat((4 - (padded.length % 4)) % 4),
      );
      if (
        binary.length > maximumBytes ||
        (exactBytes !== undefined && binary.length !== exactBytes)
      ) {
        throw invalidPack();
      }
      const bytes = new Uint8Array(binary.length);
      for (let index = 0; index < binary.length; index += 1) {
        bytes[index] = binary.charCodeAt(index);
      }
      if (encodeBase64Url(bytes) !== value) {
        bytes.fill(0);
        throw invalidPack();
      }
      return bytes;
    } catch (error) {
      if (error instanceof OfflinePackError) throw error;
      throw invalidPack();
    }
  }

  function validateEnvelope(value) {
    if (
      !hasExactKeys(value, ENVELOPE_KEYS) ||
      value.slot !== ACTIVE_KEY ||
      value.formatVersion !== FORMAT_VERSION ||
      value.cryptoProfile !== CRYPTO_PROFILE ||
      value.workFactor !== WORK_FACTOR
    ) {
      throw invalidPack();
    }
    validateOwnerScope(value.ownerScope);
    const digest = decodeBase64Url(value.collectionDigest, 32, 32);
    const salt = decodeBase64Url(value.salt, SALT_BYTES, SALT_BYTES);
    const iv = decodeBase64Url(value.iv, IV_BYTES, IV_BYTES);
    const ciphertext = decodeBase64Url(
      value.ciphertext,
      MAX_CIPHERTEXT_BYTES,
    );
    if (ciphertext.byteLength <= TAG_BYTES) {
      digest.fill(0);
      salt.fill(0);
      iv.fill(0);
      ciphertext.fill(0);
      throw invalidPack();
    }
    digest.fill(0);
    salt.fill(0);
    iv.fill(0);
    ciphertext.fill(0);
    return value;
  }

  function validateControl(value) {
    if (
      !hasExactKeys(value, CONTROL_KEYS) ||
      value.slot !== CONTROL_KEY ||
      value.formatVersion !== FORMAT_VERSION ||
      (value.ownerScope !== null && typeof value.ownerScope !== "string")
    ) {
      throw invalidPack();
    }
    if (value.ownerScope !== null) validateOwnerScope(value.ownerScope);
    const epoch = decodeBase64Url(value.lifecycleEpoch, 16, 16);
    epoch.fill(0);
    return value;
  }

  function freshLifecycleEpoch() {
    const bytes = global.crypto.getRandomValues(new Uint8Array(16));
    try {
      return encodeBase64Url(bytes);
    } finally {
      bytes.fill(0);
    }
  }

  function lifecycleControl(ownerScope) {
    return {
      slot: CONTROL_KEY,
      formatVersion: FORMAT_VERSION,
      ownerScope,
      lifecycleEpoch: freshLifecycleEpoch(),
    };
  }

  function normalizedOrigin() {
    if (global.location === undefined || typeof global.location.origin !== "string") {
      throw invalidPack();
    }
    return new URL(global.location.origin).origin;
  }

  function aad(ownerScope, collectionDigest, salt, iv) {
    return encoder.encode(
      JSON.stringify([
        AAD_PURPOSE,
        FORMAT_VERSION,
        ACTIVE_KEY,
        normalizedOrigin(),
        CRYPTO_PROFILE,
        ownerScope,
        collectionDigest,
        "PBKDF2",
        "SHA-256",
        WORK_FACTOR,
        salt,
        "AES-GCM",
        KEY_BITS,
        iv,
        TAG_BITS,
      ]),
    );
  }

  async function collectionDigest(collectionId) {
    if (!isUuid(collectionId)) throw invalidPack();
    const source = encoder.encode(collectionId.toLowerCase());
    try {
      const digest = new Uint8Array(
        await global.crypto.subtle.digest("SHA-256", source),
      );
      try {
        return encodeBase64Url(digest);
      } finally {
        digest.fill(0);
      }
    } finally {
      source.fill(0);
    }
  }

  async function deriveKey(passphrase, salt, usage) {
    const rawPassphrase = passphraseBytes(passphrase);
    try {
      const material = await global.crypto.subtle.importKey(
        "raw",
        rawPassphrase,
        "PBKDF2",
        false,
        ["deriveKey"],
      );
      return await global.crypto.subtle.deriveKey(
        {
          name: "PBKDF2",
          hash: "SHA-256",
          salt,
          iterations: WORK_FACTOR,
        },
        material,
        { name: "AES-GCM", length: KEY_BITS },
        false,
        [usage],
      );
    } finally {
      rawPassphrase.fill(0);
    }
  }

  function ensureStorageAvailable() {
    if (
      global.indexedDB === undefined ||
      global.crypto === undefined ||
      global.crypto.subtle === undefined
    ) {
      throw new OfflinePackError(
        "Encrypted offline storage is not supported by this browser.",
        "OfflinePackUnsupportedError",
      );
    }
  }

  function openDatabase() {
    ensureStorageAvailable();
    return new Promise((resolve, reject) => {
      const request = global.indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
      request.onupgradeneeded = () => {
        if (!request.result.objectStoreNames.contains(STORE_NAME)) {
          request.result.createObjectStore(STORE_NAME, { keyPath: "slot" });
        }
      };
      request.onsuccess = () => {
        request.result.onversionchange = () => request.result.close();
        resolve(request.result);
      };
      request.onerror = () => reject(request.error ?? invalidPack());
      request.onblocked = () =>
        reject(
          new OfflinePackError(
            "Offline storage is busy in another tab.",
            "OfflinePackStorageBusyError",
          ),
        );
    });
  }

  function requestResult(request) {
    return new Promise((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? invalidPack());
    });
  }

  function transactionDone(transaction) {
    return new Promise((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onabort = () => reject(transaction.error ?? invalidPack());
      transaction.onerror = () => undefined;
    });
  }

  async function readActive() {
    const database = await openDatabase();
    try {
      const transaction = database.transaction(STORE_NAME, "readonly");
      const done = transactionDone(transaction);
      const [value] = await Promise.all([
        requestResult(transaction.objectStore(STORE_NAME).get(ACTIVE_KEY)),
        done,
      ]);
      return value;
    } finally {
      database.close();
    }
  }

  async function storeTransaction(mode, work) {
    const database = await openDatabase();
    try {
      const transaction = database.transaction(STORE_NAME, mode);
      const done = transactionDone(transaction);
      try {
        const result = await work(transaction.objectStore(STORE_NAME));
        await done;
        return result;
      } catch (error) {
        // A failed request normally aborts the same transaction. Always
        // observe that second rejection before surfacing the primary error.
        await done.catch(() => undefined);
        throw error;
      }
    } finally {
      database.close();
    }
  }

  function operationCancelled() {
    return new OfflinePackError(
      "The offline operation was cancelled by a privacy action.",
      "OfflinePackOperationCancelledError",
    );
  }

  function isQuotaError(error) {
    return (
      global.DOMException !== undefined &&
      error instanceof global.DOMException &&
      (error.name === "QuotaExceededError" || error.name === "UnknownError")
    );
  }

  async function captureSaveFence(ownerScope) {
    return storeTransaction("readwrite", async (store) => {
      let control = await requestResult(store.get(CONTROL_KEY));
      const active = await requestResult(store.get(ACTIVE_KEY));
      if (active !== undefined) {
        validateEnvelope(active);
        if (active.ownerScope !== ownerScope) {
          throw new OfflinePackError(
            "The offline storage owner could not be verified.",
            "OfflinePackOwnerError",
          );
        }
      }
      if (control === undefined) {
        control = lifecycleControl(ownerScope);
        await requestResult(store.put(control));
      } else {
        validateControl(control);
        if (control.ownerScope === null && active === undefined) {
          // A full purge leaves a valid owner-neutral tombstone. The next save
          // may claim it with a fresh lifecycle epoch; an operation holding the
          // pre-purge epoch still cannot commit.
          control = lifecycleControl(ownerScope);
          await requestResult(store.put(control));
        } else if (control.ownerScope !== ownerScope) {
          throw new OfflinePackError(
            "The offline storage owner could not be verified.",
            "OfflinePackOwnerError",
          );
        }
      }
      return control.lifecycleEpoch;
    });
  }

  async function commitActive(envelope, lifecycleEpoch, expectedOperationEpoch) {
    if (operationEpoch !== expectedOperationEpoch) throw operationCancelled();
    try {
      await storeTransaction("readwrite", async (store) => {
        const control = await requestResult(store.get(CONTROL_KEY));
        if (control === undefined) throw operationCancelled();
        validateControl(control);
        if (
          operationEpoch !== expectedOperationEpoch ||
          control.ownerScope !== envelope.ownerScope ||
          control.lifecycleEpoch !== lifecycleEpoch
        ) {
          throw operationCancelled();
        }
        await requestResult(store.put(envelope));
      });
    } catch (error) {
      if (isQuotaError(error)) {
        throw new OfflinePackError(
          "This device does not have enough storage for the encrypted copy. The previous copy was kept.",
          "OfflinePackQuotaError",
        );
      }
      throw error;
    }
  }

  function broadcast(type) {
    if (!new Set(["LOCK", "PURGE", "REPLACED"]).has(type)) return;
    if (global.BroadcastChannel === undefined) return;
    try {
      if (channel === null) {
        channel = new global.BroadcastChannel(CHANNEL_NAME);
        channel.addEventListener("message", (event) => {
          const incoming = event.data;
          if (
            isRecord(incoming) &&
            Object.keys(incoming).length === 1 &&
            new Set(["LOCK", "PURGE", "REPLACED"]).has(incoming.type)
          ) {
            operationEpoch += 1;
            for (const subscriber of subscribers) subscriber(incoming.type);
          }
        });
      }
      channel.postMessage({ type });
    } catch {
      // Cross-tab signalling is best effort; encryption/storage remain decisive.
    }
  }

  function inspectionFromEnvelope(envelope) {
    return Object.freeze({
      formatVersion: envelope.formatVersion,
      cryptoProfile: envelope.cryptoProfile,
      ownerScope: envelope.ownerScope,
      collectionDigest: envelope.collectionDigest,
    });
  }

  async function save(payload, passphrase, ownerScope) {
    ensureStorageAvailable();
    const saveEpoch = operationEpoch;
    const scope = validateOwnerScope(ownerScope);
    const plaintext = payloadBytes(payload);
    let committedEnvelope;
    try {
      let lifecycleEpoch;
      try {
        lifecycleEpoch = await captureSaveFence(scope);
      } catch (error) {
        if (isQuotaError(error)) {
          throw new OfflinePackError(
            "This device does not have enough storage for the encrypted copy. The previous copy was kept.",
            "OfflinePackQuotaError",
          );
        }
        throw error;
      }
      const digest = await collectionDigest(payload.collection.collectionId);
      const salt = new Uint8Array(SALT_BYTES);
      const iv = new Uint8Array(IV_BYTES);
      let additionalData = null;
      try {
        global.crypto.getRandomValues(salt);
        global.crypto.getRandomValues(iv);
        const encodedSalt = encodeBase64Url(salt);
        const encodedIv = encodeBase64Url(iv);
        additionalData = aad(scope, digest, encodedSalt, encodedIv);
        const key = await deriveKey(passphrase, salt, "encrypt");
        const encrypted = new Uint8Array(
          await global.crypto.subtle.encrypt(
            { name: "AES-GCM", iv, additionalData, tagLength: TAG_BITS },
            key,
            plaintext,
          ),
        );
        try {
          if (encrypted.byteLength > MAX_CIPHERTEXT_BYTES) throw invalidPack();
          const envelope = {
            slot: ACTIVE_KEY,
            formatVersion: FORMAT_VERSION,
            cryptoProfile: CRYPTO_PROFILE,
            workFactor: WORK_FACTOR,
            ownerScope: scope,
            collectionDigest: digest,
            salt: encodedSalt,
            iv: encodedIv,
            ciphertext: encodeBase64Url(encrypted),
          };
          validateEnvelope(envelope);
          await commitActive(envelope, lifecycleEpoch, saveEpoch);
          committedEnvelope = envelope;
        } finally {
          encrypted.fill(0);
        }
      } finally {
        salt.fill(0);
        iv.fill(0);
        additionalData?.fill(0);
      }
    } finally {
      plaintext.fill(0);
    }
    broadcast("REPLACED");
    return inspectionFromEnvelope(committedEnvelope);
  }

  async function inspect() {
    const envelope = await readActive();
    if (envelope === undefined) return null;
    validateEnvelope(envelope);
    return inspectionFromEnvelope(envelope);
  }

  async function unlock(passphrase, expectedOwnerScope) {
    try {
      ensureStorageAvailable();
      const envelope = await readActive();
      if (envelope === undefined) throw unlockFailed();
      validateEnvelope(envelope);
      if (
        expectedOwnerScope !== undefined &&
        envelope.ownerScope !== validateOwnerScope(expectedOwnerScope)
      ) {
        throw unlockFailed();
      }
      const salt = decodeBase64Url(envelope.salt, SALT_BYTES, SALT_BYTES);
      const iv = decodeBase64Url(envelope.iv, IV_BYTES, IV_BYTES);
      const ciphertext = decodeBase64Url(
        envelope.ciphertext,
        MAX_CIPHERTEXT_BYTES,
      );
      const additionalData = aad(
        envelope.ownerScope,
        envelope.collectionDigest,
        envelope.salt,
        envelope.iv,
      );
      try {
        const key = await deriveKey(passphrase, salt, "decrypt");
        const decrypted = new Uint8Array(
          await global.crypto.subtle.decrypt(
            { name: "AES-GCM", iv, additionalData, tagLength: TAG_BITS },
            key,
            ciphertext,
          ),
        );
        try {
          if (decrypted.byteLength > MAX_PLAINTEXT_BYTES) throw unlockFailed();
          const payload = validatePayload(
            JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(decrypted)),
          );
          if (
            (await collectionDigest(payload.collection.collectionId)) !==
            envelope.collectionDigest
          ) {
            throw unlockFailed();
          }
          return payload;
        } finally {
          decrypted.fill(0);
        }
      } finally {
        salt.fill(0);
        iv.fill(0);
        ciphertext.fill(0);
        additionalData.fill(0);
      }
    } catch (error) {
      if (error instanceof OfflinePackError && error.name === "OfflinePackPassphraseError") {
        throw error;
      }
      throw unlockFailed();
    }
  }

  async function purge() {
    operationEpoch += 1;
    broadcast("LOCK");
    const removed = await storeTransaction("readwrite", async (store) => {
      const active = await requestResult(store.get(ACTIVE_KEY));
      await requestResult(store.put(lifecycleControl(null)));
      if (active !== undefined) await requestResult(store.delete(ACTIVE_KEY));
      return active !== undefined;
    });
    broadcast("PURGE");
    return removed;
  }

  async function purgeMismatched(ownerScope) {
    const scope = validateOwnerScope(ownerScope);
    const outcome = await storeTransaction("readwrite", async (store) => {
      let control = await requestResult(store.get(CONTROL_KEY));
      const active = await requestResult(store.get(ACTIVE_KEY));
      if (control !== undefined) validateControl(control);
      if (active !== undefined) validateEnvelope(active);
      const mismatch =
        (control !== undefined && control.ownerScope !== scope) ||
        (active !== undefined && active.ownerScope !== scope);
      if (control === undefined || mismatch) {
        control = lifecycleControl(scope);
        await requestResult(store.put(control));
      }
      if (mismatch && active !== undefined) {
        await requestResult(store.delete(ACTIVE_KEY));
      }
      return {
        ownerChanged: mismatch,
        removed: mismatch && active !== undefined,
      };
    });
    if (outcome.ownerChanged) {
      operationEpoch += 1;
      broadcast(outcome.removed ? "PURGE" : "LOCK");
    }
    return outcome.removed;
  }

  async function purgeCollection(collectionId) {
    const digest = await collectionDigest(collectionId);
    const removed = await storeTransaction("readwrite", async (store) => {
      const control = await requestResult(store.get(CONTROL_KEY));
      const active = await requestResult(store.get(ACTIVE_KEY));
      if (control !== undefined) validateControl(control);
      if (active !== undefined) validateEnvelope(active);
      if (
        control !== undefined &&
        active !== undefined &&
        control.ownerScope !== active.ownerScope
      ) {
        throw invalidPack();
      }
      await requestResult(
        store.put(
          lifecycleControl(
            control?.ownerScope ?? active?.ownerScope ?? null,
          ),
        ),
      );
      const matches =
        active !== undefined && active.collectionDigest === digest;
      if (matches) await requestResult(store.delete(ACTIVE_KEY));
      return matches;
    });
    operationEpoch += 1;
    broadcast(removed ? "PURGE" : "LOCK");
    return removed;
  }

  function lock() {
    operationEpoch += 1;
    broadcast("LOCK");
  }

  function subscribe(listener) {
    if (typeof listener !== "function") {
      throw new TypeError("An offline-pack event listener must be a function.");
    }
    subscribers.add(listener);
    broadcast("LOCK");
    return () => subscribers.delete(listener);
  }

  const runtime = Object.freeze({
    save,
    inspect,
    unlock,
    purge,
    purgeMismatched,
    purgeCollection,
    lock,
    subscribe,
    constants: Object.freeze({
      formatVersion: FORMAT_VERSION,
      readerRevision: READER_REVISION,
      cryptoProfile: CRYPTO_PROFILE,
      workFactor: WORK_FACTOR,
      maximumPapers: MAX_PAPERS,
      maximumPlaintextBytes: MAX_PLAINTEXT_BYTES,
      minimumPassphraseCharacters: MIN_PASSPHRASE_CHARACTERS,
      maximumPassphraseCharacters: MAX_PASSPHRASE_CHARACTERS,
      maximumPassphraseBytes: MAX_PASSPHRASE_BYTES,
    }),
  });
  global.OpenScholarOfflinePack = runtime;

  function appendText(parent, tagName, className, value) {
    const element = global.document.createElement(tagName);
    if (className !== "") element.className = className;
    element.textContent = value;
    parent.append(element);
    return element;
  }

  function readableEnum(value) {
    return value
      .toLowerCase()
      .split("_")
      .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
      .join(" ");
  }

  function renderPaper(parent, paper) {
    const item = global.document.createElement("li");
    item.className = "offlinePaper";
    appendText(item, "h3", "offlinePaper__title", paper.title);
    appendText(
      item,
      "p",
      "offlinePaper__authors",
      paper.authors.length === 0 ? "Authors unavailable" : paper.authors.join(", "),
    );
    const facts = [
      paper.publicationDate ??
        (paper.publicationYear === null ? null : String(paper.publicationYear)),
      readableEnum(paper.documentType),
      paper.venueName,
      paper.language,
    ].filter((value) => value !== null && value !== "");
    if (facts.length > 0) {
      appendText(item, "p", "offlinePaper__facts", facts.join(" · "));
    }
    appendText(
      item,
      "p",
      "offlinePaper__status",
      `Reading status: ${readableEnum(paper.readingStatus)}`,
    );
    if (paper.tags.length > 0) {
      appendText(item, "p", "offlinePaper__tags", `Tags: ${paper.tags.join(", ")}`);
    }
    parent.append(item);
  }

  function installReader() {
    const openButton = global.document.getElementById("offline-pack-open");
    if (openButton === null) return;
    const revisionStatus = global.document.getElementById("offline-pack-status");
    const revisionRecoveryButton = global.document.getElementById(
      "offline-pack-remove-any",
    );
    if (
      global.document.documentElement.dataset.offlineReaderRevision !==
        READER_REVISION ||
      runtime.constants.readerRevision !== READER_REVISION
    ) {
      openButton.disabled = true;
      if (revisionStatus !== null) {
        revisionStatus.textContent =
          "The offline reader needs an update before this copy can be opened.";
      }
      if (revisionRecoveryButton !== null) {
        revisionRecoveryButton.addEventListener("click", async () => {
          if (
            !global.confirm(
              "Remove the encrypted offline collection from this device?",
            )
          ) {
            return;
          }
          revisionRecoveryButton.disabled = true;
          runtime.lock();
          try {
            await runtime.purge();
            if (revisionStatus !== null) {
              revisionStatus.textContent =
                "Encrypted offline collection removed from this device.";
            }
          } catch {
            if (revisionStatus !== null) {
              revisionStatus.textContent =
                "The encrypted offline collection could not be removed.";
            }
          } finally {
            revisionRecoveryButton.disabled = false;
          }
        });
      }
      return;
    }
    const unlockForm = global.document.getElementById("offline-pack-unlock-form");
    const passphrase = global.document.getElementById("offline-pack-passphrase");
    const removeLockedButton = global.document.getElementById(
      "offline-pack-remove-locked",
    );
    const removeAnyButton = global.document.getElementById(
      "offline-pack-remove-any",
    );
    const reader = global.document.getElementById("offline-pack-reader");
    const title = global.document.getElementById("offline-pack-title");
    const description = global.document.getElementById("offline-pack-description");
    const generated = global.document.getElementById("offline-pack-generated");
    const papers = global.document.getElementById("offline-pack-papers");
    const filter = global.document.getElementById("offline-pack-filter");
    const status = global.document.getElementById("offline-pack-status");
    const lockButton = global.document.getElementById("offline-pack-lock");
    const purgeButton = global.document.getElementById("offline-pack-purge");
    if (
      unlockForm === null ||
      !(passphrase instanceof global.HTMLInputElement) ||
      removeLockedButton === null ||
      removeAnyButton === null ||
      reader === null ||
      title === null ||
      description === null ||
      generated === null ||
      papers === null ||
      !(filter instanceof global.HTMLInputElement) ||
      status === null ||
      lockButton === null ||
      purgeButton === null
    ) {
      return;
    }
    let unlockedPayload = null;
    let unlockEpoch = 0;

    function setStatus(message) {
      status.textContent = message;
    }

    function clearReader(message, shouldBroadcast) {
      unlockEpoch += 1;
      unlockedPayload = null;
      title.textContent = "";
      description.textContent = "";
      generated.textContent = "";
      papers.replaceChildren();
      filter.value = "";
      reader.hidden = true;
      unlockForm.hidden = true;
      passphrase.value = "";
      passphrase.disabled = false;
      removeLockedButton.disabled = false;
      const unlockButton = unlockForm.querySelector('button[type="submit"]');
      if (unlockButton !== null) unlockButton.disabled = false;
      openButton.hidden = false;
      if (message !== undefined) setStatus(message);
      if (shouldBroadcast) runtime.lock();
      if (global.document.visibilityState === "visible") openButton.focus();
    }

    async function reconcileOwner() {
      let response;
      let auth;
      let stage = "fetch";
      const controller = new global.AbortController();
      const timeout = global.setTimeout(() => controller.abort(), 3000);
      try {
        response = await global.fetch("/api/auth/status", {
          cache: "no-store",
          credentials: "same-origin",
          headers: { accept: "application/json" },
          signal: controller.signal,
        });
        if (!response.ok) return { kind: "locked", scope: undefined };
        stage = "parse";
        auth = await response.json();
      } catch (error) {
        if (
          stage === "fetch" &&
          (error instanceof TypeError ||
            (global.DOMException !== undefined &&
              error instanceof global.DOMException &&
              error.name === "AbortError"))
        ) {
          // A bounded network failure is the cold offline path. The passphrase
          // remains the only way to reveal an envelope in that case.
          return { kind: "offline", scope: undefined };
        }
        return { kind: "locked", scope: undefined };
      } finally {
        global.clearTimeout(timeout);
      }
      if (
        !hasExactKeys(auth, ["mode", "authenticated", "storageScope"]) ||
        (auth.mode !== "local" && auth.mode !== "oidc") ||
        typeof auth.authenticated !== "boolean"
      ) {
        return { kind: "locked", scope: undefined };
      }
      if (typeof auth.storageScope === "string") {
        try {
          await runtime.purgeMismatched(auth.storageScope);
          return { kind: "verified", scope: auth.storageScope };
        } catch {
          return { kind: "locked", scope: undefined };
        }
      }
      if (auth.mode === "oidc" && auth.storageScope === null) {
        try {
          await runtime.purge();
        } catch {
          return { kind: "locked", scope: undefined };
        }
        return { kind: "locked", scope: undefined };
      }
      return { kind: "locked", scope: undefined };
    }

    const initialOwnerCheck = reconcileOwner();
    initialOwnerCheck.then((owner) => {
      openButton.disabled = false;
      if (owner.kind === "locked") {
        setStatus("This browser’s offline owner could not be verified.");
      }
    });

    openButton.addEventListener("click", async () => {
      openButton.disabled = true;
      try {
        const owner = await reconcileOwner();
        if (owner.kind === "locked") {
          setStatus("This browser’s offline owner could not be verified.");
          return;
        }
        const stored = await runtime.inspect();
        if (stored === null) {
          setStatus("No encrypted offline collection is stored on this device.");
          return;
        }
        openButton.hidden = true;
        unlockForm.hidden = false;
        setStatus("Enter the passphrase used when this offline copy was saved.");
        passphrase.focus();
      } catch {
        setStatus("The stored offline copy is unavailable or damaged.");
      } finally {
        openButton.disabled = false;
      }
    });

    unlockForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const attempt = ++unlockEpoch;
      const submitter = unlockForm.querySelector('button[type="submit"]');
      if (submitter !== null) submitter.disabled = true;
      passphrase.disabled = true;
      removeLockedButton.disabled = true;
      try {
        const owner = await reconcileOwner();
        if (attempt !== unlockEpoch) return;
        if (owner.kind === "locked") {
          clearReader("This browser’s offline owner could not be verified.", false);
          return;
        }
        let secret = passphrase.value;
        passphrase.value = "";
        const unlockOperation = runtime.unlock(secret, owner.scope);
        secret = "";
        const payload = await unlockOperation;
        if (
          attempt !== unlockEpoch ||
          global.document.visibilityState !== "visible"
        ) {
          return;
        }
        unlockedPayload = payload;
        title.textContent = payload.collection.name;
        description.textContent =
          payload.collection.description ?? "Saved research collection";
        generated.textContent = `Saved for offline use ${new Date(
          payload.generatedAt,
        ).toLocaleString()}. This manual copy may be out of date.`;
        renderPapers();
        unlockForm.hidden = true;
        reader.hidden = false;
        setStatus("Offline collection unlocked for this page only.");
        title.focus();
      } catch {
        if (attempt !== unlockEpoch) return;
        passphrase.value = "";
        setStatus("The passphrase is incorrect, or the stored copy is unavailable.");
        passphrase.focus();
      } finally {
        if (attempt === unlockEpoch) {
          if (submitter !== null) submitter.disabled = false;
          passphrase.disabled = false;
          removeLockedButton.disabled = false;
        }
      }
    });

    function renderPapers() {
      papers.replaceChildren();
      if (unlockedPayload === null) return;
      const query = filter.value.trim().toLowerCase();
      const visible = unlockedPayload.papers.filter((paper) => {
        if (query === "") return true;
        return [paper.title, ...paper.authors, ...paper.tags]
          .join("\n")
          .toLowerCase()
          .includes(query);
      });
      for (const paper of visible) renderPaper(papers, paper);
      if (visible.length === 0) {
        const empty = global.document.createElement("li");
        empty.className = "offlinePaper offlinePaper--empty";
        empty.textContent =
          unlockedPayload.papers.length === 0
            ? "This collection did not contain any saved papers."
            : "No saved papers match this filter.";
        papers.append(empty);
      }
    }

    filter.addEventListener("input", renderPapers);

    lockButton.addEventListener("click", () => {
      clearReader("Offline collection locked.", true);
    });

    async function removeStoredCopy() {
      if (!global.confirm("Remove the encrypted offline collection from this device?")) {
        return;
      }
      clearReader("Removing the encrypted offline collection…", false);
      try {
        await runtime.purge();
        clearReader("Encrypted offline collection removed from this device.", false);
      } catch {
        setStatus("The encrypted offline collection could not be removed.");
      }
    }

    removeLockedButton.addEventListener("click", () => void removeStoredCopy());
    removeAnyButton.addEventListener("click", () => void removeStoredCopy());
    purgeButton.addEventListener("click", () => void removeStoredCopy());
    global.document.addEventListener("visibilitychange", () => {
      if (global.document.visibilityState === "hidden") {
        clearReader("Offline collection locked.", true);
      }
    });
    global.addEventListener("pagehide", () => {
      clearReader(undefined, true);
    });
    runtime.subscribe((type) => {
      if (type === "LOCK") {
        clearReader("Offline collection locked in another tab.", false);
      } else if (type === "PURGE") {
        clearReader("The encrypted offline collection was removed.", false);
      } else if (type === "REPLACED") {
        clearReader("The encrypted offline collection was replaced. Unlock it again.", false);
      }
    });
  }

  if (global.document !== undefined) {
    if (global.document.readyState === "loading") {
      global.document.addEventListener("DOMContentLoaded", installReader, {
        once: true,
      });
    } else {
      installReader();
    }
  }
})(globalThis);
