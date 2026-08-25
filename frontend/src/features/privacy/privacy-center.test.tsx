import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { PrivacyCenter } from "@/features/privacy/privacy-center";
import { loadOfflinePackRuntime } from "@/pwa/offline-pack-loader";

vi.mock("@/pwa/offline-pack-loader", () => ({
  loadOfflinePackRuntime: vi.fn(),
}));

const offlineRuntime = {
  beginDeletion: vi.fn(),
  completeDeletion: vi.fn().mockResolvedValue(true),
};
const deletionFence = {
  collectionDigest: null,
  deletionId: "opaque-account-deletion-id",
};

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

beforeEach(() => {
  vi.stubGlobal("indexedDB", {});
  offlineRuntime.beginDeletion.mockReset().mockResolvedValue(deletionFence);
  offlineRuntime.completeDeletion.mockReset().mockResolvedValue(true);
  vi.mocked(loadOfflinePackRuntime).mockResolvedValue(offlineRuntime as never);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.mocked(loadOfflinePackRuntime).mockReset();
  offlineRuntime.beginDeletion.mockReset();
  offlineRuntime.completeDeletion.mockReset();
});

describe("PrivacyCenter", () => {
  it("requires the exact confirmation phrase before deleting personal data", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    render(<PrivacyCenter />);

    const input = screen.getByRole("textbox", {
      name: "Type DELETE_MY_DATA to confirm",
    });
    const deleteButton = screen.getByRole("button", {
      name: "Delete my OpenScholar data",
    });
    expect(deleteButton).toBeDisabled();
    expect(input).toHaveAttribute("aria-describedby", "delete-data-help");

    await user.type(input, "delete_my_data");
    expect(deleteButton).toBeDisabled();
    expect(fetchMock).not.toHaveBeenCalled();

    await user.clear(input);
    await user.type(input, "DELETE_MY_DATA");
    expect(deleteButton).toBeEnabled();
    await user.click(deleteButton);

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith("/api/privacy/account", {
        method: "DELETE",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ confirmation: "DELETE_MY_DATA" }),
      }),
    );
    expect(offlineRuntime.beginDeletion).toHaveBeenCalledWith();
    expect(offlineRuntime.completeDeletion).toHaveBeenCalledWith(deletionFence);
    expect(offlineRuntime.beginDeletion.mock.invocationCallOrder[0]).toBeLessThan(
      fetchMock.mock.invocationCallOrder[0]!,
    );
    expect(fetchMock.mock.invocationCallOrder[0]).toBeLessThan(
      offlineRuntime.completeDeletion.mock.invocationCallOrder[0]!,
    );
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Your OpenScholar data was deleted.",
    );
    expect(
      screen.getByText("You can start again with an empty research workspace."),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Search research" })).toHaveAttribute(
      "href",
      "/",
    );
  });

  it("does not claim deletion was rolled back when completion cannot be confirmed", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(
          {
            title: "Request failed",
            status: 503,
            detail: "Nothing was deleted.",
            code: "BACKEND_UNREACHABLE",
          },
          503,
        ),
      ),
    );
    render(<PrivacyCenter />);

    const input = screen.getByRole("textbox", {
      name: "Type DELETE_MY_DATA to confirm",
    });
    await user.type(input, "DELETE_MY_DATA");
    await user.click(
      screen.getByRole("button", { name: "Delete my OpenScholar data" }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "OpenScholar could not confirm whether server deletion completed. The encrypted offline copy on this device was already removed. Refresh this page to check your workspace before trying again.",
    );
    expect(screen.getByRole("alert")).not.toHaveTextContent(
      "Nothing was deleted.",
    );
    expect(input).toBeInTheDocument();
    expect(input).toHaveValue("DELETE_MY_DATA");
    expect(
      screen.getByRole("button", { name: "Delete my OpenScholar data" }),
    ).toBeEnabled();
    expect(offlineRuntime.completeDeletion).not.toHaveBeenCalled();
  });

  it("treats a lost deletion response as an unknown completion state", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("connection closed after send")),
    );
    render(<PrivacyCenter />);

    const input = screen.getByRole("textbox", {
      name: "Type DELETE_MY_DATA to confirm",
    });
    await user.type(input, "DELETE_MY_DATA");
    await user.click(
      screen.getByRole("button", { name: "Delete my OpenScholar data" }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "OpenScholar could not confirm whether server deletion completed. The encrypted offline copy on this device was already removed. Refresh this page to check your workspace before trying again.",
    );
    expect(input).toHaveValue("DELETE_MY_DATA");
    expect(offlineRuntime.completeDeletion).not.toHaveBeenCalled();
  });

  it("does not start server deletion when the local encrypted copy cannot be removed", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    offlineRuntime.beginDeletion.mockRejectedValue(new Error("storage blocked"));
    render(<PrivacyCenter />);

    await user.type(
      screen.getByRole("textbox", {
        name: "Type DELETE_MY_DATA to confirm",
      }),
      "DELETE_MY_DATA",
    );
    await user.click(
      screen.getByRole("button", { name: "Delete my OpenScholar data" }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "OpenScholar could not remove this browser’s encrypted offline copy, so server deletion did not start.",
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("accepts an already-cleared browser fence after confirmed account deletion", async () => {
    offlineRuntime.completeDeletion.mockResolvedValue(false);
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );
    render(<PrivacyCenter />);

    const input = screen.getByRole("textbox", {
      name: "Type DELETE_MY_DATA to confirm",
    });
    await user.type(input, "DELETE_MY_DATA");
    await user.click(
      screen.getByRole("button", { name: "Delete my OpenScholar data" }),
    );

    expect(await screen.findByRole("status")).toHaveTextContent(
      "Your OpenScholar data was deleted.",
    );
    expect(offlineRuntime.completeDeletion).toHaveBeenCalledWith(deletionFence);
    expect(input).not.toBeInTheDocument();
    expect(
      screen.getByText("You can start again with an empty research workspace."),
    ).toBeInTheDocument();
  });

  it("reports local completion errors after confirmed account deletion", async () => {
    offlineRuntime.completeDeletion.mockRejectedValue(
      new Error("browser storage unavailable"),
    );
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );
    render(<PrivacyCenter />);

    const input = screen.getByRole("textbox", {
      name: "Type DELETE_MY_DATA to confirm",
    });
    await user.type(input, "DELETE_MY_DATA");
    await user.click(
      screen.getByRole("button", { name: "Delete my OpenScholar data" }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Your OpenScholar data was deleted, but browser cleanup could not be completed. Refresh before saving another offline copy.",
    );
    expect(offlineRuntime.completeDeletion).toHaveBeenCalledWith(deletionFence);
    expect(input).toHaveValue("DELETE_MY_DATA");
    expect(
      screen.queryByText("You can start again with an empty research workspace."),
    ).not.toBeInTheDocument();
  });

  it("starts the native same-origin export without buffering browser bytes", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const blobSpy = vi.spyOn(Response.prototype, "blob");
    const createObjectURL = vi.fn();
    const revokeObjectURL = vi.fn();
    const NativeURL = URL;
    class DownloadURL extends NativeURL {
      static createObjectURL = createObjectURL;
      static revokeObjectURL = revokeObjectURL;
    }
    vi.stubGlobal("URL", DownloadURL);
    render(<PrivacyCenter />);

    const download = screen.getByRole("link", { name: "Download my data" });
    expect(download).toHaveAttribute("href", "/api/privacy/export");
    expect(download).toHaveAttribute("target", "_blank");
    expect(download).toHaveAttribute("rel", "noopener");
    expect(download).toHaveAttribute(
      "aria-describedby",
      "export-download-help",
    );
    expect(
      screen.getByText(/error opens separately so this page stays available/i),
    ).toBeInTheDocument();
    download.addEventListener("click", (event) => event.preventDefault(), {
      once: true,
    });

    await user.click(download);

    expect(await screen.findByRole("status")).toHaveTextContent(
      "Your OpenScholar data export download started. Your browser will show when it finishes or if it fails.",
    );
    expect(screen.getByRole("status")).not.toHaveTextContent(
      /downloaded|completed/i,
    );
    expect(fetchMock).not.toHaveBeenCalled();
    expect(blobSpy).not.toHaveBeenCalled();
    expect(createObjectURL).not.toHaveBeenCalled();
    expect(revokeObjectURL).not.toHaveBeenCalled();
  });
});
