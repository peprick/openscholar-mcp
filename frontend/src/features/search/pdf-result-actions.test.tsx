import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { PdfResultActions } from "@/features/search/pdf-result-actions";
import { paperAccessResponseFixture, testIds } from "@/test/fixtures";

const navigation = vi.hoisted(() => ({ push: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => navigation,
}));

function accessResponse(
  body = paperAccessResponseFixture({
    freshUntil: "2099-08-18T14:31:00Z",
  }),
): Response {
  return {
    json: vi.fn().mockResolvedValue(body),
    ok: true,
  } as unknown as Response;
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}

afterEach(() => {
  cleanup();
  navigation.push.mockReset();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("PdfResultActions", () => {
  it("checks access before opening the verified reader", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockResolvedValue(accessResponse());
    vi.stubGlobal("fetch", fetchMock);
    render(<PdfResultActions paperId={testIds.paper} title="Research paper" />);

    await user.click(
      screen.getByRole("button", { name: "View PDF: Research paper" }),
    );

    await waitFor(() =>
      expect(navigation.push).toHaveBeenCalledWith(
        `/papers/${testIds.paper}/read/${testIds.location}`,
      ),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/papers/${testIds.paper}/access`,
      expect.objectContaining({
        body: JSON.stringify({ forceRefresh: false }),
        method: "POST",
      }),
    );
  });

  it("carries an explicit download intent to the verified reader", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(accessResponse()));
    render(<PdfResultActions paperId={testIds.paper} title="Research paper" />);

    await user.click(
      screen.getByRole("button", { name: "Download PDF: Research paper" }),
    );

    await waitFor(() =>
      expect(navigation.push).toHaveBeenCalledWith(
        `/papers/${testIds.paper}/read/${testIds.location}?download=1`,
      ),
    );
  });

  it("uses the backend check time instead of rejecting a response on a later browser clock", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(accessResponse(paperAccessResponseFixture())),
    );
    render(<PdfResultActions paperId={testIds.paper} title="Research paper" />);

    await user.click(
      screen.getByRole("button", { name: "View PDF: Research paper" }),
    );

    await waitFor(() =>
      expect(navigation.push).toHaveBeenCalledWith(
        `/papers/${testIds.paper}/read/${testIds.location}`,
      ),
    );
  });

  it("announces a pending check and aborts it when the result card unmounts", async () => {
    const user = userEvent.setup();
    const pending = deferred<Response>();
    const fetchMock = vi.fn().mockReturnValue(pending.promise);
    vi.stubGlobal("fetch", fetchMock);
    const view = render(
      <PdfResultActions paperId={testIds.paper} title="Research paper" />,
    );

    await user.click(
      screen.getByRole("button", { name: "View PDF: Research paper" }),
    );

    expect(
      screen.getByRole("button", {
        name: "Checking PDF before viewing: Research paper",
      }),
    ).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Checking for a verified PDF…",
    );
    const signal = fetchMock.mock.calls[0]?.[1]?.signal as AbortSignal;
    expect(signal.aborted).toBe(false);

    view.unmount();
    expect(signal.aborted).toBe(true);
    pending.resolve(accessResponse());
    await Promise.resolve();
    await Promise.resolve();
    expect(navigation.push).not.toHaveBeenCalled();
  });

  it("does not expose a provider-reported URL when verification fails", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        json: vi.fn().mockResolvedValue({
          type: "urn:openscholar:problem:access-unavailable",
          title: "Access unavailable",
          status: 503,
          detail: "Access verification is temporarily unavailable.",
          instance: `/api/papers/${testIds.paper}/access`,
          code: "ACCESS_UNAVAILABLE",
        }),
        ok: false,
      }),
    );
    render(<PdfResultActions paperId={testIds.paper} title="Research paper" />);

    await user.click(
      screen.getByRole("button", { name: "View PDF: Research paper" }),
    );

    expect(
      await screen.findByText("Access verification is temporarily unavailable."),
    ).toBeVisible();
    expect(navigation.push).not.toHaveBeenCalled();
    expect(document.querySelector("a[href$='.pdf']")).toBeNull();
  });
});
