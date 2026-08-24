import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { PrivacyCenter } from "@/features/privacy/privacy-center";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
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
      "OpenScholar could not confirm whether deletion completed. Refresh this page to check your workspace before trying again.",
    );
    expect(screen.getByRole("alert")).not.toHaveTextContent(
      "Nothing was deleted.",
    );
    expect(input).toBeInTheDocument();
    expect(input).toHaveValue("DELETE_MY_DATA");
    expect(
      screen.getByRole("button", { name: "Delete my OpenScholar data" }),
    ).toBeEnabled();
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
      "OpenScholar could not confirm whether deletion completed. Refresh this page to check your workspace before trying again.",
    );
    expect(input).toHaveValue("DELETE_MY_DATA");
  });

  it("downloads the export with a fixed private-data filename", async () => {
    const user = userEvent.setup();
    const createObjectURL = vi.fn().mockReturnValue("blob:private-export");
    const revokeObjectURL = vi.fn();
    const NativeURL = URL;
    class DownloadURL extends NativeURL {
      static createObjectURL = createObjectURL;
      static revokeObjectURL = revokeObjectURL;
    }
    vi.stubGlobal("URL", DownloadURL);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse({ searches: [], collections: [], savedPapers: [] }),
      ),
    );
    let downloadedFilename = "";
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloadedFilename = this.download;
    });
    render(<PrivacyCenter />);

    await user.click(screen.getByRole("button", { name: "Download my data" }));

    expect(await screen.findByRole("status")).toHaveTextContent(
      "Your OpenScholar data export was downloaded.",
    );
    expect(downloadedFilename).toBe("openscholar-personal-data.json");
    expect(createObjectURL).toHaveBeenCalledOnce();
    await waitFor(() =>
      expect(revokeObjectURL).toHaveBeenCalledWith("blob:private-export"),
    );
  });

  it("announces export failures without exposing a broken download", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, "click");
    render(<PrivacyCenter />);

    await user.click(screen.getByRole("button", { name: "Download my data" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "OpenScholar could not prepare your data export. Please try again.",
    );
    expect(clickSpy).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Download my data" })).toBeEnabled();
  });
});
