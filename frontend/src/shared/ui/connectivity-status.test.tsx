import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ConnectivityProvider } from "@/shared/connectivity/connectivity-context";
import { ConnectivityStatus } from "@/shared/ui/connectivity-status";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function renderStatus(): void {
  render(
    <ConnectivityProvider>
      <ConnectivityStatus id="connection-status" />
    </ConnectivityProvider>,
  );
}

describe("ConnectivityStatus", () => {
  it("keeps the connected state visually quiet", () => {
    vi.spyOn(window.navigator, "onLine", "get").mockReturnValue(true);

    renderStatus();

    const status = screen.getByRole("status");
    expect(status).toHaveClass("srOnly");
    expect(status).toBeEmptyDOMElement();
    expect(
      screen.queryByText("OpenScholar can't be reached."),
    ).not.toBeInTheDocument();
  });

  it("announces confirmed disconnection and clears the notice after restoration", async () => {
    let isOnline = true;
    vi.spyOn(window.navigator, "onLine", "get").mockImplementation(
      () => isOnline,
    );
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("offline"))
      .mockResolvedValueOnce({ ok: true } as Response);
    vi.stubGlobal("fetch", fetchMock);

    renderStatus();

    isOnline = false;
    act(() => window.dispatchEvent(new Event("offline")));

    const status = screen.getByRole("status");
    expect(status).toHaveAttribute("id", "connection-status");
    await waitFor(() =>
      expect(status).toHaveTextContent("OpenScholar can't be reached."),
    );
    expect(status).toHaveTextContent(
      "Already-opened pages may still be readable.",
    );
    expect(status.closest(".connectivityRegion")).toHaveClass(
      "connectivityRegion--offline",
    );
    expect(screen.getByText("OpenScholar can't be reached.")).toBeVisible();
    expect(screen.getByRole("button", { name: "Check again" })).toBeVisible();

    isOnline = true;
    act(() => window.dispatchEvent(new Event("online")));

    await waitFor(() =>
      expect(status).toHaveTextContent("OpenScholar can be reached again."),
    );
    expect(status).toHaveClass("srOnly");
    expect(status.closest(".connectivityRegion")).not.toHaveClass(
      "connectivityRegion--offline",
    );
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("keeps a reachable self-hosted stack usable without internet access", async () => {
    let isOnline = false;
    vi.spyOn(window.navigator, "onLine", "get").mockImplementation(
      () => isOnline,
    );
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true } as Response));

    renderStatus();

    const status = screen.getByRole("status");
    await waitFor(() =>
      expect(status).toHaveTextContent("OpenScholar is still available"),
    );
    expect(status).toHaveTextContent("Limited connectivity reported.");
    expect(status).toHaveTextContent(
      "online research sources may be limited",
    );
    expect(status).not.toHaveTextContent("OpenScholar can't be reached.");

    isOnline = true;
    act(() => window.dispatchEvent(new Event("online")));
    await waitFor(() => expect(status).toBeEmptyDOMElement());
  });

  it("lets the user recheck a temporarily unavailable local stack", async () => {
    const user = userEvent.setup();
    vi.spyOn(window.navigator, "onLine", "get").mockReturnValue(false);
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("temporarily unavailable"))
      .mockResolvedValueOnce({ ok: true } as Response);
    vi.stubGlobal("fetch", fetchMock);

    renderStatus();

    await screen.findByText("OpenScholar can't be reached.");
    await user.click(screen.getByRole("button", { name: "Check again" }));

    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent(
        "OpenScholar is still available",
      ),
    );
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(
      screen.queryByRole("button", { name: "Check again" }),
    ).not.toBeInTheDocument();
  });
});
