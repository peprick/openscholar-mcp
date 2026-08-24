import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { IdentifierLookup } from "@/features/search/identifier-lookup";
import { ConnectivityProvider } from "@/shared/connectivity/connectivity-context";
import { testIds } from "@/test/fixtures";

const navigation = vi.hoisted(() => ({
  push: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => navigation,
}));

function response(status: number, body: unknown): Response {
  return {
    json: vi.fn().mockResolvedValue(body),
    ok: status >= 200 && status < 300,
    status,
  } as unknown as Response;
}

function renderLookup(): void {
  render(
    <ConnectivityProvider>
      <IdentifierLookup />
    </ConnectivityProvider>,
  );
}

afterEach(() => {
  cleanup();
  navigation.push.mockReset();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("IdentifierLookup", () => {
  it("looks up a trimmed identifier and opens the matching paper", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockResolvedValue(
      response(200, {
        paperId: testIds.paper,
        identifierType: "DOI",
        normalizedValue: "10.1000/example",
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    renderLookup();

    await user.type(
      screen.getByRole("textbox", {
        name: "DOI, arXiv ID, or OpenAlex work ID",
      }),
      "  doi:10.1000/example  ",
    );
    await user.click(screen.getByRole("button", { name: "Open paper" }));

    const query = new URLSearchParams({ identifier: "doi:10.1000/example" });
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(`/api/papers/resolve?${query}`, {
        cache: "no-store",
      }),
    );
    expect(navigation.push).toHaveBeenCalledWith(`/papers/${testIds.paper}`);
  });

  it("shows an accessible inline error for an empty identifier", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    renderLookup();

    const input = screen.getByRole("textbox", {
      name: "DOI, arXiv ID, or OpenAlex work ID",
    });
    await user.click(screen.getByRole("button", { name: "Open paper" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(
      "Enter a DOI, arXiv ID, or OpenAlex work ID.",
    );
    expect(alert).toHaveFocus();
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAttribute(
      "aria-describedby",
      "paper-identifier-hint paper-identifier-error",
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("turns a private not-found response into helpful user guidance", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        response(404, {
          title: "Paper not found",
          status: 404,
          detail: "No visible paper matched this identifier.",
          code: "PAPER_IDENTIFIER_NOT_FOUND",
        }),
      ),
    );
    renderLookup();

    await user.type(
      screen.getByRole("textbox", {
        name: "DOI, arXiv ID, or OpenAlex work ID",
      }),
      "W2741809807",
    );
    await user.click(screen.getByRole("button", { name: "Open paper" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(
      "This paper isn’t in your OpenScholar workspace yet. Search for it by topic first.",
    );
    expect(alert).not.toHaveTextContent("No visible paper");
    expect(
      screen.getByRole("textbox", {
        name: "DOI, arXiv ID, or OpenAlex work ID",
      }),
    ).toHaveAttribute("aria-invalid", "false");
    expect(navigation.push).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Open paper" })).toBeEnabled();
  });

  it("keeps the action disabled and announces progress while looking up", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(() => undefined)));
    renderLookup();

    await user.type(
      screen.getByRole("textbox", {
        name: "DOI, arXiv ID, or OpenAlex work ID",
      }),
      "arXiv:2401.12345",
    );
    await user.click(screen.getByRole("button", { name: "Open paper" }));

    expect(
      await screen.findByRole("button", { name: "Opening…" }),
    ).toBeDisabled();
  });

  it("recovers with plain guidance when the lookup cannot be reached", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
    renderLookup();

    await user.type(
      screen.getByRole("textbox", {
        name: "DOI, arXiv ID, or OpenAlex work ID",
      }),
      "2401.12345",
    );
    await user.click(screen.getByRole("button", { name: "Open paper" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "OpenScholar is temporarily unavailable. Please try again.",
    );
    expect(screen.getByRole("button", { name: "Open paper" })).toBeEnabled();
  });
});
