"use client";

import { useEffect, useRef, useState } from "react";
import { z } from "zod";

import { responseErrorMessage } from "@/features/library/library-client";
import { loadOfflinePackRuntime } from "@/pwa/offline-pack-loader";
import { offlineCollectionPackSchema } from "@/shared/api/library-schemas";

const authStatusSchema = z
  .object({
    mode: z.enum(["local", "oidc"]),
    authenticated: z.boolean(),
    storageScope: z.string().min(1).max(128).nullable(),
  })
  .strict();

const MIN_PASSPHRASE_CHARACTERS = 12;
const MAX_PASSPHRASE_CHARACTERS = 128;
const MAX_PASSPHRASE_BYTES = 256;

async function currentStorageScope(): Promise<string | null> {
  const response = await fetch("/api/auth/status", {
    cache: "no-store",
    headers: { accept: "application/json" },
  });
  if (!response.ok) {
    throw new Error("OpenScholar could not verify this browser’s offline owner.");
  }
  const parsed = authStatusSchema.safeParse(await response.json());
  if (!parsed.success) {
    throw new Error("OpenScholar received an unexpected sign-in response.");
  }
  return parsed.data.storageScope;
}

function passphraseProblem(passphrase: string): string | null {
  const characterCount = Array.from(passphrase).length;
  if (
    characterCount < MIN_PASSPHRASE_CHARACTERS ||
    characterCount > MAX_PASSPHRASE_CHARACTERS
  ) {
    return `Use ${MIN_PASSPHRASE_CHARACTERS}–${MAX_PASSPHRASE_CHARACTERS} characters.`;
  }
  if (new TextEncoder().encode(passphrase).byteLength > MAX_PASSPHRASE_BYTES) {
    return `The passphrase must be at most ${MAX_PASSPHRASE_BYTES} UTF-8 bytes.`;
  }
  return null;
}

export function OfflinePackManager({
  collectionId,
}: {
  collectionId: string;
}): React.JSX.Element {
  const passphraseRef = useRef<HTMLInputElement>(null);
  const confirmationRef = useRef<HTMLInputElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [stored, setStored] = useState<boolean | null>(null);
  const [inspectionComplete, setInspectionComplete] = useState(false);
  const [pending, setPending] = useState<"save" | "remove" | null>(null);
  const [message, setMessage] = useState(
    "Checking encrypted offline storage on this device…",
  );

  useEffect(() => {
    let active = true;
    let unsubscribe: () => void = () => undefined;

    async function inspectDeviceCopy(): Promise<void> {
      try {
        const [runtime, scope] = await Promise.all([
          loadOfflinePackRuntime(),
          currentStorageScope(),
        ]);
        if (!active) return;
        if (scope === null) {
          await runtime.purge();
        } else {
          await runtime.purgeMismatched(scope);
        }
        if (!active) return;
        setStored((await runtime.inspect()) !== null);
        setInspectionComplete(true);
        setMessage("");
        unsubscribe = runtime.subscribe((event) => {
          if (!active) return;
          if (event === "PURGE") setStored(false);
          if (event === "REPLACED") setStored(true);
        });
      } catch {
        if (!active) return;
        setStored(null);
        setInspectionComplete(true);
        setMessage("Encrypted offline storage is not available right now.");
      }
    }

    void inspectDeviceCopy();
    return () => {
      active = false;
      unsubscribe();
    };
  }, []);

  async function saveOfflineCopy(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const validationMessage = passphraseProblem(
      passphraseRef.current?.value ?? "",
    );
    if (validationMessage !== null) {
      setMessage(validationMessage);
      passphraseRef.current?.focus();
      return;
    }
    if (
      (confirmationRef.current?.value ?? "") !==
      (passphraseRef.current?.value ?? "")
    ) {
      setMessage("The passphrases must match exactly.");
      confirmationRef.current?.focus();
      return;
    }

    setPending("save");
    setMessage("");
    try {
      const [runtime, scope] = await Promise.all([
        loadOfflinePackRuntime(),
        currentStorageScope(),
      ]);
      if (scope === null) {
        await runtime.purge();
        setStored(false);
        setMessage("Sign in again before saving an encrypted offline copy.");
        return;
      }
      await runtime.purgeMismatched(scope);

      const response = await fetch(
        `/api/collections/${encodeURIComponent(collectionId)}/offline-pack`,
        { cache: "no-store", headers: { accept: "application/json" } },
      );
      if (!response.ok) {
        setMessage(
          await responseErrorMessage(
            response,
            "The encrypted offline copy could not be prepared.",
          ),
        );
        return;
      }
      const parsed = offlineCollectionPackSchema.safeParse(await response.json());
      if (
        !parsed.success ||
        parsed.data.collection.collectionId.toLowerCase() !==
          collectionId.toLowerCase()
      ) {
        setMessage("The server returned an unexpected offline collection response.");
        return;
      }

      let passphrase = passphraseRef.current?.value ?? "";
      let confirmation = confirmationRef.current?.value ?? "";
      const finalValidationMessage = passphraseProblem(passphrase);
      if (finalValidationMessage !== null) {
        setMessage(finalValidationMessage);
        passphraseRef.current?.focus();
        return;
      }
      if (confirmation !== passphrase) {
        setMessage("The passphrases must match exactly.");
        confirmationRef.current?.focus();
        return;
      }
      const saveOperation = runtime.save(parsed.data, passphrase, scope);
      passphrase = "";
      confirmation = "";
      formRef.current?.reset();
      await saveOperation;
      setFormOpen(false);
      setStored(true);
      setMessage(
        "Encrypted offline copy saved. Open it from the offline screen with this passphrase.",
      );
    } catch (error) {
      setMessage(
        error instanceof Error
          ? error.message
          : "The encrypted offline copy could not be saved.",
      );
    } finally {
      setPending(null);
    }
  }

  async function removeOfflineCopy(): Promise<void> {
    if (!window.confirm("Remove the encrypted offline collection from this device?")) {
      return;
    }
    setPending("remove");
    setMessage("");
    try {
      const runtime = await loadOfflinePackRuntime();
      await runtime.purge();
      setStored(false);
      setMessage("Encrypted offline copy removed from this device.");
    } catch {
      setMessage("The encrypted offline copy could not be removed.");
    } finally {
      setPending(null);
    }
  }

  return (
    <section className="offlinePackSettings" aria-labelledby="offline-pack-heading">
      <div className="offlinePackSummary">
        <span className="eyebrow">Offline access</span>
        <h2 id="offline-pack-heading">Encrypted device copy</h2>
        <p>
          Save this collection’s citation and reading metadata for a read-only
          offline view. PDFs are not included. Only one encrypted collection is
          kept on this device, so saving replaces the previous copy. It is a
          manual snapshot that can become out of date, and the browser may evict it.
        </p>
        {stored === true ? (
          <p className="offlinePackStored">An encrypted collection is stored.</p>
        ) : null}
      </div>
      <div className="offlinePackControls">
        {!formOpen ? (
          <div className="offlinePackButtons">
            <button
              className="button button--primary"
              disabled={pending !== null}
              onClick={() => setFormOpen(true)}
              type="button"
            >
              {stored ? "Replace offline copy" : "Prepare offline copy"}
            </button>
            {stored === true || (inspectionComplete && stored === null) ? (
              <>
                {stored === true ? (
                  <a className="button button--secondary" href="/offline.html">
                    Open offline library
                  </a>
                ) : null}
                <button
                  className="button button--danger"
                  disabled={pending !== null}
                  onClick={() => void removeOfflineCopy()}
                  type="button"
                >
                  {pending === "remove"
                    ? "Removing…"
                    : stored === true
                      ? "Remove encrypted offline copy"
                      : "Clear unavailable offline data"}
                </button>
              </>
            ) : null}
          </div>
        ) : (
          <form
            className="offlinePackForm"
            onSubmit={(event) => void saveOfflineCopy(event)}
            ref={formRef}
          >
            <div className="fieldGroup">
              <label htmlFor="offline-pack-save-passphrase">Offline passphrase</label>
              <input
                autoComplete="off"
                disabled={pending !== null}
                id="offline-pack-save-passphrase"
                ref={passphraseRef}
                required
                spellCheck={false}
                type="password"
              />
              <small>
                12–128 characters (at most 256 UTF-8 bytes). This is separate
                from sign-in and cannot be recovered.
              </small>
            </div>
            <div className="fieldGroup">
              <label htmlFor="offline-pack-save-confirmation">
                Confirm offline passphrase
              </label>
              <input
                autoComplete="off"
                disabled={pending !== null}
                id="offline-pack-save-confirmation"
                ref={confirmationRef}
                required
                spellCheck={false}
                type="password"
              />
            </div>
            <div className="offlinePackButtons">
              <button
                className="button button--primary"
                disabled={pending !== null}
                type="submit"
              >
                {pending === "save" ? "Encrypting…" : "Save encrypted offline copy"}
              </button>
              <button
                className="button button--secondary"
                disabled={pending !== null}
                onClick={() => {
                  formRef.current?.reset();
                  setFormOpen(false);
                  setMessage("");
                }}
                type="button"
              >
                Cancel
              </button>
            </div>
          </form>
        )}
        <p aria-live="polite" className="libraryMessage" role="status">
          {message}
        </p>
      </div>
    </section>
  );
}
