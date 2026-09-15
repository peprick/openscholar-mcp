import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { OfflinePackManager } from "@/features/library/offline-pack-manager";
import type { OpenScholarOfflinePackRuntime } from "@/pwa/offline-pack-runtime";
import { offlineCollectionPackFixture, testIds } from "@/test/fixtures";

const loadRuntime = vi.hoisted(() => vi.fn());

vi.mock("@/pwa/offline-pack-loader", () => ({
  loadOfflinePackRuntime: loadRuntime,
}));

const inspection = {
  formatVersion: 1 as const,
  cryptoProfile: "pbkdf2-sha256-aes256gcm-v1" as const,
  ownerScope: "local-v1",
  collectionDigest: "opaque-digest",
};
const saveFence = {
  collectionDigest: "opaque-digest",
  lifecycleEpoch: "opaque-lifecycle-epoch",
  ownerScope: "local-v1",
};
const deletionFence = {
  collectionDigest: null,
  deletionId: "opaque-deletion-id",
};

let runtime: OpenScholarOfflinePackRuntime;

function authResponse(): Response {
  return Response.json({
    mode: "local",
    authenticated: false,
    storageScope: "local-v1",
  });
}

beforeEach(() => {
  runtime = {
    constants: {
      formatVersion: 1,
      readerRevision: "2026-08-24-r4",
      cryptoProfile: "pbkdf2-sha256-aes256gcm-v1",
      workFactor: 600000,
      maximumPapers: 500,
      maximumPlaintextBytes: 1048576,
      minimumPassphraseCharacters: 12,
      maximumPassphraseCharacters: 128,
      maximumPassphraseBytes: 256,
    },
    prepareSave: vi.fn().mockResolvedValue(saveFence),
    save: vi.fn().mockResolvedValue(inspection),
    beginDeletion: vi.fn().mockResolvedValue(deletionFence),
    completeDeletion: vi.fn().mockResolvedValue(true),
    inspect: vi.fn().mockResolvedValue(null),
    unlock: vi.fn(),
    purge: vi.fn().mockResolvedValue(false),
    purgeMismatched: vi.fn().mockResolvedValue(false),
    lock: vi.fn(),
    subscribe: vi.fn(() => () => undefined),
  };
  loadRuntime.mockResolvedValue(runtime);
});

afterEach(() => {
  cleanup();
  loadRuntime.mockReset();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("OfflinePackManager", () => {
  it("keeps preparation unavailable until device inspection finishes", async () => {
    let finishInspection: (result: null) => void = () => undefined;
    vi.mocked(runtime.inspect).mockReturnValue(new Promise((resolve) => {
      finishInspection = resolve;
    }));
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(authResponse()));
    render(<OfflinePackManager collectionId={testIds.collection} />);
    const prepare = screen.getByRole("button", { name: "Prepare offline copy" });
    expect(prepare).toBeDisabled();
    expect(prepare).toHaveAttribute("aria-describedby", "offline-pack-status");
    expect(screen.getByRole("region", { name: "Encrypted device copy" })).toHaveAttribute("aria-busy", "true");

    await waitFor(() => expect(runtime.inspect).toHaveBeenCalledOnce());
    await act(async () => finishInspection(null));
    await waitFor(() => expect(prepare).toBeEnabled());
    expect(prepare).not.toHaveAttribute("aria-describedby");
    expect(screen.getByRole("region", { name: "Encrypted device copy" })).toHaveAttribute("aria-busy", "false");
  });

  it("offers Retry and prevents preparing a copy after an inspection failure", async () => {
    const user = userEvent.setup();
    vi.mocked(runtime.inspect)
      .mockRejectedValueOnce(new Error("storage blocked"))
      .mockResolvedValueOnce(null);
    vi.stubGlobal("fetch", vi.fn(async () => authResponse()));
    render(<OfflinePackManager collectionId={testIds.collection} />);

    const retry = await screen.findByRole("button", { name: "Retry offline access" });
    const prepare = screen.getByRole("button", { name: "Prepare offline copy" });
    expect(prepare).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("Retry before preparing a copy.");
    await user.click(prepare);
    expect(screen.queryByLabelText("Offline passphrase")).not.toBeInTheDocument();

    await user.click(retry);
    await waitFor(() => expect(prepare).toBeEnabled());
    expect(runtime.inspect).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole("button", { name: "Retry offline access" })).not.toBeInTheDocument();
  });

  it("fetches the bounded export and saves it with an exact separate passphrase", async () => {
    const user = userEvent.setup();
    const payload = offlineCollectionPackFixture();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === "/api/auth/status") return authResponse();
      if (input === `/api/collections/${testIds.collection}/offline-pack`) {
        return Response.json(payload);
      }
      throw new Error(`Unexpected request: ${String(input)}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<OfflinePackManager collectionId={testIds.collection} />);

    await waitFor(() => expect(runtime.inspect).toHaveBeenCalledOnce());
    await user.click(screen.getByRole("button", { name: "Prepare offline copy" }));
    await user.type(screen.getByLabelText("Offline passphrase"), "exact phrase 🔐");
    await user.type(
      screen.getByLabelText("Confirm offline passphrase"),
      "exact phrase 🔐",
    );
    await user.click(
      screen.getByRole("button", { name: "Save encrypted offline copy" }),
    );

    await waitFor(() =>
      expect(runtime.save).toHaveBeenCalledWith(
        payload,
        "exact phrase 🔐",
        saveFence,
      ),
    );
    expect(runtime.prepareSave).toHaveBeenCalledWith(
      testIds.collection,
      "local-v1",
    );
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/collections/${testIds.collection}/offline-pack`,
      { cache: "no-store", headers: { accept: "application/json" } },
    );
    const exportCall = fetchMock.mock.calls.findIndex(
      ([input]) =>
        input === `/api/collections/${testIds.collection}/offline-pack`,
    );
    expect(exportCall).toBeGreaterThanOrEqual(0);
    expect(vi.mocked(runtime.prepareSave).mock.invocationCallOrder[0]).toBeLessThan(
      fetchMock.mock.invocationCallOrder[exportCall]!,
    );
    expect(
      await screen.findByText(/Encrypted offline copy saved/),
    ).toBeInTheDocument();
  });

  it("requires exact confirmation before fetching collection metadata", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === "/api/auth/status") return authResponse();
      throw new Error(`Unexpected request: ${String(input)}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<OfflinePackManager collectionId={testIds.collection} />);

    await waitFor(() => expect(runtime.inspect).toHaveBeenCalledOnce());
    await user.click(screen.getByRole("button", { name: "Prepare offline copy" }));
    await user.type(screen.getByLabelText("Offline passphrase"), "correct horse");
    await user.type(
      screen.getByLabelText("Confirm offline passphrase"),
      "correct Horse",
    );
    await user.click(
      screen.getByRole("button", { name: "Save encrypted offline copy" }),
    );

    expect(await screen.findByText("The passphrases must match exactly.")).toBeInTheDocument();
    expect(runtime.save).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("does not fetch a snapshot when the durable save fence cannot be prepared", async () => {
    vi.mocked(runtime.prepareSave).mockRejectedValue(
      new Error("Another deletion is still being confirmed."),
    );
    const user = userEvent.setup();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === "/api/auth/status") return authResponse();
      throw new Error(`Unexpected request: ${String(input)}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<OfflinePackManager collectionId={testIds.collection} />);

    await waitFor(() => expect(runtime.inspect).toHaveBeenCalledOnce());
    await user.click(screen.getByRole("button", { name: "Prepare offline copy" }));
    await user.type(screen.getByLabelText("Offline passphrase"), "correct horse");
    await user.type(
      screen.getByLabelText("Confirm offline passphrase"),
      "correct horse",
    );
    await user.click(
      screen.getByRole("button", { name: "Save encrypted offline copy" }),
    );

    expect(
      await screen.findByText("Another deletion is still being confirmed."),
    ).toBeInTheDocument();
    expect(runtime.save).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalledWith(
      `/api/collections/${testIds.collection}/offline-pack`,
      expect.anything(),
    );
  });

  it("removes the active device copy after confirmation", async () => {
    vi.mocked(runtime.inspect).mockResolvedValue(inspection);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(authResponse()));
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    render(<OfflinePackManager collectionId={testIds.collection} />);

    const remove = await screen.findByRole("button", {
      name: "Remove encrypted offline copy",
    });
    await user.click(remove);

    await waitFor(() => expect(runtime.purge).toHaveBeenCalledOnce());
    expect(
      screen.getByText("Encrypted offline copy removed from this device."),
    ).toBeInTheDocument();
  });

  it("offers confirmed recovery when the stored envelope cannot be inspected", async () => {
    vi.mocked(runtime.inspect).mockRejectedValue(new Error("damaged envelope"));
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(authResponse()));
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    render(<OfflinePackManager collectionId={testIds.collection} />);

    const recovery = await screen.findByRole("button", {
      name: "Clear unavailable offline data",
    });
    await user.click(recovery);

    await waitFor(() => expect(runtime.purge).toHaveBeenCalledOnce());
    expect(
      screen.getByText("Encrypted offline copy removed from this device."),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Prepare offline copy" })).toBeEnabled();
  });

  it("explains the encryption length limit without byte terminology", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(authResponse()));
    render(<OfflinePackManager collectionId={testIds.collection} />);
    await waitFor(() => expect(runtime.inspect).toHaveBeenCalledOnce());
    await user.click(screen.getByRole("button", { name: "Prepare offline copy" }));
    await user.type(screen.getByLabelText("Offline passphrase"), "🔐".repeat(65));
    await user.type(screen.getByLabelText("Confirm offline passphrase"), "🔐".repeat(65));
    await user.click(screen.getByRole("button", { name: "Save encrypted offline copy" }));
    expect(screen.getByRole("status")).toHaveTextContent(
      "This passphrase is too long for encryption. Shorten it or use fewer emoji or non-Latin characters.",
    );
    expect(screen.getByLabelText("Offline passphrase")).toHaveFocus();
    expect(screen.queryByText(/UTF-8 bytes/)).not.toBeInTheDocument();
    expect(runtime.save).not.toHaveBeenCalled();
  });
});
